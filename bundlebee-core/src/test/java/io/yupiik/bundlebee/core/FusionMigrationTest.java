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

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FusionMigrationTest {
    @TempDir
    Path directory;

    @Test
    void legacyConfigurationNamesRemainDiscoverableExceptDocumentedAliases() throws Exception {
        final var baseline = new Properties();
        try (final var input = getClass().getResourceAsStream("legacy-option-names.properties")) {
            assertNotNull(input);
            baseline.load(input);
        }
        assertEquals(207, baseline.size());
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(ParameterExtractor.class)) {
            final var current = new HashSet<String>();
            lookup.instance()
                    .commands()
                    .forEach(command -> command.parameters().forEach(parameter -> current.add(parameter.configName())));
            final var missing = new HashSet<>(baseline.stringPropertyNames());
            missing.removeAll(current);
            assertEquals(
                    Set.of("kubeconfig", "bundlebee.maven.http.connectTimeout"),
                    missing,
                    "Only documented configuration aliases may rename an option");
            assertFalse(current.contains("kubeconfig"));
            assertTrue(current.contains("bundlebee.kube.kubeconfig"));
            assertTrue(current.contains("bundlebee.maven.http.timeout"));
        }
    }

    @Test
    void subprocessUsesFusionPrecedenceWithoutJvmPropertyLeaks() throws Exception {
        final var argo =
                Map.of("ARGOCD_APP_PARAMETERS", "[{\"name\":\"bundlebee.help.command\",\"string\":\"decipher\"}]");
        for (final var option : List.of("--command", "--bundlebee.help.command", "--bundlebee-help-command")) {
            assertCommand("cipher", runCli(List.of(), argo, "help", option, "cipher"));
            assertCommand("decipher", runCli(List.of(), Map.of(), "help", option, "cipher", option, "decipher"));
        }
        assertCommand(
                "decipher", runCli(List.of("-Dbundlebee.help.command=cipher"), argo, "help", "--command", "decipher"));
        assertCommand(
                "decipher",
                runCli(List.of(), Map.of("BUNDLEBEE_HELP_COMMAND", "cipher"), "help", "--command", "decipher"));
        assertCommand("decipher", runCli(List.of(), argo, "help"));
        Files.writeString(directory.resolve(".bundlebeerc"), "bundlebee.help.command=cipher\n");
        assertCommand(
                "decipher",
                runCli(List.of("-Dbundlebee.help.command=decipher"), argo, "help", "--command", "decipher"));
        assertCommand("decipher", runCli(List.of("-Dbundlebee.help.command=decipher"), Map.of(), "help"));
        assertCommand("decipher", runCli(List.of(), Map.of("BUNDLEBEE_HELP_COMMAND", "decipher"), "help"));
        final var file = directory.resolve("invocation.properties");
        Files.writeString(file, "bundlebee.help.command=decipher\n");
        assertCommand(
                "cipher", runCli(List.of(), Map.of(), "--config-file", file.toString(), "help", "--command", "cipher"));
        assertCommand(
                "cipher", runCli(List.of(), Map.of(), "help", "--command", "cipher", "--config-file", file.toString()));
    }

    @Test
    void subprocessKeepsLegacyFlagErrorsAndUnknownCommandFallback() throws Exception {
        for (final var args : List.of(
                List.of("help", "--shared"), List.of("help", "--no-shared"), List.of("help", "--command=cipher"))) {
            final var result = runCli(List.of(), Map.of(), args.toArray(String[]::new));
            assertNotEquals(0, result.exitCode(), result.toString());
            assertEquals("", result.stdout(), result.toString());
            assertTrue(result.stderr().contains("Invalid argument parity"), result.toString());
        }
        final var missingFile = runCli(
                List.of(),
                Map.of(),
                "help",
                "--config-file",
                directory.resolve("missing.properties").toString());
        assertNotEquals(0, missingFile.exitCode(), missingFile.toString());
        assertTrue(missingFile.stderr().contains("NoSuchFileException"), missingFile.toString());
        final var unknown = runCli(List.of(), Map.of(), "not-a-command");
        assertEquals(0, unknown.exitCode(), unknown.toString());
        assertTrue(unknown.stdout().contains("BundleBee"), unknown.toString());
        assertTrue((unknown.stdout() + unknown.stderr()).contains("No command found"), unknown.toString());
        final var completion = runCli(
                List.of(),
                Map.of(),
                "completion",
                "--comp.line",
                "bundlebee he",
                "--comp.point",
                "12",
                "--useLogger",
                "false",
                "--incomplete");
        assertEquals(0, completion.exitCode(), completion.toString());
        assertFalse(completion.stderr().contains("Exception"), completion.toString());
    }

    private void assertCommand(final String command, final CliResult result) {
        assertEquals(0, result.exitCode(), result.toString());
        assertTrue(result.stdout().contains("  [] " + command + ":"), result.toString());
        assertFalse(
                result.stdout().contains("  [] " + ("cipher".equals(command) ? "decipher" : "cipher") + ":"),
                result.toString());
        assertFalse(result.stderr().contains("Exception"), result.toString());
    }

    private CliResult runCli(final List<String> properties, final Map<String, String> environment, final String... args)
            throws Exception {
        final var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + directory,
                "-D.handlers=io.yupiik.logging.jul.handler.StandardHandler"));
        command.addAll(properties);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), BundleBee.class.getName()));
        command.addAll(List.of(args));
        final var stdout = Files.createTempFile(directory, "cli-", ".out");
        final var stderr = Files.createTempFile(directory, "cli-", ".err");
        final var builder =
                new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().remove("ARGOCD_APP_PARAMETERS");
        builder.environment().remove("BUNDLEBEE_HELP_COMMAND");
        builder.environment().putAll(environment);
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "CLI subprocess timed out: " + command);
            return new CliResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private record CliResult(int exitCode, String stdout, String stderr) {}

    @Test
    void launchOverridesAreIsolatedAndCliWins() {
        final var overrides = Map.of(
                "bundlebee.help.command",
                "cipher",
                "bundlebee.help.args",
                "false",
                "bundlebee.help.shared",
                "false",
                "kubeconfig",
                "/must-not-be-read-for-help");
        final var executor = new CommandExecutor();
        final var first = executor.wrap(null, INFO, () -> new BundleBee().launch(new String[] {"help"}, overrides));
        final var second = executor.wrap(
                null,
                INFO,
                () -> new BundleBee().launch(new String[] {"help", "--bundlebee-help-command", "decipher"}, overrides));
        assertTrue(first.contains("  [] cipher:"), first);
        assertFalse(first.contains("  [] decipher:"), first);
        assertTrue(second.contains("  [] decipher:"), second);
        assertFalse(second.contains("  [] cipher:"), second);
        assertEquals("cipher", overrides.get("bundlebee.help.command"));
        assertNull(System.getProperty("kubeconfig"));
    }

    @Test
    void independentContainerConfiguration() {
        try (final var first = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                ApplicationScoped.class,
                                ConfigurationSource.class,
                                () -> Map.of("isolated.key", "first")::get))
                        .start();
                final var second = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                ApplicationScoped.class,
                                ConfigurationSource.class,
                                () -> Map.of("isolated.key", "second")::get))
                        .start();
                final var a = first.lookup(Configuration.class);
                final var b = second.lookup(Configuration.class)) {
            assertEquals("first", a.instance().get("isolated.key").orElseThrow());
            assertEquals("second", b.instance().get("isolated.key").orElseThrow());
        }
    }

    @Test
    void descriptorsAreAvailableWithoutCommandInitialization() {
        try (final var container = ConfiguringContainer.of().start();
                final var extractor = container.lookup(ParameterExtractor.class)) {
            final var commands = extractor.instance().commands();
            assertEquals(
                    commands.size(),
                    commands.stream().map(CliCommand::name).distinct().count());
            final var apply = commands.stream()
                    .filter(command -> command.name().equals("apply"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(apply.parameters().stream()
                    .anyMatch(parameter -> parameter.configName().equals("bundlebee.apply.alveolus")));
        }
    }

    @Test
    void argumentsUseFusionNativeSemantics() {
        final var source = new ArgsConfigSource(List.of(
                "apply",
                "--bundlebee-apply-injectTimestamp=false",
                "--fusion-properties",
                "custom.key=value",
                "--repeat",
                "one",
                "--repeat",
                "two"));
        assertEquals("false", source.get("bundlebee.apply.injectTimestamp"));
        assertEquals("value", source.get("custom.key"));
        assertEquals("one,two", source.get("repeat"));
        assertThrows(IllegalArgumentException.class, () -> new ArgsConfigSource(List.of("apply", "--alveolus")));
    }

    @Test
    void generatedModelCodecsPreserveGenericPatchesAndWireNames() {
        try (final var container = ConfiguringContainer.of().start();
                final var instance = container.lookup(JsonMapper.class)) {
            final var mapper = instance.instance();
            final var manifest = mapper.fromString(
                    Manifest.class,
                    "{\"alveoli\":[{\"name\":\"test\",\"patches\":[{\"descriptorName\":\"*\",\"patch\":[{\"op\":\"add\",\"path\":\"/value\",\"value\":null}]}]}]}");
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> manifest.alveoli()
                            .add(new Manifest.Alveolus(null, "other", null, null, null, false, null, null, null)));
            assertEquals(1, manifest.alveoli().size());
            final var roundTrip = mapper.fromString(Manifest.class, mapper.toString(manifest));
            final var operation = assertInstanceOf(
                    Map.class,
                    roundTrip.alveoli().get(0).patches().get(0).patch().get(0));
            assertTrue(operation.containsKey("value"));
            assertNull(operation.get("value"));
        }
    }

    @Test
    void thinClasspathRunsOfflineWithoutLegacyRuntime() throws Exception {
        final var executable =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final var process = new ProcessBuilder(
                        executable,
                        "-D.handlers=io.yupiik.logging.jul.handler.StandardHandler",
                        "-Dkubeconfig=/must-not-be-read",
                        "-cp",
                        System.getProperty("java.class.path"),
                        BundleBee.class.getName(),
                        "help",
                        "--bundlebee-help-shared",
                        "false",
                        "--bundlebee-help-args",
                        "false")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("BundleBee"), output);
        assertFalse(output.contains("Exception"), output);
        for (final var type : List.of(
                "javax.enterprise.inject.spi.BeanManager",
                "javax.json.Json",
                "org.apache.johnzon.jsonb.JsonbImpl",
                "org.apache.webbeans.config.WebBeansContext")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }
}
