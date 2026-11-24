/*
 * Copyright (c) 2021 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.bundlebee.core.kube;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.bundlebee.core.event.OnKubeRequest;
import io.yupiik.bundlebee.core.http.DryRunClient;
import io.yupiik.bundlebee.core.http.RateLimitedClient;
import io.yupiik.bundlebee.core.http.RateLimiter;
import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.impl.CurlLoggerListener;
import io.yupiik.fusion.httpclient.core.listener.impl.DefaultTimeout;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

// a bit more complex than needed mainly to limit the behavior changes with v1
@ApplicationScoped
public class KubernetesClientProducer {
    private KubeConfig metadata;
    private HttpClient policies;

    @Bean
    @ApplicationScoped
    public NamespaceProvider namespaceProvider(
            final RuntimeContainer container, final KubernetesConfiguration settings) {
        // Explicit overrides win; otherwise retain Fusion's kubeconfig/service-account namespace.
        return () -> {
            final var namespace = settings.namespace();
            if (namespace != null && !namespace.isBlank() && !"default".equals(namespace)) {
                return namespace;
            }
            try (final var client = container.lookup(KubernetesClient.class)) {
                return client.instance().namespace().orElse("default");
            }
        };
    }

    @Destroy
    public void close() {
        final var owned = policies;
        policies = null;
        if (owned != null) {
            try {
                ((AutoCloseable) owned).close();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // Producer methods are cached by the application scope, not subclassed as KubernetesClient proxies.
    // Fusion owns and closes the produced AutoCloseable, including its internal exec JSON mapper.
    @Bean
    @ApplicationScoped
    @Order(2000) // Prefer BundleBee's configuration and HTTP policies over integration defaults.
    public KubernetesClient kubernetesClient(
            final KubernetesConfiguration settings,
            final Configuration configuration,
            final HttpClient shared,
            final JsonMapper mapper,
            final Yaml yaml,
            final Emitter events) {
        final var clientConfiguration = new KubernetesClientConfiguration()
                .setMaster("auto".equals(settings.api()) ? serviceEndpoint(configuration) : settings.api())
                .setCertificates(settings.certificates())
                .setSkipTls(!settings.validateSSL())
                .setToken(
                        settings.tokenFile() != null
                                        && !"none".equals(settings.tokenFile())
                                        && Files.exists(Path.of(settings.tokenFile()))
                                ? settings.tokenFile()
                                : null)
                .setClientCustomizer(builder -> customize(builder, settings, shared));
        final var location = configuration.get("kubeconfig").orElse(settings.kubeconfig());
        final var tree = load(location, configuration, mapper, yaml, clientConfiguration);
        // The explicit token applies only when no kubeconfig is loaded, as in the original client.
        if (tree == null && configured(settings.token())) {
            clientConfiguration.setToken("none").setTokenEvaluator(settings::token);
        }
        if (tree != null) {
            if (configured(settings.context())) {
                tree.put("current-context", settings.context());
            }
            validateContext(tree);
        }
        clientConfiguration.setClientWrapper(client -> policies = policies(client, events, settings));
        final var client = new KubernetesClient(clientConfiguration) {
            @Override
            protected Map<String, Object> loadKubeConfig(final Path kubeconfig) {
                return tree;
            }
        };
        metadata = metadata(tree, client.base().toString());
        return client;
    }

    public KubeConfig loadedConfiguration() {
        return metadata;
    }

    private String serviceEndpoint(final Configuration configuration) {
        return configuration
                .get("kubernetes.service.host")
                .map(host -> "https://" + host + ':'
                        + configuration.get("kubernetes.service.port").orElse("443"))
                .orElse("http://localhost:8080");
    }

    private void customize(
            final HttpClient.Builder builder, final KubernetesConfiguration settings, final HttpClient shared) {
        builder.followRedirects(shared.followRedirects()).version(shared.version());
        shared.executor().ifPresent(builder::executor);
        shared.connectTimeout()
                .or(() -> settings.http().timeout() > 0
                        ? Optional.of(Duration.ofMillis(settings.http().timeout()))
                        : Optional.empty())
                .ifPresent(builder::connectTimeout);
        final var proxy = settings.proxy();
        if (configured(proxy.host())) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
            if (configured(proxy.username()) && configured(proxy.password())) {
                if (proxy.setProxySystemProperties()) {
                    System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
                }
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY
                                && proxy.host().equals(getRequestingHost())
                                && proxy.port() == getRequestingPort()) {
                            return new PasswordAuthentication(
                                    proxy.username(),
                                    proxy.password() == null || UNSET.equals(proxy.password())
                                            ? new char[0]
                                            : proxy.password().toCharArray());
                        }
                        return null;
                    }
                });
            }
        }
    }

    private HttpClient eventClient(final HttpClient client, final Emitter events) {
        return new DelegatingHttpClient(client) {
            @Override
            @SuppressWarnings("unchecked")
            public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler)
                    throws IOException, InterruptedException {
                final var event = new OnKubeRequest(request);
                events.emit(event);
                if (event.getUserResponse() != null) {
                    try {
                        return (HttpResponse<T>)
                                event.getUserResponse().toCompletableFuture().get();
                    } catch (final ExecutionException e) {
                        throw new IOException(e.getCause());
                    }
                }
                return delegate.send(event.getUserRequest() == null ? request : event.getUserRequest(), handler);
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    final HttpRequest request,
                    final HttpResponse.BodyHandler<T> handler,
                    final HttpResponse.PushPromiseHandler<T> push) {
                return sendAsync(request, handler, push, true);
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
                return sendAsync(request, handler, null, false);
            }

            @SuppressWarnings("unchecked")
            private <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    final HttpRequest request,
                    final HttpResponse.BodyHandler<T> handler,
                    final HttpResponse.PushPromiseHandler<T> push,
                    final boolean withPush) {
                final var event = new OnKubeRequest(request);
                events.emit(event);
                if (event.getUserResponse() != null) {
                    return (CompletableFuture<HttpResponse<T>>)
                            (CompletableFuture<?>) event.getUserResponse().toCompletableFuture();
                }
                final var effective = event.getUserRequest() == null ? request : event.getUserRequest();
                return (withPush
                                ? delegate.sendAsync(effective, handler, push)
                                : delegate.sendAsync(effective, handler))
                        .whenCompleteAsync(
                                (response, error) -> {
                                    // Restore BundleBee's executor/classloader boundary for completion callbacks.
                                },
                                delegate.executor().orElseGet(ForkJoinPool::commonPool));
            }
        };
    }

    private HttpClient policies(
            final HttpClient transport, final Emitter events, final KubernetesConfiguration settings) {
        HttpClient client = eventClient(transport, events);
        if (settings.dryRun()) {
            // Interception is inside dry run: mocked requests must never reach observers or the network.
            client = new DryRunClient(client, settings.skipDryRunForGet());
        }
        if (settings.dryRun() || settings.verbose()) {
            final var loggedDelegate = client;
            client =
                    new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                            .setDelegate(client)
                            .setRequestListeners(List.of(new CurlLoggerListener(new CurlLoggerListener.Configuration(
                                            Logger.getLogger(KubernetesClientProducer.class.getName()))
                                    .setLogPayload(true, true))))) {
                        @Override
                        public void close() {
                            super.close();
                            // ExtendedHttpClient deliberately borrows its delegate; this producer owns the chain.
                            try {
                                ((AutoCloseable) loggedDelegate).close();
                            } catch (final Exception e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    };
        }
        if (settings.rateLimiter().enabled()) {
            client = new RateLimitedClient(
                    client,
                    new RateLimiter(
                            settings.rateLimiter().permits(),
                            settings.rateLimiter().window(),
                            Clock.systemUTC()));
        }
        return settings.http().timeout() > 0
                ? new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                        .setDelegate(client)
                        .setRequestListeners(List.of(new DefaultTimeout(
                                Duration.ofMillis(settings.http().timeout())))))
                : client;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(
            final String location,
            final Configuration configuration,
            final JsonMapper mapper,
            final Yaml yaml,
            final KubernetesClientConfiguration clientConfiguration) {
        if ("explicit".equals(location)) {
            return null;
        }
        final String content;
        try {
            if (location.stripLeading().startsWith("{")
                    || location.contains("\n")
                    || location.contains("apiVersion:")) {
                content = location;
                // Logical location only: inline credential paths are relative to the working directory.
                clientConfiguration.setKubeconfig(Path.of("kubeconfig"));
            } else {
                var path = "auto".equals(location)
                        ? Path.of(
                                configuration.get("user.home").orElseGet(() -> System.getProperty("user.home")),
                                ".kube",
                                "config")
                        : Path.of(location);
                if (!Files.exists(path)) {
                    // Historically a missing explicit path also falls back to ~/.kube/config.
                    path = Path.of(
                            configuration.get("user.home").orElseGet(() -> System.getProperty("user.home")),
                            ".kube",
                            "config");
                    if (!Files.exists(path)) {
                        return null;
                    }
                }
                clientConfiguration.setKubeconfig(path);
                content = Files.readString(path, UTF_8);
            }
        } catch (final IOException e) {
            throw new IllegalArgumentException("Can't read kubeconfig: " + location, e);
        }
        final var parsed =
                content.stripLeading().startsWith("{") ? mapper.fromString(Object.class, content) : yaml.load(content);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Kubeconfig must be an object");
        }
        // SnakeYAML supports the full existing YAML syntax; Fusion consumes the parsed tree directly.
        return new LinkedHashMap<>((Map<String, Object>) parsed);
    }

    private void validateContext(final Map<String, Object> tree) {
        // Keep fail-fast validation: Fusion otherwise falls back to the configured endpoint.
        var selected = tree.get("current-context");
        if ((selected == null || "".equals(selected))
                && tree.get("contexts") instanceof List<?> contexts
                && !contexts.isEmpty()
                && contexts.get(0) instanceof Map<?, ?> first) {
            selected = first.get("name");
        }
        final var context = namedSection(tree, "contexts", "context", selected);
        namedSection(tree, "clusters", "cluster", context.get("cluster"));
        namedSection(tree, "users", "user", context.get("user"));
    }

    private Map<?, ?> namedSection(
            final Map<String, Object> tree, final String entries, final String section, final Object name) {
        if (name != null && tree.get(entries) instanceof List<?> list) {
            for (final var item : list) {
                if (item instanceof Map<?, ?> named
                        && name.equals(named.get("name"))
                        && named.get(section) instanceof Map<?, ?> value) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException(
                "No kube " + section + " '" + name + "', ensure to configure kube client please");
    }

    private KubeConfig metadata(final Map<String, Object> tree, final String base) {
        if (tree != null && tree.get("clusters") instanceof List<?> clusters && !clusters.isEmpty()) {
            return new KubeConfig(clusters.stream()
                    .map(item -> {
                        final var named = (Map<?, ?>) item;
                        final var cluster = (Map<?, ?>) named.get("cluster");
                        return new KubeConfig.NamedCluster(
                                (String) named.get("name"), new KubeConfig.Cluster((String) cluster.get("server")));
                    })
                    .toList());
        }
        return new KubeConfig(List.of(new KubeConfig.NamedCluster("default", new KubeConfig.Cluster(base))));
    }

    private boolean configured(final String value) {
        return value != null && !value.isBlank() && !UNSET.equals(value);
    }
}
