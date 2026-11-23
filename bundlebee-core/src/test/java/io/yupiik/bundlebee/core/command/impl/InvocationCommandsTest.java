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
package io.yupiik.bundlebee.core.command.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.lang.SubstitutorProducer;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvocationCommandsTest {
    @TempDir
    private Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"apply", "delete"})
    void concurrentInvocationsUseSharedServicesButSeparateOptionsAndIds(final String command) throws Exception {
        try (final var fixture = new Fixture(directory)) {
            fixture.overlap = new CountDownLatch(2);
            final var first = fixture.runAsync(command, "first", true);
            final var second = fixture.runAsync(command, "second", false);
            CompletableFuture.allOf(first, second).get(20, TimeUnit.SECONDS);
            for (final var id : List.of("first", "second")) {
                assertTrue(fixture.prepared.stream()
                        .anyMatch(event -> id.equals(event.getId()) && "child".equals(event.getAlveolus())));
                assertTrue(fixture.prepared.stream()
                        .anyMatch(event -> id.equals(event.getId()) && id.equals(event.getAlveolus())));
                assertTrue(fixture.placeholders.stream()
                        .anyMatch(event -> id.equals(event.getId())
                                && "root".equals(event.getName())
                                && id.equals(event.getResolvedValue())));
                assertTrue(fixture.placeholders.stream()
                        .anyMatch(event -> id.equals(event.getId())
                                && "message".equals(event.getName())
                                && id.equals(event.getResolvedValue())));
                assertTrue(fixture.requests.stream()
                        .anyMatch(request -> request.path().endsWith('/' + id + "-child")
                                || request.body().contains(id + "-child")));
                assertTrue(fixture.requests.stream()
                        .anyMatch(request -> request.path().endsWith('/' + id + "-main")
                                || request.body().contains(id + "-main")));
                fixture.assertReleased(id);
            }
            assertTrue(fixture.prepared.stream()
                    .allMatch(event -> List.of("first", "second").contains(event.getId())));
            if ("apply".equals(command)) {
                assertFalse(fixture.requests.stream()
                        .anyMatch(request ->
                                "GET".equals(request.method()) && request.path().endsWith("/first-main")));
                assertTrue(fixture.requests.stream()
                        .anyMatch(request ->
                                "GET".equals(request.method()) && request.path().endsWith("/second-main")));
                assertTrue(fixture.requests.stream()
                        .anyMatch(request -> request.body().contains("first-main")
                                && request.body().contains("bundlebee.root.alveolus.name")));
                assertFalse(fixture.requests.stream()
                        .anyMatch(request -> request.body().contains("second-main")
                                && request.body().contains("bundlebee.root.alveolus.name")));
            } else {
                assertTrue(fixture.requests.stream()
                        .filter(request -> "DELETE".equals(request.method())
                                && request.path().contains("/first-"))
                        .allMatch(request -> request.query().contains("gracePeriodSeconds=1")));
                assertTrue(fixture.requests.stream()
                        .filter(request -> "DELETE".equals(request.method())
                                && request.path().contains("/second-"))
                        .allMatch(request -> request.query().contains("gracePeriodSeconds=7")));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "delete"})
    void failedInvocationReleasesBothContextsAndCanReuseTheId(final String command) throws Exception {
        try (final var fixture = new Fixture(directory)) {
            fixture.fail = true;
            fixture.failPreparation = "delete".equals(command);
            assertThrows(
                    Exception.class,
                    () -> fixture.runAsync(command, "retry", false).get(20, TimeUnit.SECONDS));
            fixture.assertReleased("retry");
            fixture.fail = false;
            fixture.runAsync(command, "retry", false).get(20, TimeUnit.SECONDS);
            fixture.assertReleased("retry");
            assertTrue(
                    fixture.requests.stream().anyMatch(request -> request.path().endsWith("/retry-main")));
        }
    }

    @Test
    void deletesStateInTheConfiguredNamespace() throws Exception {
        try (final var fixture = new Fixture(directory)) {
            fixture.stateExists = true;
            fixture.runAsync("delete", "state", false).get(20, TimeUnit.SECONDS);
            assertTrue(fixture.requests.stream()
                    .anyMatch(request -> "DELETE".equals(request.method())
                            && request.path().equals("/prefix/api/v1/namespaces/team/secrets/state-bbs")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "delete"})
    void retainsTheExecutionIdAcrossMultipleRootAlveoli(final String command) throws Exception {
        try (final var fixture = new Fixture(directory)) {
            fixture.scanClasspath = true;
            fixture.runAsync(command, "roots", false).get(20, TimeUnit.SECONDS);
            assertEquals(3, fixture.prepared.size()); // child dependency, its owner, and child as another root
            assertTrue(fixture.prepared.stream().allMatch(event -> "roots".equals(event.getId())));
            assertTrue(fixture.placeholders.stream()
                    .filter(event -> "message".equals(event.getName()))
                    .allMatch(event -> "roots".equals(event.getId()) && "roots".equals(event.getResolvedValue())));
            fixture.assertReleased("roots");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final RuntimeContainer container;
        private final List<OnPlaceholder> placeholders = new CopyOnWriteArrayList<>();
        private final List<OnPrepareDescriptor> prepared = new CopyOnWriteArrayList<>();
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final Path manifest;
        private volatile CountDownLatch overlap;
        private volatile boolean fail;
        private volatile boolean failPreparation;
        private volatile boolean stateExists;
        private boolean scanClasspath;

        private Fixture(final Path directory) throws Exception {
            final var descriptors = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
            manifest = directory.resolve("bundlebee/manifest.json");
            Files.writeString(manifest, """
                    {"interpolateAlveoli":true,"alveoli":[
                      {"name":"{{root}}","dependencies":[{"name":"child"}],"descriptors":[{"name":"main.json","location":"%s"}]},
                      {"name":"child","descriptors":[{"name":"child.json","location":"%s"}]}]}
                    """.formatted(directory, directory));
            for (final var name : List.of("main", "child")) {
                Files.writeString(descriptors.resolve(name + ".json"), """
                        {"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"{{message}}-%s"},"data":{"value":"{{message}}"}}
                        """.formatted(name));
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try (exchange) {
                    final var path = exchange.getRequestURI().getPath();
                    final var method = exchange.getRequestMethod();
                    final var body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                    requests.add(
                            new Request(method, path, exchange.getRequestURI().getRawQuery(), body));
                    final var latch = overlap;
                    if (latch != null
                            && (path.endsWith("-child") || body.contains("-child"))
                            && !"GET".equals(method)) {
                        latch.countDown();
                        try {
                            if (!latch.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Invocations did not overlap");
                            }
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                    final var status = fail
                            ? 500
                            : "GET".equals(method) && !(stateExists && path.endsWith("-bbs"))
                                    ? 404
                                    : "POST".equals(method) ? 201 : 200;
                    final var response = "{}".getBytes(UTF_8);
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
            final var settings = Map.of(
                    "kubeconfig",
                    "explicit",
                    "bundlebee.kube.api",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/prefix",
                    "bundlebee.kube.namespace",
                    "team",
                    "bundlebee.kube.http.timeout",
                    "5000");
            container = ConfiguringContainer.of()
                    .register(new ProvidedInstanceBean<>(
                            ApplicationScoped.class, ConfigurationSource.class, () -> settings::get))
                    .register(
                            listener(OnPlaceholder.class, placeholders::add),
                            listener(OnPrepareDescriptor.class, event -> {
                                prepared.add(event);
                                if (fail && failPreparation) {
                                    throw new IllegalStateException("Descriptor observer failure");
                                }
                            }))
                    .start();
        }

        private CompletableFuture<Void> runAsync(final String command, final String id, final boolean firstOptions) {
            return CompletableFuture.runAsync(
                    () -> {
                        final var thread = Thread.currentThread();
                        final var originalLoader = thread.getContextClassLoader();
                        try (final var loader =
                                        new URLClassLoader(
                                                new URL[] {
                                                    manifest.getParent()
                                                            .getParent()
                                                            .toUri()
                                                            .toURL()
                                                },
                                                originalLoader) {
                                            @Override
                                            public Enumeration<URL> getResources(final String name)
                                                    throws java.io.IOException {
                                                return "bundlebee/manifest.json".equals(name)
                                                        ? Collections.enumeration(List.of(
                                                                manifest.toUri().toURL()))
                                                        : super.getResources(name);
                                            }
                                        };
                                final var producer = container.lookup(SubstitutorProducer.class);
                                final var metadata = container.lookup(ParameterExtractor.class)) {
                            if (scanClasspath) {
                                thread.setContextClassLoader(loader);
                            }
                            final var substitution = producer.instance();
                            final var values = new HashMap<>(Map.of("root", id, "message", id));
                            substitution.setInvocationPlaceholders(id, values);
                            values.put("message", "must-not-leak");
                            substitution.getByIdContextualPlaceholders().put(id, Map.of("context", "must-be-released"));
                            try {
                                final var definition = metadata.instance().commands().stream()
                                        .filter(it -> command.equals(it.name()))
                                        .findFirst()
                                        .orElseThrow();
                                final var options = new HashMap<String, String>();
                                final var prefix = "bundlebee." + command + '.';
                                options.put(
                                        prefix + "from",
                                        manifest.getParent().getParent().toString());
                                options.put(prefix + "manifest", scanClasspath ? "skip" : manifest.toString());
                                options.put(prefix + "alveolus", scanClasspath ? "auto" : id);
                                options.put(prefix + "skipGet", Boolean.toString(firstOptions));
                                options.put(prefix + "injectTimestamp", "false");
                                options.put(prefix + "injectBundleBeeMetadata", Boolean.toString(firstOptions));
                                options.put(prefix + "gracePeriodSeconds", firstOptions ? "1" : "7");
                                options.put("bundlebee.apply.trackState", Boolean.toString(stateExists));
                                final var mapped = new HashMap<String, String>();
                                options.forEach((key, value) ->
                                        mapped.put(definition.keyMapper().apply(key), value));
                                mapped.putAll(options);
                                final var dependents = new ArrayList<Instance<?>>();
                                try (final var invocation = definition.create(Configuration.of(mapped), dependents)) {
                                    final CompletionStage<?> stage;
                                    if (invocation.instance() instanceof ApplyCommand apply) {
                                        stage = apply.execute(id);
                                    } else if (invocation.instance() instanceof DeleteCommand delete) {
                                        stage = delete.execute(id);
                                    } else {
                                        throw new IllegalStateException(command);
                                    }
                                    stage.toCompletableFuture().join();
                                }
                            } finally {
                                // Same boundary as the operator: clear only after all async work has finished.
                                substitution.clearInvocationPlaceholders(id);
                            }
                        } catch (final java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        } finally {
                            thread.setContextClassLoader(originalLoader);
                        }
                    },
                    workers);
        }

        private void assertReleased(final String id) {
            try (final var substitute = container.lookup(Substitutor.class)) {
                assertEquals(
                        "default/default",
                        substitute.instance().replace("{{message:-default}}/{{context:-default}}", id));
            }
        }

        private static <T> FusionListener<T> listener(final Class<T> type, final Consumer<T> consumer) {
            return new FusionListener<>() {
                @Override
                public Type eventType() {
                    return type;
                }

                @Override
                public void onEvent(final RuntimeContainer container, final T event) {
                    consumer.accept(event);
                }
            };
        }

        @Override
        public void close() {
            container.close();
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private record Request(String method, String path, String query, String body) {}
}
