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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.main.Launcher;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.kubernetes.operator.base.impl.OperatorsLifecycle;
import io.yupiik.kubernetes.operator.base.impl.Threads;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorBootstrapTest {
    private static final IllegalStateException RUNTIME_ACCESS = new IllegalStateException("Operator runtime requested");

    @Test
    void selectsCoreKubernetesClientWithoutResolvingOperatorSettings() {
        final var values = Map.of(
                "operator.enabled", "false",
                "kubeconfig", "explicit",
                "bundlebee.kube.tokenFile", "none",
                "bundlebee.kube.api", "http://localhost:12345");
        final ConfigurationSource source = key -> {
            if (key.startsWith("operator.") && !"operator.enabled".equals(key)) {
                throw new AssertionError("Core client resolved operator setting: " + key);
            }
            return values.get(key);
        };
        try (final var container = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                DefaultScoped.class, ConfigurationSource.class, () -> source))
                        .start();
                final var first = container.lookup(KubernetesClient.class);
                final var second = container.lookup(KubernetesClient.class)) {
            assertEquals("http://localhost:12345", first.instance().base().toString());
            assertSame(first.instance(), second.instance());
        }
    }

    @Test
    void sharesOperatorExecutorAndClosesItWithContainer() {
        final ScheduledExecutorService executor;
        try (final var container = ConfiguringContainer.of().start();
                final var scheduler = container.lookup(ScheduledExecutorService.class);
                final var threads = container.lookup(Threads.class)) {
            executor = scheduler.instance();
            assertSame(threads.instance().executor(), executor);
            assertFalse(executor.isShutdown());
        }
        assertTrue(executor.isShutdown());
    }

    @Test
    void twoMetadataContainersWithOperatorClasspathDoNotResolveRuntime() {
        final ConfigurationSource source = key -> {
            if ("operator.enabled".equals(key)) {
                return null;
            }
            if (key.startsWith("operator.") || key.startsWith("bundlebee.kube.")) {
                throw new AssertionError("Metadata container resolved runtime setting: " + key);
            }
            return null;
        };
        try (final var first = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                DefaultScoped.class, ConfigurationSource.class, () -> source))
                        .start();
                final var second = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                DefaultScoped.class, ConfigurationSource.class, () -> source))
                        .start();
                final var mapper = first.lookup(JsonMapper.class);
                final var configuration = first.lookup(Configuration.class);
                final var lifecycle = second.lookup(OperatorsLifecycle.class)) {
            assertEquals(
                    "false", configuration.instance().get("operator.enabled").orElseThrow());
            assertEquals("{}", mapper.instance().toString(Map.of()));
            lifecycle.instance().await();
        }
    }

    @Test
    void coreCliWithOperatorClasspathDoesNotResolveOperatorRuntime() {
        new BundleBee().launch(new String[] {"help"}, key -> {
            if ("operator.enabled".equals(key)) {
                return null;
            }
            if (key.startsWith("operator.")) {
                throw new AssertionError("Core CLI resolved operator setting: " + key);
            }
            return null;
        });
    }

    @Test
    void launcherDefaultsDisabled(@TempDir final Path directory) throws Exception {
        runIsolated(directory, "disabled", List.of(), Map.of());
    }

    @Test
    void launcherHonorsSystemPropertyDisable(@TempDir final Path directory) throws Exception {
        runIsolated(directory, "disabled", List.of("-Doperator.enabled=false"), Map.of());
    }

    @Test
    void launcherHonorsEnvironmentDisable(@TempDir final Path directory) throws Exception {
        runIsolated(directory, "disabled", List.of(), Map.of("OPERATOR_ENABLED", "false"));
    }

    @Test
    void launcherHonorsSystemPropertyEnable(@TempDir final Path directory) throws Exception {
        runIsolated(directory, "enabled", List.of("-Doperator.enabled=true"), Map.of());
    }

    @Test
    void launcherHonorsEnvironmentEnable(@TempDir final Path directory) throws Exception {
        runIsolated(directory, "enabled", List.of(), Map.of("OPERATOR_ENABLED", "true"));
    }

    @Test
    void systemPropertyTakesPrecedenceOverEnvironment(@TempDir final Path directory) throws Exception {
        runIsolated(directory, "disabled", List.of("-Doperator.enabled=false"), Map.of("OPERATOR_ENABLED", "true"));
    }

    @Test
    void systemPropertyEnableTakesPrecedenceOverEnvironmentDisable(@TempDir final Path directory) throws Exception {
        runIsolated(directory, "enabled", List.of("-Doperator.enabled=true"), Map.of("OPERATOR_ENABLED", "false"));
    }

    @Test
    void launcherArgumentsEnableOverSystemAndEnvironmentDisable(@TempDir final Path directory) throws Exception {
        runIsolated(
                directory,
                "enabled",
                List.of("-Doperator.enabled=false"),
                Map.of("OPERATOR_ENABLED", "false"),
                "--operator.enabled",
                "true");
    }

    @Test
    void launcherArgumentsDisableOverSystemAndEnvironmentEnable(@TempDir final Path directory) throws Exception {
        runIsolated(
                directory,
                "disabled",
                List.of("-Doperator.enabled=true"),
                Map.of("OPERATOR_ENABLED", "true"),
                "--operator-enabled=false",
                "--fusion.launcher.useHook=true");
    }

    @Test
    void explicitSourcesOverrideDiscoveredDefault() {
        // Registered sources are authoritative, unlike the fallback appended by OperatorDefaults.
        for (final var enabled : List.of("true", "false")) {
            final var arguments = new ArgsConfigSource(List.of("--operator.enabled=" + enabled));
            final var runtimeAccess = new IllegalStateException("Operator runtime requested");
            final ConfigurationSource source = key -> {
                if ("operator.enabled".equals(key)) {
                    return arguments.get(key);
                }
                if (key.startsWith("operator.")) {
                    throw runtimeAccess;
                }
                return null;
            };
            if ("true".equals(enabled)) {
                final var failure = assertThrows(RuntimeException.class, () -> {
                    try (final var container = ConfiguringContainer.of()
                            .register(new ProvidedInstanceBean<>(
                                    DefaultScoped.class, ConfigurationSource.class, () -> source))
                            .start()) {
                        throw new AssertionError("Enabled operator did not resolve runtime settings");
                    }
                });
                assertTrue(hasCause(failure, runtimeAccess));
            } else {
                try (final var container = ConfiguringContainer.of()
                                .register(new ProvidedInstanceBean<>(
                                        DefaultScoped.class, ConfigurationSource.class, () -> source))
                                .start();
                        final var configuration = container.lookup(Configuration.class)) {
                    assertEquals(
                            "false",
                            configuration.instance().get("operator.enabled").orElseThrow());
                }
            }
        }
    }

    private static boolean hasCause(final Throwable failure, final Throwable expected) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause == expected) {
                return true;
            }
        }
        return false;
    }

    private void runIsolated(
            final Path directory,
            final String scenario,
            final List<String> options,
            final Map<String, String> environment,
            final String... launcherArgs)
            throws Exception {
        final var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + directory);
        command.add("-Dfusion.configuration.sources.secrets=");
        command.add("-Djava.util.logging.manager=io.yupiik.logging.jul.YupiikLogManager");
        command.add("-D.handlers=io.yupiik.logging.jul.handler.StandardHandler");
        command.addAll(options);
        command.addAll(
                List.of("-cp", System.getProperty("java.class.path"), OperatorBootstrapTest.class.getName(), scenario));
        command.addAll(List.of(launcherArgs));
        final var output = directory.resolve("bootstrap.log");
        final var builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().keySet().removeIf(key -> {
            final var normalized = key.toUpperCase(Locale.ROOT);
            return normalized.startsWith("OPERATOR")
                    || normalized.startsWith("FUSION")
                    || normalized.startsWith("BUNDLEBEE")
                    || normalized.equals("KUBECONFIG")
                    || normalized.equals("JAVA_TOOL_OPTIONS")
                    || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS");
        });
        builder.environment().putAll(environment);
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Bootstrap did not terminate");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(output);
                } catch (final IOException e) {
                    return e.toString();
                }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final var launcherArgs = List.of(args).subList(1, args.length).toArray(String[]::new);
        final var enabled = "enabled".equals(args[0]);
        if (enabled) {
            final var failure = assertThrows(RuntimeException.class, () -> {
                try (final var launcher = new RuntimeGuardLauncher(launcherArgs)) {
                    throw new AssertionError("Explicit enable did not resolve runtime settings");
                }
            });
            assertTrue(hasCause(failure, RUNTIME_ACCESS));
        } else {
            try (final var launcher = new RuntimeGuardLauncher(launcherArgs)) {
                launcher.assertDisabled(launcherArgs);
                launcher.await();
            }
        }
        // Actual entry point must return without parsing malformed runtime settings or binding the occupied port.
        try (final var occupied = new ServerSocket(0)) {
            final var options = new ArrayList<String>();
            if (enabled) {
                // Enable cases intentionally fail before starting watches above; exercise main separately disabled.
                options.add("--operator.enabled=false");
            } else {
                options.addAll(List.of(launcherArgs));
            }
            options.addAll(List.of(
                    "--operator.probe-port", Integer.toString(occupied.getLocalPort()),
                    "--operator.event-thread-count", "not-an-integer",
                    "--bundlebee.kube.api", ":not-a-uri"));
            Launcher.main(options.toArray(String[]::new));
            assertTrue(occupied.isBound());
        }
    }

    private static final class RuntimeGuardLauncher extends Launcher {
        private RuntimeGuardLauncher(final String... args) {
            super(args);
        }

        @Override
        protected ConfiguringContainer customize(final ConfiguringContainer container) {
            final ConfigurationSource source = key -> {
                if (!"operator.enabled".equals(key) && key.startsWith("operator.")) {
                    throw RUNTIME_ACCESS;
                }
                return null;
            };
            return container.register(
                    new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> source));
        }

        private void assertDisabled(final String[] expectedArgs) {
            try (final var configuration = container.lookup(Configuration.class);
                    final var args = container.lookup(Args.class)) {
                assertEquals(
                        "false",
                        configuration.instance().get("operator.enabled").orElseThrow());
                assertEquals(List.of(expectedArgs), args.instance().args());
            }
        }
    }
}
