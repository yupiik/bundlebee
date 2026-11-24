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
package io.yupiik.bundlebee.operator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.bundlebee.operator.handler.ActionHandler;
import io.yupiik.bundlebee.operator.handler.CommandRunner;
import io.yupiik.bundlebee.operator.model.AlveoliResource;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorCommandBoundaryTest {
    @TempDir
    private Path directory;

    @Test
    void rejectsGlobalAliasesBeforeLaunchingIncludingExecKubeconfig() throws Exception {
        runIsolated("rejectsGlobalAliasesBeforeLaunchingIncludingExecKubeconfig");
    }

    @Test
    void acceptsCommandAliasesAndPlaceholdersWithoutChangingStartupDefaults() throws Exception {
        runIsolated("acceptsCommandAliasesAndPlaceholdersWithoutChangingStartupDefaults");
    }

    @Test
    void placeholderOnlyEmbeddingCannotOverrideMatchingStartupKeys() throws Exception {
        runIsolated("placeholderOnlyEmbeddingCannotOverrideMatchingStartupKeys");
    }

    @Test
    void arbitraryPlaceholderNamesAndExactKeysRemainSupported() throws Exception {
        runIsolated("arbitraryPlaceholderNamesAndExactKeysRemainSupported");
    }

    @Test
    void realConcurrentInvocationsKeepEventOptionsAndPlaceholdersIsolated() throws Exception {
        runIsolated("realConcurrentInvocationsKeepEventOptionsAndPlaceholdersIsolated");
    }

    private void runIsolated(final String scenario) throws Exception {
        // Surefire's global Kubernetes properties outrank the operator's startup embedding settings.
        // Do not mutate them in the shared JVM: other tests can be running concurrently.
        final var output = directory.resolve("scenario.log");
        final var builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Duser.home=" + directory,
                        "-Djava.util.logging.manager=io.yupiik.logging.jul.YupiikLogManager",
                        "-D.handlers=io.yupiik.logging.jul.handler.StandardHandler",
                        "-cp",
                        System.getProperty("java.class.path"),
                        OperatorCommandBoundaryTest.class.getName(),
                        scenario,
                        directory.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().keySet().removeIf(key -> {
            final var normalized = key.toUpperCase(Locale.ROOT);
            return normalized.startsWith("BUNDLEBEE")
                    || normalized.startsWith("FUSION")
                    || normalized.equals("KUBECONFIG")
                    || normalized.equals("ARGOCD_APP_PARAMETERS")
                    || normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS");
        });
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), () -> "Scenario timed out: " + scenario);
            assertEquals(0, process.exitValue(), scenario + ":\n" + Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final var test = new OperatorCommandBoundaryTest();
        test.directory = Path.of(args[1]);
        switch (args[0]) {
            case "rejectsGlobalAliasesBeforeLaunchingIncludingExecKubeconfig" -> test.rejectsGlobalAliasesScenario();
            case "acceptsCommandAliasesAndPlaceholdersWithoutChangingStartupDefaults" ->
                test.acceptsCommandAliasesScenario();
            case "placeholderOnlyEmbeddingCannotOverrideMatchingStartupKeys" -> test.placeholderOnlyEmbeddingScenario();
            case "arbitraryPlaceholderNamesAndExactKeysRemainSupported" -> test.arbitraryPlaceholderNamesScenario();
            case "realConcurrentInvocationsKeepEventOptionsAndPlaceholdersIsolated" ->
                test.realConcurrentInvocationsScenario();
            default -> throw new IllegalArgumentException("Unknown scenario: " + args[0]);
        }
    }

    private void rejectsGlobalAliasesScenario() throws Exception {
        try (final var fixture = new Fixture(directory)) {
            final var marker = directory.resolve("exec-marker");
            final var kubeconfig = """
                    apiVersion: v1
                    kind: Config
                    current-context: test
                    clusters:
                    - name: test
                      cluster:
                        server: %s
                    contexts:
                    - name: test
                      context:
                        cluster: test
                        user: test
                    users:
                    - name: test
                      user:
                        exec:
                          apiVersion: client.authentication.k8s.io/v1beta1
                          command: /bin/sh
                          args:
                          - -c
                          - >-
                            echo invoked > '%s'; printf '%%s' '{"apiVersion":"client.authentication.k8s.io/v1beta1","kind":"ExecCredential","status":{"token":"injected"}}'
                    """.formatted(fixture.endpoint, marker);
            final var properties = directory.resolve("injected.properties");
            Files.writeString(properties, "bundlebee.kube.kubeconfig=" + kubeconfig.replace("\n", "\\n") + "\n");
            final var attempts = List.of(
                    List.of("--kubeconfig", kubeconfig),
                    List.of("--bundlebee.kube.kubeconfig", kubeconfig),
                    List.of("--bundlebee-kube-kubeconfig", kubeconfig),
                    List.of("--bundlebee.kube.api", "http://untrusted.invalid"),
                    List.of("--bundlebee-kube-api", "http://untrusted.invalid"),
                    List.of("--bundlebee.kube.token", "injected"),
                    List.of("--bundlebee-kube-tokenFile", properties.toString()),
                    List.of("--bundlebee-kube-certificates", properties.toString()),
                    List.of("--bundlebee.maven.cache", directory.toString()),
                    List.of("--config-file", properties.toString()),
                    List.of("--config-file=" + properties, "unused"),
                    List.of("--config.file", properties.toString()),
                    List.of("--fusion-properties", properties.toString()),
                    List.of("--fusion.properties.inline", "bundlebee.kube.token=injected"),
                    List.of("--user.home", directory.toString()),
                    List.of("--ARGOCD_APP_PARAMETERS", "[]"),
                    List.of("--bundlebee.apply.notAnOption", "value"),
                    List.of("delete", "unused"),
                    List.of("--", "delete"),
                    List.of("-bundlebee-kube-api", "http://untrusted.invalid"),
                    List.of("--no-bundlebee-kube-validateSSL", "unused"),
                    List.of("--message", "--config-file"),
                    List.of("--message", "--config-file=" + properties));
            for (final var type : List.of("ADDED", "MODIFIED", "DELETED")) {
                for (final var attempt : attempts) {
                    final var args = new ArrayList<>(List.of("--alveolus", "event"));
                    args.addAll(attempt);
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> fixture.handler.onEvent(type, object(args)),
                            type + ": " + attempt.get(0));
                    assertFalse(Files.exists(marker), "Untrusted kubeconfig exec must not run");
                    assertTrue(fixture.requests.isEmpty(), "Rejected event must not launch Kubernetes operations");
                }
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.handler.onEvent("ADDED", object(List.of("--alveolus=event"))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.handler.onEvent("ADDED", object(List.of("--alveolus"))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.handler.onEvent("DELETED", object(List.of("--skipGet", "true"))));
            // A rejected event cannot poison a subsequent valid event.
            fixture.handler.onEvent("ADDED", object(List.of()));
            fixture.assertWrites("startup:startup");
            assertFalse(Files.exists(marker));
        }
    }

    private void acceptsCommandAliasesScenario() throws Exception {
        try (final var fixture = new Fixture(directory)) {
            for (final var alias : List.of("--alveolus", "--bundlebee.apply.alveolus", "--bundlebee-apply-alveolus")) {
                fixture.handler.onEvent(
                        "ADDED",
                        object(List.of(
                                alias,
                                "event",
                                "--message",
                                "event",
                                "--from",
                                directory.toString(),
                                "--manifest",
                                directory.resolve("bundlebee/manifest.json").toString())));
            }
            for (final var alias :
                    List.of("--alveolus", "--bundlebee.delete.alveolus", "--bundlebee-delete-alveolus")) {
                fixture.handler.onEvent(
                        "DELETED",
                        object(List.of(
                                alias,
                                "event",
                                "--message",
                                "delete",
                                "--bundlebee.delete.from",
                                directory.toString(),
                                "--bundlebee-delete-manifest",
                                directory.resolve("bundlebee/manifest.json").toString())));
            }
            fixture.handler.onEvent(
                    "MODIFIED",
                    object(List.of(
                            "--alveolus",
                            "event",
                            "--alveolus",
                            "startup",
                            "--message",
                            "--literal-option-looking-value")));
            fixture.handler.onEvent("ADDED", object(List.of()));
            fixture.assertWrites(
                    "event:event",
                    "event:event",
                    "event:event",
                    "startup:--literal-option-looking-value",
                    "startup:startup");
            assertEquals(
                    3,
                    fixture.requests.stream()
                            .filter(request -> "DELETE".equals(request.method()))
                            .count());
            assertTrue(fixture.requests.stream()
                    .filter(request -> "DELETE".equals(request.method()))
                    .allMatch(request -> request.path().endsWith("/event")));
            fixture.assertStartupConnection();
        }
    }

    private void placeholderOnlyEmbeddingScenario() throws Exception {
        try (final var fixture = new Fixture(directory)) {
            final var marker = directory.resolve("placeholder-exec-marker");
            final var injected = """
                    apiVersion: v1
                    kind: Config
                    current-context: test
                    clusters:
                    - name: test
                      cluster:
                        server: %s
                    contexts:
                    - name: test
                      context:
                        cluster: test
                        user: test
                    users:
                    - name: test
                      user:
                        exec:
                          apiVersion: client.authentication.k8s.io/v1beta1
                          command: /bin/sh
                          args: ['-c', 'touch %s']
                    """.formatted(fixture.endpoint, marker);
            final var descriptor = directory.resolve("bundlebee/kubernetes/event.yaml");
            Files.writeString(
                    descriptor,
                    Files.readString(descriptor)
                            .replace(
                                    "{{message}}",
                                    "{{bundlebee.kube.token}}/{{config-file}}/{{fusion.properties.inline}}"));
            fixture.applyWithPlaceholders(Map.of(
                    "kubeconfig",
                    injected,
                    "bundlebee.kube.kubeconfig",
                    injected,
                    "bundlebee.kube.api",
                    "http://untrusted.invalid",
                    "bundlebee.kube.token",
                    "placeholder-token",
                    "config-file",
                    "not-a-file",
                    "fusion.properties.inline",
                    "not-configuration",
                    "ARGOCD_APP_PARAMETERS",
                    "invalid-json"));
            fixture.assertWrites("event:placeholder-token/not-a-file/not-configuration");
            fixture.assertStartupConnection();
            assertFalse(Files.exists(marker), "Placeholder kubeconfig exec must not run");
        }
    }

    private void arbitraryPlaceholderNamesScenario() throws Exception {
        try (final var fixture = new Fixture(directory)) {
            final var descriptor = directory.resolve("bundlebee/kubernetes/event.yaml");
            Files.writeString(
                    descriptor,
                    Files.readString(descriptor)
                            .replace("{{message}}", "{{unknownPlaceholder}}/{{recipe.message}}/{{recipe-message}}"));
            fixture.handler.onEvent(
                    "ADDED",
                    object(List.of(
                            "--alveolus",
                            "event",
                            "--unknownPlaceholder",
                            "hello",
                            "--recipe.message",
                            "dotted",
                            "--recipe-message",
                            "dashed")));
            fixture.assertWrites("event:hello/dotted/dashed");
            fixture.assertStartupConnection();
        }
    }

    private void realConcurrentInvocationsScenario() throws Exception {
        try (final var fixture = new Fixture(directory)) {
            final var pool = Executors.newFixedThreadPool(2);
            try {
                final var first = pool.submit(() ->
                        fixture.handler.onEvent("ADDED", object(List.of("--alveolus", "event", "--message", "first"))));
                final var second = pool.submit(() -> fixture.handler.onEvent(
                        "MODIFIED", object(List.of("--alveolus", "startup", "--message", "second"))));
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            final var writes = fixture.writes();
            assertEquals(2, writes.size());
            assertTrue(writes.contains("event:first"), writes.toString());
            assertTrue(writes.contains("startup:second"), writes.toString());
            fixture.handler.onEvent("ADDED", object(List.of()));
            final var subsequentWrites = fixture.writes();
            assertEquals("startup:startup", subsequentWrites.get(subsequentWrites.size() - 1));
            fixture.assertStartupConnection();
        }
    }

    private static AlveoliResource object(final List<String> args) {
        return new AlveoliResource(
                new AlveoliResource.Metadata("boundary-uid", "boundary-test", "startup-namespace", null),
                new AlveoliResource.Spec(args));
    }

    private record Request(String method, String path, String authorization, String body) {}

    private static final class Fixture implements AutoCloseable {
        private final io.yupiik.fusion.framework.api.RuntimeContainer container;
        private final HttpServer server;
        private final String endpoint;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final ActionHandler handler;

        private Fixture(final Path root) throws Exception {
            final var bundlebee = Files.createDirectories(root.resolve("bundlebee"));
            final var descriptors = Files.createDirectories(bundlebee.resolve("kubernetes"));
            final var manifest = bundlebee.resolve("manifest.json");
            Files.writeString(manifest, """
                    {"alveoli":[
                      {"name":"startup","descriptors":[{"name":"startup","interpolate":true}]},
                      {"name":"event","descriptors":[{"name":"event","interpolate":true}]}
                    ]}
                    """);
            for (final var name : List.of("startup", "event")) {
                Files.writeString(descriptors.resolve(name + ".yaml"), """
                        apiVersion: v1
                        kind: ConfigMap
                        metadata:
                          name: %s
                        data:
                          message: '{{message}}'
                        """.formatted(name));
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    requests.add(new Request(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
                    final var response = "{}".getBytes(UTF_8);
                    exchange.sendResponseHeaders(
                            "GET".equals(exchange.getRequestMethod())
                                    ? 404
                                    : "POST".equals(exchange.getRequestMethod()) ? 201 : 200,
                            response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
            endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            final var startup = new ArgsConfigSource(List.of(
                    "--kubeconfig",
                    "explicit",
                    "--bundlebee.kube.kubeconfig",
                    "explicit",
                    "--bundlebee.kube.api",
                    endpoint,
                    "--bundlebee.kube.token",
                    "startup-token",
                    "--bundlebee.kube.namespace",
                    "startup-namespace",
                    "--bundlebee.apply.from",
                    root.toString(),
                    "--bundlebee.delete.from",
                    root.toString(),
                    "--bundlebee.apply.manifest",
                    manifest.toString(),
                    "--bundlebee.delete.manifest",
                    manifest.toString(),
                    "--bundlebee.apply.alveolus",
                    "startup",
                    "--bundlebee.delete.alveolus",
                    "startup",
                    "--bundlebee.apply.injectTimestamp",
                    "false",
                    "--bundlebee.apply.injectBundleBeeMetadata",
                    "false",
                    "--message",
                    "startup"));
            container = io.yupiik.fusion.framework.api.ConfiguringContainer.of()
                    .disableModule(io.yupiik.kubernetes.operator.base.impl.FusionGeneratedModule.class)
                    .register(new io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean<>(
                            io.yupiik.fusion.framework.api.scope.ApplicationScoped.class,
                            io.yupiik.kubernetes.operator.base.impl.Threads.class,
                            () -> new io.yupiik.kubernetes.operator.base.impl.Threads(
                                    Executors.newScheduledThreadPool(2))))
                    .register(new io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean<>(
                            io.yupiik.fusion.framework.api.scope.DefaultScoped.class,
                            io.yupiik.fusion.framework.api.configuration.ConfigurationSource.class,
                            () -> startup))
                    .start();
            final var metadata = container.lookup(io.yupiik.bundlebee.core.service.ParameterExtractor.class);
            final var substitution = container.lookup(io.yupiik.bundlebee.core.lang.SubstitutorProducer.class);
            final var configuration =
                    container.lookup(io.yupiik.fusion.framework.api.configuration.Configuration.class);
            handler = new ActionHandler(
                    new CommandRunner(metadata.instance(), substitution.instance(), configuration.instance()));
        }

        private void applyWithPlaceholders(final Map<String, String> placeholders) {
            try (final var metadata = container.lookup(io.yupiik.bundlebee.core.service.ParameterExtractor.class);
                    final var producer = container.lookup(io.yupiik.bundlebee.core.lang.SubstitutorProducer.class);
                    final var configuration =
                            container.lookup(io.yupiik.fusion.framework.api.configuration.Configuration.class)) {
                final var command = metadata.instance().commands().stream()
                        .filter(it -> "apply".equals(it.name()))
                        .findFirst()
                        .orElseThrow();
                final var id = "placeholder-only";
                producer.instance().setInvocationPlaceholders(id, placeholders);
                try (final var invocation = command.create(
                        key -> key.equals("bundlebee.apply.alveolus")
                                        || key.equals(command.keyMapper().apply("bundlebee.apply.alveolus"))
                                ? java.util.Optional.of("event")
                                : configuration.instance().get(key),
                        new ArrayList<>())) {
                    ((io.yupiik.bundlebee.core.command.impl.ApplyCommand) invocation.instance())
                            .execute(id)
                            .toCompletableFuture()
                            .join();
                } finally {
                    producer.instance().clearInvocationPlaceholders(id);
                }
            }
        }

        private List<String> writes() {
            return requests.stream()
                    .filter(request -> "POST".equals(request.method()))
                    .map(request -> {
                        final var name = request.body().contains("\"name\":\"event\"") ? "event" : "startup";
                        final var message = request.body()
                                .substring(request.body().indexOf("\"message\":\"") + "\"message\":\"".length());
                        return name + ':' + message.substring(0, message.indexOf('"'));
                    })
                    .toList();
        }

        private void assertWrites(final String... expected) {
            assertEquals(List.of(expected), writes(), requests.toString());
        }

        private void assertStartupConnection() {
            assertFalse(requests.isEmpty());
            assertTrue(
                    requests.stream().allMatch(request -> "Bearer startup-token".equals(request.authorization())),
                    requests.toString());
            assertTrue(
                    requests.stream()
                            .allMatch(request -> "/api/v1".equals(request.path())
                                    || request.path().startsWith("/api/v1/namespaces/startup-namespace/configmaps")),
                    requests.toString());
        }

        @Override
        public void close() {
            try {
                container.close();
            } finally {
                server.stop(0);
            }
        }
    }
}
