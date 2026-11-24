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
package io.yupiik.bundlebee.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.configuration.GlobalConfiguration;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleBeeBootstrapTest {
    @TempDir
    Path directory;

    @Test
    void noArgumentsRunsGeneratedHelpOffline() {
        final var logger = Logger.getLogger("io.yupiik.bundlebee.core.command.impl.HelpCommand");
        final var output = new StringBuilder();
        final var level = logger.getLevel();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                output.append(record.getMessage());
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        try {
            new BundleBee().launch(new String[0], Map.of("kubeconfig", "/must-not-be-read-for-help"));
            assertTrue(output.toString().contains("BundleBee"), output.toString());
            assertTrue(output.toString().contains("  [] apply:"), output.toString());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }

    @Test
    void filesArgoEmbeddingAndCliUseNativeSourcePrecedence() throws IOException {
        final var file = directory.resolve("invocation.properties");
        Files.writeString(file, "bootstrap.value=file\n");
        assertEquals(
                "file",
                invoke(List.of("--config-file", file.toString()), Map.of()).value());
        final var argo = Map.of("ARGOCD_APP_PARAMETERS", "[{\"name\":\"bootstrap.value\",\"string\":\"argo\"}]");
        assertEquals(
                "argo", invoke(List.of("--config-file", file.toString()), argo).value());
        assertEquals(
                "cli",
                invoke(
                                List.of("--config-file", file.toString(), "--bootstrap-value", "cli"),
                                Map.of(
                                        "ARGOCD_APP_PARAMETERS",
                                        argo.get("ARGOCD_APP_PARAMETERS"),
                                        "bootstrap.value",
                                        "embedding"))
                        .value());
        assertEquals("default", invoke(List.of(), Map.of()).value());
        assertNull(System.getProperty("bootstrap.value"));
    }

    @Test
    void configFileCanPrecedeTheCommand() throws IOException {
        final var file = directory.resolve("leading.properties");
        Files.writeString(file, "bootstrap.value=leading\n");
        for (final var prefix : List.of(List.of("--config-file", file.toString()), List.of("--config-file=" + file))) {
            final var id = UUID.randomUUID().toString();
            final var args = Stream.concat(prefix.stream(), Stream.of("bootstrap-probe", "--bootstrap-id", id))
                    .toArray(String[]::new);
            new BundleBee().launch(args);
            final var observation = Probe.OBSERVATIONS.remove(id);
            assertNotNull(observation);
            assertEquals("leading", observation.value());
        }
        assertDoesNotThrow(() -> new BundleBee().launch("--config-file", file.toString()));
    }

    @Test
    void cliOverridesArgoWithDottedAndDashedOptions() {
        final var argo = Map.of("ARGOCD_APP_PARAMETERS", "[{\"name\":\"bootstrap.value\",\"string\":\"argo\"}]");
        assertEquals("cli", invoke(List.of("--bootstrap-value", "cli"), argo).value());
        assertEquals("cli", invoke(List.of("--bootstrap.value", "cli"), argo).value());
        assertEquals(
                "embedding",
                invoke(
                                List.of(),
                                Map.of(
                                        "ARGOCD_APP_PARAMETERS",
                                        argo.get("ARGOCD_APP_PARAMETERS"),
                                        "bootstrap.value",
                                        "embedding"))
                        .value());
    }

    @Test
    void repeatedArgoParametersKeepTheLastDottedOrDashedValue() {
        final var argo = Map.of(
                "ARGOCD_APP_PARAMETERS",
                "[{\"name\":\"bootstrap.value\",\"string\":\"first\"},"
                        + "{\"name\":\"bootstrap.value\",\"string\":\"second\"},"
                        + "{\"name\":\"bootstrap.value\",\"string\":\"last\"}]");
        assertEquals("last", invoke(List.of(), argo).value());
        assertEquals(
                "last",
                invoke(
                                List.of(),
                                Map.of(
                                        "ARGOCD_APP_PARAMETERS",
                                        argo.get("ARGOCD_APP_PARAMETERS")
                                                .replace("bootstrap.value", "bootstrap-value")))
                        .value());
    }

    @Test
    void repeatedOptionsKeepTheLastValueIncludingCommas() {
        assertEquals(
                "last,with,commas",
                invoke(List.of("--bootstrap-value", "first", "--bootstrap-value", "last,with,commas"), Map.of())
                        .value());
        assertEquals(
                List.of("two", "three"),
                invoke(List.of("--bootstrap-values", "one", "--bootstrap-values", "two,three"), Map.of())
                        .values());
        assertTrue(invoke(List.of("--bootstrap-enabled", "false", "--bootstrap-enabled", "true"), Map.of())
                .enabled());
    }

    @Test
    void legacyPairsPreserveOptionLookingValuesAndRejectFlags() {
        assertEquals(
                "--literal",
                invoke(List.of("--bootstrap-value", "--literal"), Map.of()).value());
        assertThrows(IllegalArgumentException.class, () -> invoke(List.of("--bootstrap-enabled"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> invoke(List.of("--no-bootstrap-enabled"), Map.of()));
        assertThrows(
                IllegalArgumentException.class, () -> invoke(List.of("--bootstrap-value=not-a-legacy-pair"), Map.of()));
    }

    @Test
    void missingConfigFileAndMissingOptionValueFailWithoutLeaking() {
        assertThrows(
                IllegalStateException.class,
                () -> invoke(
                        List.of("--config-file", directory.resolve("missing").toString()), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> invoke(List.of("--config-file"), Map.of()));
        assertEquals("default", invoke(List.of(), Map.of()).value());
    }

    @Test
    void arbitraryEmbeddingConfigurationIsResolvedLazilyPerInvocation() {
        final var value = new AtomicReference<>("first");
        final ConfigurationSource source = key -> "bootstrap.lazy".equals(key) ? value.get() : null;
        assertEquals("first", invoke(List.of(), source).lazy());
        value.set("second");
        assertEquals("second", invoke(List.of(), source).lazy());
        assertEquals("missing", invoke(List.of(), Map.of()).lazy());
        assertNull(System.getProperty("bootstrap.lazy"));
    }

    @Test
    void concurrentInvocationsDoNotShareOverrides() throws Exception {
        final var file = directory.resolve("concurrent.properties");
        Files.writeString(file, "bootstrap.value=file\n");
        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var first = executor.submit(() -> invoke(
                    List.of("--config-file", file.toString(), "--bootstrap-value", "first"),
                    Map.of("bootstrap.value", "embedding")));
            final var second = executor.submit(() -> invoke(
                    List.of("--config-file", file.toString()),
                    Map.of("ARGOCD_APP_PARAMETERS", "[{\"name\":\"bootstrap.value\",\"string\":\"second\"}]")));
            assertEquals("first", first.get(20, TimeUnit.SECONDS).value());
            assertEquals("second", second.get(20, TimeUnit.SECONDS).value());
            assertEquals("default", invoke(List.of(), Map.of()).value());
            assertEquals("bootstrap.value=file\n", Files.readString(file));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void nativePriorityWithRealSystemAndEnvironmentInIsolatedJvms() throws Exception {
        final var home = Files.createDirectory(directory.resolve("home"));
        final var rc = home.resolve(".bundlebeerc");
        final var file = directory.resolve("explicit.properties");
        final var rcContent = "bootstrap.value=rc\nbundlebee.kube.dryRun=true\nbundlebee.kube.verbose=true\n";
        final var fileContent = "bootstrap.value=file\nbundlebee.kube.dryRun=true\nbundlebee.kube.verbose=true\n";
        Files.writeString(rc, rcContent);
        Files.writeString(file, fileContent);
        final var executor = Executors.newFixedThreadPool(3);
        try {
            final var futures = Stream.of("rc", "environment", "system", "file", "argo", "embedding", "cli")
                    .map(mode -> executor.submit(() -> {
                        final var command = new ArrayList<String>();
                        command.add(Path.of(System.getProperty("java.home"), "bin", "java")
                                .toString());
                        command.add("-Duser.home=" + home);
                        if (!List.of("rc", "environment").contains(mode)) {
                            command.add("-Dbootstrap.value=system");
                            command.add("-Dbundlebee.kube.dryRun=true");
                            command.add("-Dbundlebee.kube.verbose=true");
                        }
                        command.addAll(List.of(
                                "-cp",
                                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                                BundleBeeBootstrapTest.class.getName(),
                                mode,
                                file.toString()));
                        final var builder = new ProcessBuilder(command).redirectErrorStream(true);
                        final var environment = builder.environment();
                        environment.remove("ARGOCD_APP_PARAMETERS");
                        environment.remove("BOOTSTRAP_VALUE");
                        if (!"rc".equals(mode)) {
                            environment.put("BOOTSTRAP_VALUE", "environment");
                            environment.put("BUNDLEBEE_KUBE_DRYRUN", "true");
                            environment.put("BUNDLEBEE_KUBE_VERBOSE", "true");
                        }
                        if (List.of("argo", "embedding", "cli").contains(mode)) {
                            environment.put(
                                    "ARGOCD_APP_PARAMETERS", "[{\"name\":\"bootstrap.value\",\"string\":\"argo\"}]");
                        }
                        final var log = directory.resolve(mode + ".log");
                        final var process = builder.redirectOutput(log.toFile()).start();
                        try {
                            assertTrue(process.waitFor(40, TimeUnit.SECONDS), mode + " timed out");
                            assertEquals(0, process.exitValue(), () -> {
                                try {
                                    return mode + ": " + Files.readString(log);
                                } catch (final IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
                        } finally {
                            process.destroyForcibly();
                        }
                        return mode;
                    }))
                    .toList();
            for (final var future : futures) {
                future.get(90, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(rcContent, Files.readString(rc));
        assertEquals(fileContent, Files.readString(file));
        assertNull(System.getProperty("bootstrap.value"));
    }

    /** Child entry point: JVM properties/environment are genuine startup inputs, never test mutations. */
    public static void main(final String[] args) {
        final var test = new BundleBeeBootstrapTest();
        final var mode = args[0];
        final var options = new ArrayList<String>();
        if (List.of("file", "argo", "embedding", "cli").contains(mode)) {
            options.addAll(List.of("--config-file", args[1]));
        }
        if ("cli".equals(mode)) {
            options.addAll(List.of(
                    "--bootstrap.value",
                    "cli",
                    "--bundlebee-kube-dryRun",
                    "false",
                    "--bundlebee.kube.verbose",
                    "false"));
        }
        final var embedding = List.of("embedding", "cli").contains(mode)
                ? Map.of(
                        "bootstrap.value",
                        "embedding",
                        "bundlebee.kube.dryRun",
                        "true",
                        "bundlebee.kube.verbose",
                        "true")
                : Map.<String, String>of();
        final var observation = test.invoke(options, embedding);
        assertEquals(mode, observation.value());
        assertEquals(!"cli".equals(mode), observation.dryRun());
        assertEquals(!"cli".equals(mode), observation.verbose());
        // Neither input files nor decoded Argo/embedding values become system properties.
        assertEquals(
                List.of("rc", "environment").contains(mode) ? null : "system", System.getProperty("bootstrap.value"));
        System.out.println("Verified native priority: " + mode);
    }

    @Test
    void globalKubeconfigCanonicalAndRuntimeAliasRemainSupported() {
        assertEquals(
                "canonical",
                invoke(List.of("--bundlebee-kube-kubeconfig", "canonical"), Map.of("kubeconfig", "embedding"))
                        .kubeconfig());
        assertEquals(
                "alias",
                invoke(List.of("--kubeconfig", "alias"), Map.of("bundlebee.kube.kubeconfig", "embedding"))
                        .kubeconfig());
        assertEquals(
                "embedding",
                invoke(List.of(), Map.of("kubeconfig", "embedding")).kubeconfig());
        assertEquals(
                "canonical",
                invoke(List.of(), Map.of("bundlebee.kube.kubeconfig", "canonical"))
                        .kubeconfig());
    }

    @Test
    void commandRelativeDottedAndDashedAliasesOverrideLowerSources() {
        for (final var flag : List.of(
                "--value", "--bundlebee-bootstrap-alias-probe-value", "--bundlebee.bootstrap-alias-probe.value")) {
            new BundleBee()
                    .launch(
                            new String[] {"bootstrap-alias-probe", flag, "cli"},
                            Map.of("bundlebee.bootstrap-alias-probe.value", "embedding"));
        }
    }

    @DefaultScoped
    @Command(name = "bootstrap-alias-probe", description = "Exercises legacy command-relative aliases.")
    public static class AliasProbe implements Runnable {
        private final AliasConfiguration options;

        public AliasProbe(final AliasConfiguration options) {
            this.options = options;
        }

        @Override
        public void run() {
            assertEquals("cli", options.value());
        }
    }

    @RootConfiguration("bundlebee.bootstrap-alias-probe")
    public record AliasConfiguration(
            @Property(defaultValue = "\"default\"") String value) {}

    private Observation invoke(final List<String> options, final Map<String, String> overrides) {
        return launchAndObserve(options, args -> new BundleBee().launch(args, overrides));
    }

    private Observation invoke(final List<String> options, final ConfigurationSource overrides) {
        return launchAndObserve(options, args -> new BundleBee().launch(args, overrides));
    }

    private Observation launchAndObserve(final List<String> options, final Consumer<String[]> launch) {
        final var id = UUID.randomUUID().toString();
        final var args = Stream.concat(Stream.of("bootstrap-probe", "--bootstrap-id", id), options.stream())
                .toArray(String[]::new);
        launch.accept(args);
        final var result = Probe.OBSERVATIONS.remove(id);
        assertNotNull(result);
        return result;
    }

    public record Observation(
            String value,
            List<String> values,
            boolean enabled,
            String lazy,
            boolean dryRun,
            boolean verbose,
            String kubeconfig) {}

    @DefaultScoped
    @Command(name = "bootstrap-probe", description = "Exercises the generated CLI bootstrap boundary.")
    public static class Probe implements Runnable {
        private static final Map<String, Observation> OBSERVATIONS = new ConcurrentHashMap<>();
        private final ProbeConfiguration options;
        private final Configuration configuration;
        private final GlobalConfiguration global;

        public Probe(
                final ProbeConfiguration options, final Configuration configuration, final GlobalConfiguration global) {
            this.options = options;
            this.configuration = configuration;
            this.global = global;
        }

        @Override
        public void run() {
            OBSERVATIONS.put(
                    options.id(),
                    new Observation(
                            options.value(),
                            options.values(),
                            options.enabled(),
                            configuration.get("bootstrap.lazy").orElse("missing"),
                            global.kube().dryRun(),
                            global.kube().verbose(),
                            global.kube().kubeconfig()));
        }
    }

    @RootConfiguration("bootstrap")
    public record ProbeConfiguration(
            @Property(defaultValue = "\"unset\"") String id,
            @Property(defaultValue = "\"default\"") String value,
            @Property(defaultValue = "java.util.List.of()") List<String> values,
            @Property(defaultValue = "false") boolean enabled) {}
}
