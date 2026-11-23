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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.bundlebee.core.configuration.GlobalConfiguration;
import io.yupiik.bundlebee.core.event.OnKubeRequest;
import io.yupiik.bundlebee.core.service.ContainerSanitizer;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class KubernetesClientProducerTest {
    @Test
    void applicationScopedClientSurvivesLookupCloseWhileResponseIsPending() throws Exception {
        final var received = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            received.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Response was not released");
                }
                final var body = "{\"data\":{}}".getBytes(UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (final var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                exchange.close();
            }
        });
        server.start();
        final var values = Map.of(
                "kubeconfig",
                "explicit",
                "bundlebee.kube.tokenFile",
                "none",
                "bundlebee.kube.api",
                "http://localhost:" + server.getAddress().getPort());
        try (final var container = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                ApplicationScoped.class, ConfigurationSource.class, () -> values::get))
                        .start();
                final var lookup = container.lookup(KubeClient.class)) {
            final var client = lookup.instance();
            final var response = client.findSecret("default", "test").toCompletableFuture();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            release.countDown();
            assertEquals(Map.of("data", Map.of()), response.get(10, TimeUnit.SECONDS));
            assertEquals(
                    Map.of("data", Map.of()),
                    client.findSecret("default", "second").toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(
                    values.get("bundlebee.kube.api"),
                    client.getLoadedKubeConfig()
                            .getClusters()
                            .get(0)
                            .getCluster()
                            .getServer());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void namespaceOverrideDoesNotResolveMalformedKubeconfig() throws Exception {
        final var config = Files.writeString(temp.resolve("malformed.yaml"), "clusters: [unterminated\n");
        for (final var namespace : List.of("target", "default")) {
            final var values = Map.of("kubeconfig", config.toString(), "bundlebee.kube.namespace", namespace);
            try (final var container = ConfiguringContainer.of()
                            .register(new ProvidedInstanceBean<>(
                                    ApplicationScoped.class, ConfigurationSource.class, () -> values::get))
                            .start();
                    final var provider = container.lookup(NamespaceProvider.class)) {
                final var instance = provider.instance();
                if ("target".equals(namespace)) {
                    assertEquals("target", instance.namespace());
                } else {
                    assertThrows(RuntimeException.class, instance::namespace);
                }
            }
        }
    }

    @Test
    void kubeClientDefersValidationUntilTransportOrMetadataIsUsed() throws Exception {
        final var config = Files.writeString(temp.resolve("malformed.yaml"), "clusters: [unterminated\n");
        final var values = Map.of("kubeconfig", config.toString());
        try (final var container = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                ApplicationScoped.class, ConfigurationSource.class, () -> values::get))
                        .start();
                final var client = container.lookup(KubeClient.class)) {
            final var kube = client.instance();
            assertEquals(
                    1,
                    kube.forDescriptor(null, "{\"kind\":\"ConfigMap\"}", "json", CompletableFuture::completedFuture)
                            .toCompletableFuture()
                            .get()
                            .size());
            assertThrows(RuntimeException.class, kube::getLoadedKubeConfig);
            assertThrows(RuntimeException.class, () -> kube.toBaseUri(Map.of(), "configmaps", "default"));
            assertThrows(RuntimeException.class, () -> kube.findSecret("default", "test"));
        }
    }

    @Test
    void legacyConfigurationDefaultsArePreserved() {
        final var settings = settings(Configuration.of(Map.of())).kube();
        assertEquals("http://localhost:8080", settings.api());
        assertEquals("default", settings.namespace());
        assertEquals("none", settings.tokenFile());
        assertTrue(settings.proxy().setProxySystemProperties());
    }

    @Test
    void proxyAuthenticationAndSystemPropertySwitchArePreserved() throws Exception {
        final var key = "jdk.http.auth.tunneling.disabledSchemes";
        final var previous = System.getProperty(key);
        try {
            for (final var enabled : List.of(false, true)) {
                System.setProperty(key, "Basic");
                try (final var fixture = new Fixture(
                        Map.of(
                                "bundlebee.kube.proxy.host",
                                "localhost",
                                "bundlebee.kube.proxy.username",
                                "alice",
                                "bundlebee.kube.proxy.password",
                                "secret",
                                "bundlebee.kube.proxy.setProxySystemProperties",
                                Boolean.toString(enabled)),
                        event -> {})) {
                    assertEquals(enabled ? "" : "Basic", System.getProperty(key));
                    final var proxy = fixture.fusion
                            .proxy()
                            .orElseThrow()
                            .select(URI.create("https://cluster.test"))
                            .get(0);
                    assertEquals(new InetSocketAddress("localhost", 3128), proxy.address());
                    final var authenticator = fixture.fusion.authenticator().orElseThrow();
                    final var credentials = authenticator.requestPasswordAuthenticationInstance(
                            "localhost", null, 3128, "http", "proxy", "basic", null, Authenticator.RequestorType.PROXY);
                    assertEquals("alice", credentials.getUserName());
                    assertEquals("secret", new String(credentials.getPassword()));
                    assertEquals(
                            null,
                            authenticator.requestPasswordAuthenticationInstance(
                                    "localhost",
                                    null,
                                    3128,
                                    "http",
                                    "server",
                                    "basic",
                                    null,
                                    Authenticator.RequestorType.SERVER));
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void missingKubeconfigPathFallsBackToHomeConfiguration() throws Exception {
        final var config = temp.resolve(".kube/config");
        Files.createDirectories(config.getParent());
        Files.writeString(config, kubeconfig("http://localhost:12345", Map.of("token", "home-token"), Map.of()));
        try (final var fixture = new Fixture(
                Map.of("kubeconfig", temp.resolve("missing").toString(), "user.home", temp.toString()), event -> {})) {
            assertEquals("http://localhost:12345", fixture.getBaseApi());
            assertEquals(
                    "Bearer home-token",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
        }
    }

    @Test
    void execWithoutApiVersionIsStillSupported() throws Exception {
        final var user = Map.<String, Object>of(
                "exec",
                Map.of(
                        "command",
                        "/bin/sh",
                        "args",
                        List.of(
                                "-c",
                                "printf '%s' '{\"kind\":\"ExecCredential\",\"status\":{\"token\":\"legacy-exec\"}}'")));
        try (final var fixture =
                new Fixture(Map.of("kubeconfig", kubeconfig("http://localhost:12345", user, Map.of())), event -> {})) {
            assertEquals(
                    "Bearer legacy-exec",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
        }
    }

    @Test
    void defaultNamespaceStillDefersToKubeconfig() throws Exception {
        final var config = kubeconfig("http://localhost:12345", Map.of(), Map.of())
                .replace("\"user\":\"test\"", "\"namespace\":\"configured\",\"user\":\"test\"");
        try (final var fixture =
                new Fixture(Map.of("kubeconfig", config, "bundlebee.kube.namespace", "default"), event -> {})) {
            assertEquals("configured", fixture.namespace());
        }
    }

    @Test
    void kubeconfigServerNormalizationAndBasePathArePreserved() throws Exception {
        try (final var fixture = new Fixture(
                Map.of("kubeconfig", kubeconfig("localhost:12345/prefix/", Map.of(), Map.of())), event -> {})) {
            assertEquals("http://localhost:12345/prefix", fixture.getBaseApi());
            assertEquals(
                    "http://localhost:12345/prefix/api/a%2Fb?q=a%2Fb",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api/a%2Fb?q=a%2Fb")
                            .uri()
                            .toString());
        }
    }

    @Test
    void missingCurrentContextUsesFirstDeclaredContext() throws Exception {
        final var config = """
                {"clusters":[{"name":"cluster","cluster":{"server":"http://localhost:12345"}}],
                 "contexts":[{"name":"first","context":{"cluster":"cluster","user":"alice"}},
                             {"name":"second","context":{"cluster":"cluster","user":"bob"}}],
                 "users":[{"name":"alice","user":{"token":"selected"}},
                          {"name":"bob","user":{"token":"other"}}]}
                """;
        try (final var fixture = new Fixture(Map.of("kubeconfig", config), event -> {})) {
            assertEquals("http://localhost:12345", fixture.getBaseApi());
            assertEquals(
                    "Bearer selected",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
        }
    }

    @Test
    void unknownContextFailsInsteadOfUsingUnrelatedEndpoint() {
        final var config = kubeconfig("http://localhost:12345", Map.of(), Map.of());
        final var configuration = Configuration.of(Map.of("kubeconfig", config, "bundlebee.kube.context", "missing"));
        final var settings = settings(configuration).kube();
        try (final var mapper = new JsonMapperImpl(List.of(), type -> Optional.empty())) {
            final var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> new KubernetesClientProducer()
                            .kubernetesClient(
                                    settings,
                                    configuration,
                                    HttpClient.newHttpClient(),
                                    mapper,
                                    new Yaml(),
                                    new Emitter() {
                                        @Override
                                        public <T> void emit(final T event) {
                                            // No request should be made for an invalid kubeconfig.
                                        }
                                    }));
            assertEquals("No kube context 'missing', ensure to configure kube client please", failure.getMessage());
        }
    }

    @Test
    void kubeconfigCredentialsTakePrecedenceOverExplicitToken() throws Exception {
        try (final var fixture = new Fixture(
                Map.of(
                        "kubeconfig",
                        kubeconfig("http://localhost:12345", Map.of("token", "selected"), Map.of()),
                        "bundlebee.kube.token",
                        "explicit"),
                event -> {})) {
            assertEquals(
                    "Bearer selected",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
        }
    }

    @Test
    void kubeconfigBasicAuthenticationIsSentPreemptively() throws Exception {
        try (final var fixture = new Fixture(
                Map.of(
                        "kubeconfig",
                        kubeconfig(
                                "http://localhost:12345", Map.of("username", "alice", "password", "secret"), Map.of())),
                event -> {})) {
            assertEquals(
                    "Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(UTF_8)),
                    fixture.execute(HttpRequest.newBuilder().GET(), fixture.base + "/api")
                            .toCompletableFuture()
                            .get()
                            .body());
        }
    }

    @Test
    void preservesKubeConfigurationPrefixWithFusionArguments() {
        final var arguments = new ArgsConfigSource(
                List.of("--bundlebee-kube-dryRun", "true", "--bundlebee-kube-api", "http://localhost:8080"));
        final Configuration configuration = key -> Optional.ofNullable(arguments.get(key));
        final var settings = settings(configuration).kube();
        assertTrue(settings.dryRun());
        assertEquals("http://localhost:8080", settings.api());
    }

    @TempDir
    private Path temp;

    @Test
    void explicitConnectionUsesFusionPreparationAndHonorsCallerAuthorization() throws Exception {
        try (final var fixture = new Fixture(
                Map.of("bundlebee.kube.token", "explicit-token", "bundlebee.kube.namespace", "chosen"), event -> {})) {
            assertInstanceOf(KubernetesClient.class, fixture.fusion);
            assertEquals("chosen", fixture.namespace());
            final var request = fixture.prepareRequest(HttpRequest.newBuilder().GET(), "/api/v1?limit=1");
            assertEquals(fixture.base + "/api/v1?limit=1", request.uri().toString());
            assertEquals(
                    "Bearer explicit-token",
                    request.headers().firstValue("Authorization").orElseThrow());
            assertEquals(
                    "Bearer caller",
                    fixture.prepareRequest(HttpRequest.newBuilder().header("Authorization", "Bearer caller"), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
            assertEquals(
                    "Bearer explicit-token",
                    fixture.execute(HttpRequest.newBuilder().GET(), "/api")
                            .toCompletableFuture()
                            .get()
                            .body());
            assertEquals(1, fixture.requests.get());
        }
    }

    @Test
    void deploymentUsesFusionTransportAndNamespace() throws Exception {
        try (final var fixture = new Fixture(
                Map.of("bundlebee.kube.token", "deployment-token", "bundlebee.kube.namespace", "target"),
                event -> {})) {
            final var methods = new ArrayList<String>();
            fixture.server.removeContext("/");
            fixture.server.createContext("/", exchange -> {
                methods.add(exchange.getRequestMethod());
                assertEquals(
                        "Bearer deployment-token", exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals(
                        "/api/v1/namespaces/target/configmaps"
                                + ("GET".equals(exchange.getRequestMethod()) ? "/test" : ""),
                        exchange.getRequestURI().getPath());
                final var body = "{}".getBytes(UTF_8);
                exchange.sendResponseHeaders("GET".equals(exchange.getRequestMethod()) ? 404 : 201, body.length);
                try (final var output = exchange.getResponseBody()) {
                    output.write(body);
                } finally {
                    exchange.close();
                }
            });
            final var client = new KubeClient(
                    new Yaml2JsonConverter(fixture.yamlContainer, fixture.mapper),
                    new ContainerSanitizer(),
                    new ApiPreloader(fixture.mapper, fixture.fusion),
                    fixture.container,
                    fixture.mapper,
                    null,
                    settings(Configuration.of(Map.of())).deployment(),
                    fixture.settings,
                    fixture.producer.namespaceProvider(fixture.container, fixture.settings),
                    fixture.producer);
            client.init();
            client.apply(
                            "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"test\"}}",
                            "json",
                            Map.of())
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertEquals(List.of("GET", "POST"), methods);
        }
    }

    @Test
    void inlineKubeconfigContextAndNamespaceAreResolvedByFusion() throws Exception {
        final var config = """
                apiVersion: v1
                current-context: first
                clusters:
                  - name: cluster
                    cluster:
                      server: http://localhost:12345
                contexts:
                  - name: first
                    context:
                      cluster: cluster
                      user: alice
                      namespace: original
                  - name: second
                    context:
                      cluster: cluster
                      user: bob
                      namespace: selected
                users:
                  - name: alice
                    user:
                      token: alice-token
                  - name: bob
                    user:
                      token: bob-token
                """;
        try (final var fixture =
                new Fixture(Map.of("kubeconfig", config, "bundlebee.kube.context", "second"), event -> {})) {
            assertEquals("selected", fixture.namespace());
            assertEquals("http://localhost:12345", fixture.getBaseApi());
            assertEquals(
                    "Bearer bob-token",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
            assertEquals(
                    "cluster",
                    fixture.getLoadedKubeConfig().getClusters().get(0).getName());
            assertEquals(
                    "http://localhost:12345",
                    fixture.getLoadedKubeConfig()
                            .getClusters()
                            .get(0)
                            .getCluster()
                            .getServer());
        }
    }

    @Test
    void yamlAnchorsAndRelativeTokenPathsArePreserved() throws Exception {
        final var token = Files.writeString(temp.resolve("token"), "file-token\n");
        final var content = """
                apiVersion: v1
                current-context: test
                clusters:
                  - name: cluster
                    cluster:
                      server: http://localhost:12345
                contexts:
                  - name: test
                    context:
                      cluster: cluster
                      user: test
                credentials: &credentials
                  tokenFile: token
                users:
                  - name: test
                    user: *credentials
                """;
        final var config = Files.writeString(temp.resolve("config.yaml"), content);
        try (final var fixture = new Fixture(Map.of("kubeconfig", config.toString()), event -> {})) {
            assertEquals("http://localhost:12345", fixture.getBaseApi());
            assertEquals(
                    "Bearer file-token",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
        }
        assertEquals(content, Files.readString(config));
        assertEquals("file-token\n", Files.readString(token));
    }

    @Test
    void kubeconfigFileAndTokenFileStayOwnedByCaller() throws Exception {
        final var token = temp.resolve("token");
        Files.writeString(token, "old\n");
        final var kubeconfig = temp.resolve("config.json");
        Files.writeString(kubeconfig, """
                {"current-context":"test","clusters":[{"name":"cluster","cluster":{"server":"http://localhost:12345"}}],
                 "contexts":[{"name":"test","context":{"cluster":"cluster","user":"test","namespace":"from-config"}}],
                 "users":[{"name":"test","user":{"tokenFile":"token"}}]}
                """);
        try (final var fixture = new Fixture(
                Map.of("kubeconfig", kubeconfig.toString(), "bundlebee.kube.namespace", "override"), event -> {})) {
            // Fusion loads the token on first use, not at producer construction.
            Files.writeString(token, "rotated\n");
            assertEquals(
                    "Bearer rotated",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
            assertEquals("override", fixture.namespace());
        }
        assertTrue(Files.exists(kubeconfig));
        assertEquals("rotated\n", Files.readString(token));
    }

    @Test
    void explicitServiceAccountSettingsArePassedToFusion() throws Exception {
        final var token = temp.resolve("service-account-token");
        Files.writeString(token, "pod-token\n");
        try (final var fixture = new Fixture(
                Map.of(
                        "bundlebee.kube.api",
                        "auto",
                        "kubernetes.service.host",
                        "localhost",
                        "kubernetes.service.port",
                        "8443",
                        "bundlebee.kube.tokenFile",
                        token.toString()),
                event -> {})) {
            assertEquals("https://localhost:8443", fixture.getBaseApi());
            assertEquals(
                    "Bearer pod-token",
                    fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                            .headers()
                            .firstValue("Authorization")
                            .orElseThrow());
        }
    }

    @Test
    void dryRunDoesNotSendOrEmitButCanPassThroughGets() throws Exception {
        final var events = new AtomicInteger();
        try (final var fixture = new Fixture(
                Map.of("bundlebee.kube.dryRun", "true", "bundlebee.kube.skipDryRunForGet", "true"),
                event -> events.incrementAndGet())) {
            final var response = fixture.execute(
                            HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString("{}")), "/api")
                    .toCompletableFuture()
                    .get();
            assertEquals(200, response.statusCode());
            assertEquals("true", response.headers().firstValue("X-Dry-Run").orElseThrow());
            assertEquals(0, fixture.requests.get());
            assertEquals(0, events.get());
            fixture.execute(HttpRequest.newBuilder().GET(), "/api")
                    .toCompletableFuture()
                    .get();
            assertEquals(1, fixture.requests.get());
            assertEquals(1, events.get());
        }
    }

    @Test
    void eventCanReplaceTheRequest() throws Exception {
        try (final var fixture = new Fixture(
                Map.of(),
                event -> event.setUserRequest(HttpRequest.newBuilder(event.getRequest(), (k, v) -> true)
                        .header("Authorization", "Bearer event")
                        .build()))) {
            assertEquals(
                    "Bearer event",
                    fixture.execute(HttpRequest.newBuilder().GET(), "/api")
                            .toCompletableFuture()
                            .get()
                            .body());
        }
    }

    @Test
    void applicationScopedProducerIsProxySafeAndCached() {
        try (final var mapper = new JsonMapperImpl(List.of(), type -> Optional.empty());
                final var container = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                ApplicationScoped.class,
                                ConfigurationSource.class,
                                () -> Map.of("kubeconfig", "explicit", "bundlebee.kube.tokenFile", "none")::get))
                        .start();
                final var first = container.lookup(KubernetesClient.class);
                final var second = container.lookup(KubernetesClient.class);
                final var namespace = container.lookup(NamespaceProvider.class)) {
            assertSame(first.instance(), second.instance());
            assertEquals("default", namespace.instance().namespace());
        }
    }

    @Test
    void execCredentialsAndTheirCacheAreHandledByFusion() throws Exception {
        final var calls = temp.resolve("exec-calls");
        final var script = temp.resolve("credentials.sh");
        Files.writeString(
                script,
                "echo called >> '" + calls
                        + "'\nprintf '%s' '{\"kind\":\"ExecCredential\",\"status\":{\"token\":\"exec-token\",\"expirationTimestamp\":\"2099-01-01T00:00:00Z\"}}'\n");
        final var user = Map.<String, Object>of(
                "exec",
                Map.of(
                        "apiVersion",
                        "client.authentication.k8s.io/v1",
                        "command",
                        "/bin/sh",
                        "args",
                        List.of(script.toString())));
        try (final var fixture =
                new Fixture(Map.of("kubeconfig", kubeconfig("http://localhost:12345", user, Map.of())), event -> {})) {
            for (var i = 0; i < 2; i++) {
                assertEquals(
                        "Bearer exec-token",
                        fixture.prepareRequest(HttpRequest.newBuilder(), "/api")
                                .headers()
                                .firstValue("Authorization")
                                .orElseThrow());
            }
            assertEquals(List.of("called"), Files.readAllLines(calls));
        }
    }

    @Test
    void kubeconfigBearerAuthenticationIsSentByFusion() throws Exception {
        try (final var fixture = new Fixture(
                Map.of(
                        "kubeconfig",
                        kubeconfig("http://localhost:12345", Map.of("token", "kubeconfig-token"), Map.of())),
                event -> {})) {
            assertEquals(
                    "Bearer kubeconfig-token",
                    fixture.execute(HttpRequest.newBuilder().GET(), fixture.base + "/api")
                            .toCompletableFuture()
                            .get()
                            .body());
        }
    }

    @Test
    void eventCanShortCircuitTheTransportAndRateLimitingRetainsIt() throws Exception {
        try (final var fixture = new Fixture(
                Map.of(
                        "bundlebee.kube.rateLimiter.enabled",
                        "true",
                        "bundlebee.kube.rateLimiter.permits",
                        "2147483647"),
                event -> {
                    event.setUserResponse(CompletableFuture.completedFuture(new HttpResponse<String>() {
                        @Override
                        public int statusCode() {
                            return 202;
                        }

                        @Override
                        public HttpRequest request() {
                            return event.getRequest();
                        }

                        @Override
                        public Optional<HttpResponse<String>> previousResponse() {
                            return Optional.empty();
                        }

                        @Override
                        public HttpHeaders headers() {
                            return HttpHeaders.of(Map.of(), (k, v) -> true);
                        }

                        @Override
                        public String body() {
                            return "intercepted";
                        }

                        @Override
                        public Optional<SSLSession> sslSession() {
                            return Optional.empty();
                        }

                        @Override
                        public URI uri() {
                            return event.getRequest().uri();
                        }

                        @Override
                        public HttpClient.Version version() {
                            return HttpClient.Version.HTTP_1_1;
                        }
                    }));
                })) {
            final var response = fixture.execute(HttpRequest.newBuilder().GET(), "/api")
                    .toCompletableFuture()
                    .get();
            assertEquals(202, response.statusCode());
            assertEquals("intercepted", response.body());
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test
    void tlsTrustClientCertificatesAndBooleanSkipTlsAreHandledByFusion() throws Exception {
        final var keystore = temp.resolve("tls.p12");
        final var keytool =
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        final var process = new ProcessBuilder(
                        keytool,
                        "-genkeypair",
                        "-alias",
                        "test",
                        "-keyalg",
                        "RSA",
                        "-keystore",
                        keystore.toString(),
                        "-storetype",
                        "PKCS12",
                        "-storepass",
                        "changeit",
                        "-keypass",
                        "changeit",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-validity",
                        "1",
                        "-noprompt")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        try (final var output = process.getInputStream()) {
            assertEquals(0, process.exitValue(), new String(output.readAllBytes(), UTF_8));
        }
        final var store = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(keystore)) {
            store.load(input, "changeit".toCharArray());
        }
        final var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        final var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        final var ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        final var server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override
            public void configure(final HttpsParameters parameters) {
                final var sslParameters = ssl.getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(true);
                parameters.setSSLParameters(sslParameters);
            }
        });
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            final var certificate = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(store.getCertificate("test").getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
            final var key = "-----" + "BEGIN" + " PRIVATE KEY" + "-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(store.getKey("test", "changeit".toCharArray())
                                    .getEncoded())
                    + "\n-----" + "END" + " PRIVATE KEY" + "-----\n";
            final var user = Map.<String, Object>of(
                    "client-certificate-data",
                    Base64.getEncoder().encodeToString(certificate.getBytes(UTF_8)),
                    "client-key-data",
                    Base64.getEncoder().encodeToString(key.getBytes(UTF_8)));
            final var base = "https://localhost:" + server.getAddress().getPort();
            for (final var cluster : List.of(
                    Map.<String, Object>of(
                            "certificate-authority-data",
                            Base64.getEncoder().encodeToString(certificate.getBytes(UTF_8))),
                    Map.<String, Object>of("insecure-skip-tls-verify", true))) {
                try (final var fixture =
                        new Fixture(Map.of("kubeconfig", kubeconfig(base, user, cluster)), event -> {})) {
                    assertEquals(
                            204,
                            fixture.execute(HttpRequest.newBuilder().GET(), "/api")
                                    .toCompletableFuture()
                                    .get(10, TimeUnit.SECONDS)
                                    .statusCode());
                }
            }
            try (final var fixture = new Fixture(
                    Map.of("kubeconfig", kubeconfig(base, user, Map.of()), "bundlebee.kube.validateSSL", "false"),
                    event -> {})) {
                assertEquals(
                        204,
                        fixture.execute(HttpRequest.newBuilder().GET(), "/api")
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS)
                                .statusCode());
            }
        } finally {
            server.stop(0);
        }
    }

    private String kubeconfig(
            final String base, final Map<String, Object> user, final Map<String, Object> clusterOptions) {
        final var cluster = new HashMap<String, Object>(clusterOptions);
        cluster.put("server", base);
        try (final var mapper = new JsonMapperImpl(List.of(), type -> Optional.empty())) {
            return mapper.toString(Map.of(
                    "current-context",
                    "test",
                    "clusters",
                    List.of(Map.of("name", "test", "cluster", cluster)),
                    "contexts",
                    List.of(Map.of("name", "test", "context", Map.of("cluster", "test", "user", "test"))),
                    "users",
                    List.of(Map.of("name", "test", "user", user))));
        }
    }

    private static GlobalConfiguration settings(final Configuration configuration) {
        try (final var container = ConfiguringContainer.of()
                        .register(
                                new ProvidedInstanceBean<>(
                                        ApplicationScoped.class, Configuration.class, () -> configuration) {
                                    @Override
                                    public int priority() {
                                        return 2000; // Typed-record fixtures must not inherit Surefire's mock-cluster
                                        // properties.
                                    }
                                })
                        .start();
                final var settings = container.lookup(GlobalConfiguration.class)) {
            return settings.instance();
        }
    }

    @Test
    void syncAsyncAndPushCallsShareTimeoutAuthenticationAndInterceptionPolicies() throws Exception {
        final var events = new AtomicInteger();
        try (final var fixture = new Fixture(
                Map.of("bundlebee.kube.token", "policy-token", "bundlebee.kube.http.timeout", "1234"), event -> {
                    events.incrementAndGet();
                    assertEquals(
                            "Bearer policy-token",
                            event.getRequest()
                                    .headers()
                                    .firstValue("Authorization")
                                    .orElseThrow());
                    assertEquals(
                            Duration.ofMillis(1234),
                            event.getRequest().timeout().orElseThrow());
                })) {
            final var request = HttpRequest.newBuilder(URI.create(fixture.base + "/api/a%2Fb?q=c%2Fd"))
                    .build();
            assertEquals(
                    "Bearer policy-token",
                    fixture.fusion
                            .send(request, HttpResponse.BodyHandlers.ofString())
                            .body());
            assertEquals(
                    "Bearer policy-token",
                    fixture.fusion
                            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .get()
                            .body());
            assertEquals(
                    "Bearer policy-token",
                    fixture.fusion
                            .sendAsync(request, HttpResponse.BodyHandlers.ofString(), (initial, push, accept) -> {})
                            .get()
                            .body());
            assertEquals(3, events.get());
            assertEquals(3, fixture.requests.get());
        }
    }

    @Test
    void curlMasksCredentialsAndPreservesRequestAndResponseBodiesForAllSendVariants() throws Exception {
        final var messages = new ArrayList<String>();
        final var logger = Logger.getLogger(KubernetesClientProducer.class.getName());
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try (final var fixture =
                new Fixture(Map.of("bundlebee.kube.dryRun", "true", "bundlebee.kube.token", "secret-bearer"), event -> {
                    throw new AssertionError("Dry run must precede interception");
                })) {
            final var request = HttpRequest.newBuilder(URI.create(fixture.base + "/api"))
                    .header("Proxy-Authorization", "Basic secret-proxy")
                    .header("Cookie", "secret-cookie")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":\"request-body\"}"))
                    .build();
            assertEquals(
                    "{}",
                    fixture.fusion
                            .send(request, HttpResponse.BodyHandlers.ofString())
                            .body());
            assertEquals(
                    "{}",
                    fixture.fusion
                            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .get()
                            .body());
            assertEquals(
                    "{}",
                    fixture.fusion
                            .sendAsync(request, HttpResponse.BodyHandlers.ofString(), (initial, push, accept) -> {})
                            .get()
                            .body());
            assertEquals(3, messages.size());
            for (final var message : messages) {
                assertTrue(message.contains("request-body"), message);
                assertTrue(message.contains("HTTP/1.1 200"), message);
                assertTrue(message.endsWith("{}"), message);
                assertTrue(message.contains("******"), message);
                assertFalse(message.contains("secret-bearer"), message);
                assertFalse(message.contains("secret-proxy"), message);
                assertFalse(message.contains("secret-cookie"), message);
            }
            assertEquals(0, fixture.requests.get());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void closingPolicyChainIsIdempotentAndLeavesBorrowedExecutorAndMapperOpen() throws Exception {
        final var executor = Executors.newSingleThreadExecutor();
        final var configuration = Configuration.of(Map.of(
                "kubeconfig",
                "explicit",
                "bundlebee.kube.dryRun",
                "true",
                "bundlebee.kube.rateLimiter.enabled",
                "true"));
        final var producer = new KubernetesClientProducer();
        try (final var mapper = new JsonMapperImpl(List.of(), type -> Optional.empty());
                final var client = producer.kubernetesClient(
                        settings(configuration).kube(),
                        configuration,
                        HttpClient.newBuilder().executor(executor).build(),
                        mapper,
                        new Yaml(),
                        new Emitter() {
                            @Override
                            public <T> void emit(final T event) {
                                throw new AssertionError("Dry run must not emit");
                            }
                        })) {
            assertEquals(
                    "{}",
                    client.sendAsync(
                                    HttpRequest.newBuilder(URI.create("http://localhost/api"))
                                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .get()
                            .body());
            producer.close();
            producer.close();
            assertFalse(executor.isShutdown());
            assertEquals("{}", mapper.toString(Map.of()));
        } finally {
            producer.close();
            executor.shutdownNow();
        }
    }

    private static class Fixture implements AutoCloseable {
        private final AtomicInteger requests = new AtomicInteger();
        private final HttpServer server;
        private final String base;
        private final JsonMapper mapper = new JsonMapperImpl(List.of(), type -> Optional.empty());
        private final KubernetesClient fusion;
        private final RuntimeContainer container;
        private final RuntimeContainer yamlContainer = ConfiguringContainer.of().start();
        private final KubernetesConfiguration settings;
        private final KubernetesClientProducer producer = new KubernetesClientProducer();

        private Fixture(final Map<String, String> overrides, final Consumer<OnKubeRequest> listener) throws Exception {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            base = "http://localhost:" + server.getAddress().getPort();
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                final var body = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))
                        .getBytes(UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (final var output = exchange.getResponseBody()) {
                    output.write(body);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            final var values = new HashMap<>(
                    Map.of("kubeconfig", "explicit", "bundlebee.kube.api", base, "bundlebee.kube.tokenFile", "none"));
            values.putAll(overrides);
            final var configuration = Configuration.of(values);
            settings = settings(configuration).kube();
            fusion = producer.kubernetesClient(
                    settings, configuration, HttpClient.newHttpClient(), mapper, new Yaml(), new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            listener.accept((OnKubeRequest) event);
                        }
                    });
            container = ConfiguringContainer.of()
                    .disableAutoDiscovery(true)
                    .register(new ProvidedInstanceBean<>(ApplicationScoped.class, KubernetesClient.class, () -> fusion))
                    .start();
        }

        private String getBaseApi() {
            return fusion.base().toString();
        }

        private String namespace() {
            return producer.namespaceProvider(container, settings).namespace();
        }

        private KubeConfig getLoadedKubeConfig() {
            return producer.loadedConfiguration();
        }

        private HttpRequest prepareRequest(final HttpRequest.Builder builder, final String path) {
            return fusion.prepare(builder.uri(URI.create(
                            path.startsWith("http:") || path.startsWith("https:")
                                    ? path
                                    : fusion.base().toString() + path))
                    .build());
        }

        private CompletableFuture<HttpResponse<String>> execute(final HttpRequest.Builder builder, final String path) {
            return fusion.sendAsync(
                    builder.uri(URI.create(
                                    path.startsWith("http:") || path.startsWith("https:")
                                            ? path
                                            : fusion.base().toString() + path))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(UTF_8));
        }

        @Override
        public void close() throws Exception {
            try {
                container.close();
                yamlContainer.close();
                producer.close();
            } finally {
                fusion.close();
                mapper.close();
                server.stop(0);
            }
        }
    }
}
