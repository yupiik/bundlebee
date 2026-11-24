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
package io.yupiik.bundlebee.build;

import static java.util.stream.Collectors.toCollection;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessControlException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in, artifact-only Java 17 differential checks; never builds application modules.
 * Enable with {@code -Dbundlebee.cli.features.enabled=true}, and supply absolute paths in
 * {@code bundlebee.cli.features.java}, {@code bundlebee.cli.features.current.jar},
 * {@code bundlebee.cli.features.head.jar}, and {@code bundlebee.cli.features.release.jar}.
 * The Java executable MUST be Java 17 (the offline policy deliberately uses its SecurityManager).
 * Optional {@code bundlebee.cli.features.work} selects a NEW evidence directory; otherwise a
 * {@code bundlebee-cli-feature-*} directory is created in the system temporary directory.
 * Raw stdout, stderr, arguments, exit codes and fixtures are retained even on assertion failure.
 * No historical jar or /tmp baseline is required by default CI. Shared help/completion names
 * are checked strictly except for the explicit tokenFile/certificates additions and the major-release
 * kubeconfig metadata rename, so an opt-in
 * run exposes spelling changes and missing options rather than normalizing them away.
 */
@EnabledIfSystemProperty(named = "bundlebee.cli.features.enabled", matches = "true")
class CliFeatureCompatibilityTest {
    private static final String PREFIX = "bundlebee.cli.features.";
    private static final Pattern LOG_PREFIX = Pattern.compile(
            "(?m)^\\d{4}-\\d{2}-\\d{2}T[^\\n]*?\\[(?:INFO|WARNING|SEVERE|FINE)[^\\n]*?\\]\\[[^\\n]*?\\] ?");
    private static final Pattern OPTIONS = Pattern.compile("(?m)^    (--[^ :]+)");
    private static final Pattern COMMANDS = Pattern.compile("(?m)^  \\[\\] ([^:]+):");
    private static final Pattern PASSWORD = Pattern.compile("<password>([^<]+)</password>");
    private static final Pattern MASTER = Pattern.compile("<master>([^<]+)</master>");
    private static final String SECRET = "fixture secret + punctuation / =";
    private static final Set<String> ADDED_SHARED_OPTIONS =
            Set.of("--bundlebee.kube.tokenFile", "--bundlebee.kube.certificates");
    private static Path work;
    private static Path javaExecutable;
    private static Path policy;
    private static List<Distribution> distributions;

    @BeforeAll
    static void configure() throws Exception {
        javaExecutable = requiredPath("java");
        distributions = List.of(
                new Distribution("current", requiredPath("current.jar")),
                new Distribution("head", requiredPath("head.jar")),
                new Distribution("release", requiredPath("release.jar")));
        final var configuredWork = System.getProperty(PREFIX + "work");
        work = configuredWork == null
                ? Files.createTempDirectory("bundlebee-cli-feature-")
                : Files.createDirectory(Path.of(configuredWork).toAbsolutePath());
        policy = work.resolve("offline.policy");
        // Deliberately NO SocketPermission. File/process/reflection permissions allow the actual CLI,
        // not networking. This is stricter than unusable proxies (java.net.http can bypass them).
        Files.writeString(policy, """
                grant {
                  permission java.io.FilePermission "<<ALL FILES>>", "read,write,delete,execute";
                  permission java.lang.RuntimePermission "*";
                  permission java.lang.reflect.ReflectPermission "*";
                  permission java.util.PropertyPermission "*", "read,write";
                  permission java.security.SecurityPermission "*";
                  permission java.util.logging.LoggingPermission "control";
                  permission java.net.NetPermission "*";
                  permission javax.management.MBeanPermission "*", "*";
                  permission javax.management.MBeanServerPermission "*";
                  permission javax.management.MBeanTrustPermission "*";
                };
                """);
        final var version = launch(work, "java-version", List.of(javaExecutable.toString(), "-version"));
        assertEquals(0, version.exit());
        assertTrue(version.err().contains("version \"17."), "Explicit Java 17 required: " + version.err());
        final var check = new ArrayList<>(List.of(
                javaExecutable.toString(),
                "-Djava.security.manager",
                "-Djava.security.policy==" + policy,
                "-cp",
                System.getProperty("java.class.path"),
                OfflineProbe.class.getName()));
        final var denied = launch(work, "network-denial", check);
        assertEquals(0, denied.exit(), denied.err());
        assertEquals("network-denied", denied.out().strip());
        final var provenance = new StringBuilder(version.err());
        for (final var distribution : distributions) {
            provenance
                    .append(distribution.name())
                    .append('=')
                    .append(distribution.jar())
                    .append('\n');
            try (final var input = Files.newInputStream(distribution.jar())) {
                final var digest = MessageDigest.getInstance("SHA-256");
                final var buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
                provenance.append(HexFormat.of().formatHex(digest.digest())).append('\n');
            }
        }
        Files.writeString(work.resolve("provenance.txt"), provenance);
        System.out.println("CLI feature evidence: " + work);
    }

    @TestFactory
    Stream<DynamicTest> shellHelpCompletionAndErrors() {
        final var cases = new ArrayList<ShellCase>();
        cases.add(new ShellCase("no-arguments", List.of(), 0, "BundleBee", false));
        cases.add(new ShellCase("unknown-command", List.of("nonesuch"), 0, "BundleBee", false));
        cases.add(new ShellCase("help-flag-is-fallback", List.of("--help"), 0, "BundleBee", false));
        cases.add(new ShellCase(
                "missing-option-value", List.of("cipher", "--masterPassword"), 1, "Invalid argument parity", true));
        cases.add(
                new ShellCase("missing-cipher-input", List.of("cipher"), 1, "Missing master password or value", true));
        cases.add(new ShellCase(
                "versions-missing-coordinates", List.of("versions"), 1, "Ensure to set group and artifact", true));
        cases.add(new ShellCase(
                "invalid-enum", List.of("placeholder-extract", "--outputType", "invalid"), 1, "invalid", true));
        cases.add(new ShellCase(
                "decipher-unwrapped-is-passthrough",
                List.of("decipher", "--masterPassword", "master", "--value", "not-ciphertext"),
                0,
                "not-ciphertext",
                false));
        for (final var line : List.of(
                "bundlebee ",
                "bundlebee ver",
                "bundlebee process --",
                "bundlebee process --injectTimestamp ",
                "bundlebee process --bundlebee-kube-dryRun ",
                "bundlebee process --bundlebee.kube.dryRun ",
                "bundlebee nonesuch --")) {
            cases.add(new ShellCase(
                    "completion-" + cases.size(),
                    List.of("completion", "--comp.line", line, "--comp.point", Integer.toString(line.length())),
                    0,
                    "",
                    false));
        }
        return cases.stream()
                .map(spec -> dynamicTest(spec.name(), () -> {
                    final var runs = all(spec.name(), spec.arguments());
                    for (final var run : runs) {
                        assertEquals(spec.exit(), run.exit(), run.err());
                        assertTrue((spec.error() ? run.err() : run.out()).contains(spec.token()), run.toString());
                    }
                    if (spec.name().startsWith("completion-")) {
                        compare(
                                runs,
                                run -> compatibleSharedOptions(lines(run.out())),
                                spec.arguments().get(2).equals("bundlebee "));
                    } else if (spec.name().equals("invalid-enum")) {
                        // Keep diagnostics in evidence, but do not demand CDI converter/stacktrace wording.
                        for (final var run : runs) {
                            assertTrue(run.err().contains("IllegalArgumentException"), run.err());
                        }
                    } else if (spec.error()) {
                        compare(runs, run -> exceptionSummary(run.err()), false);
                    } else if (spec.token().equals("BundleBee")) {
                        compare(runs, run -> matches(COMMANDS, run.out()), true);
                    } else {
                        compare(runs, Run::out, false);
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> helpAndVersionNuances() {
        return Stream.of(
                dynamicTest("version-flag-v2-requires-version-command", () -> {
                    final var runs = all("version-flag", List.of("--version"));
                    // Approved v2 difference: use the version command, not a valueless option.
                    final var current = runs.get(0);
                    assertEquals(1, current.exit(), current.err());
                    assertTrue(current.err().contains("Option '--version' requires a value"), current.err());
                    for (final var historical : runs.subList(1, runs.size())) {
                        assertEquals(0, historical.exit(), historical.err());
                        assertTrue(historical.out().contains("BundleBee"), historical.toString());
                        assertFalse(matches(COMMANDS, historical.out()).isEmpty(), historical.out());
                    }
                }),
                dynamicTest("help-shared-option-spellings", () -> {
                    final var runs = all("help-shared", List.of("help", "--command", "version"));
                    success(runs);
                    assertTrue(
                            matches(OPTIONS, runs.get(0).out()).containsAll(ADDED_SHARED_OPTIONS),
                            runs.get(0).out());
                    for (var i = 0; i < runs.size(); i++) {
                        final var options = matches(OPTIONS, runs.get(i).out());
                        assertTrue(options.contains(i == 0 ? "--bundlebee.kube.kubeconfig" : "--kubeconfig"));
                        assertFalse(options.contains(i == 0 ? "--kubeconfig" : "--bundlebee.kube.kubeconfig"));
                    }
                    compare(runs, run -> compatibleSharedOptions(matches(OPTIONS, run.out())), false);
                }),
                dynamicTest("help-command-filter-without-shared-options", () -> {
                    final var runs = all("help-filter", List.of("help", "--command", "cipher", "--shared", "false"));
                    success(runs);
                    for (final var run : runs) {
                        assertEquals(Set.of("cipher"), matches(COMMANDS, run.out()));
                        assertEquals(Set.of("--masterPassword", "--value"), matches(OPTIONS, run.out()));
                        assertFalse(run.out().contains("Shared Options"));
                    }
                }),
                dynamicTest("help-args-false-and-unknown-filter", () -> {
                    for (final var command : List.of("cipher", "nonesuch", "completion")) {
                        final var runs = all(
                                "help-no-args-" + command,
                                List.of("help", "--command", command, "--shared", "false", "--args", "false"));
                        success(runs);
                        for (final var run : runs) {
                            assertEquals(Set.of(), matches(OPTIONS, run.out()));
                            assertEquals(
                                    command.equals("nonesuch") ? Set.of() : Set.of(command),
                                    matches(COMMANDS, run.out()));
                        }
                    }
                }),
                dynamicTest("version-command-not-version-flag", () -> {
                    for (final var distribution : distributions) {
                        final var run = run(distribution, "version", List.of("version"));
                        final var extra =
                                run(distribution, "version-extra", List.of("version", "--unused", "accepted"));
                        success(List.of(run, extra));
                        final String expected;
                        try (final var jar = new JarFile(distribution.jar().toFile())) {
                            final var entry = jar.getJarEntry("META-INF/maven/io.yupiik/bundlebee-core/pom.properties");
                            assertTrue(entry != null, "Missing version provenance");
                            final var properties = new Properties();
                            try (final var input = jar.getInputStream(entry)) {
                                properties.load(input);
                            }
                            expected = properties.getProperty("version");
                        }
                        assertEquals(expected, run.out().strip());
                        assertEquals(run.out(), extra.out());
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> localFilesAndCodecs() {
        return Stream.of(
                dynamicTest("list-alveoli-json-and-text", () -> {
                    for (final var output : List.of("list.json", "list.txt", "logger.json")) {
                        final var runs = all(
                                "list-" + output,
                                List.of(
                                        "list-alveoli",
                                        "--from",
                                        "fixture",
                                        "--manifest",
                                        "fixture/bundlebee/manifest.json",
                                        "--output",
                                        output));
                        success(runs);
                        compare(
                                runs,
                                run -> output.equals("logger.json")
                                        ? json(run.out())
                                        : output.endsWith(".json")
                                                ? json(Files.readString(
                                                        run.dir().resolve(output)))
                                                : Files.readString(run.dir().resolve(output)),
                                false);
                        if (!output.equals("logger.json")) {
                            assertTrue(Files.readString(runs.get(0).dir().resolve(output))
                                    .contains("sample"));
                        }
                    }
                }),
                dynamicTest("list-lint-rule-set-not-discovery-order", () -> {
                    final var runs = all("lint-rules", List.of("list-lint-rules"));
                    success(runs);
                    compare(runs, run -> lines(run.out()), false);
                    assertEquals(35, lines(runs.get(0).out()).size());
                }),
                dynamicTest("unknown-json-model-fields-are-tolerated", () -> {
                    final var runs = all(
                            "unknown-json",
                            List.of("list-alveoli", "--manifest", "unknown.json", "--output", "items.json"));
                    success(runs);
                    compare(runs, run -> json(Files.readString(run.dir().resolve("items.json"))), false);
                    assertEquals(
                            json("{\"items\":[{\"name\":\"sample\"}]}"),
                            json(Files.readString(runs.get(0).dir().resolve("items.json"))));
                }),
                dynamicTest("yaml-scalars-null-collections-multidoc-and-extension", () -> {
                    final var runs = all("yaml", List.of("yaml2json", "--input", "yaml", "--output", "converted"));
                    success(runs);
                    for (final var file : List.of("mixed.json", "nested/multi.json", "scalar.json", "empty.json")) {
                        compare(runs, run -> json(Files.readString(run.dir().resolve("converted/" + file))), false);
                    }
                    for (final var run : runs) {
                        assertFalse(
                                Files.exists(run.dir().resolve("converted/ignored.json")),
                                ".yml is historically ignored");
                        assertEquals("hello", json(Files.readString(run.dir().resolve("converted/scalar.json"))));
                        assertEquals(
                                2,
                                ((List<?>) json(Files.readString(run.dir().resolve("converted/nested/multi.json"))))
                                        .size());
                    }
                }),
                dynamicTest("yaml-invalid-fails-without-partial-output", () -> {
                    final var runs = all(
                            "yaml-invalid", List.of("yaml2json", "--input", "invalid-yaml", "--output", "converted"));
                    for (final var run : runs) {
                        assertEquals(1, run.exit(), run.err());
                        assertFalse(Files.exists(run.dir().resolve("converted")));
                    }
                }),
                dynamicTest("placeholder-files-and-model-codecs", () -> {
                    final var runs = all(
                            "placeholders-file",
                            visitor("placeholder-extract", "--outputType", "FILE", "--dumpLocation", "extracted"));
                    success(runs);
                    for (final var file : List.of(
                            "placeholders.json",
                            "placeholders.properties",
                            "placeholders.completion.properties",
                            "placeholders.adoc")) {
                        compare(
                                runs,
                                run -> file.endsWith(".json")
                                        ? json(Files.readString(run.dir().resolve("extracted/" + file)))
                                        : Files.readString(run.dir().resolve("extracted/" + file)),
                                false);
                    }
                    final var content = Files.readString(runs.get(0).dir().resolve("extracted/placeholders.json"));
                    assertTrue(content.contains("required.value"));
                    assertFalse(content.contains("service.internal"));
                }),
                dynamicTest("placeholder-argocd-array-codec", () -> {
                    final var runs = all("placeholders-argo", visitor("placeholder-extract", "--outputType", "ARGOCD"));
                    success(runs);
                    compare(runs, run -> json(run.out()), false);
                    assertEquals(3, ((List<?>) json(runs.get(0).out())).size());
                }),
                dynamicTest("process-placeholder-json-and-yaml-payloads", () -> {
                    final var runs = all(
                            "process",
                            visitor(
                                    "process",
                                    "--output",
                                    "stdout",
                                    "--injectTimestamp",
                                    "false",
                                    "--injectBundleBeeMetadata",
                                    "false"));
                    success(runs);
                    compare(
                            runs,
                            run -> json(run.out()
                                    .lines()
                                    .filter(line -> line.startsWith("{"))
                                    .findFirst()
                                    .orElseThrow()),
                            false);
                    assertTrue(runs.get(0).out().contains("\"name\":\"demo\""));
                    assertTrue(runs.get(0).out().contains("hello"));
                }));
    }

    @TestFactory
    Stream<DynamicTest> passwordsAndCipherInteroperability() {
        return Stream.of(
                dynamicTest("cipher-all-producers-all-consumers", () -> {
                    for (final var producer : distributions) {
                        final var encrypted = run(
                                producer, "cipher", List.of("cipher", "--masterPassword", "master", "--value", SECRET));
                        success(List.of(encrypted));
                        final var value = encrypted.out().strip();
                        assertTrue(value.startsWith("{") && value.endsWith("}"), encrypted.out());
                        for (final var consumer : distributions) {
                            final var decrypted = run(
                                    consumer,
                                    "decipher-from-" + producer.name(),
                                    List.of("decipher", "--masterPassword", "master", "--value", value));
                            success(List.of(decrypted));
                            assertEquals(SECRET, decrypted.out().strip());
                        }
                    }
                }),
                dynamicTest("maven-master-idempotence-and-cross-version-server-passwords", () -> {
                    for (final var producer : distributions) {
                        final var created = run(
                                producer, "master", List.of("create-master-password", "--password", "fixture-master"));
                        success(List.of(created));
                        // preferCustomSettingsXml defaults to true: settings live beside the configured cache.
                        final var security = created.dir().resolve("repository/settings-security.xml");
                        assertTrue(Files.exists(security), created.toString());
                        final var content = Files.readString(security);
                        final var repeat = runAt(
                                producer,
                                created.dir(),
                                "master-repeat",
                                List.of("create-master-password", "--password", "must-not-overwrite"));
                        success(List.of(repeat));
                        assertEquals(content, Files.readString(security));
                        final var masterValue = capture(MASTER, content);
                        for (final var consumer : distributions) {
                            final var decryptedMaster = run(
                                    consumer,
                                    "master-decipher-from-" + producer.name(),
                                    List.of(
                                            "decipher",
                                            "--masterPassword",
                                            "settings.security",
                                            "--value",
                                            masterValue));
                            success(List.of(decryptedMaster));
                            assertEquals("fixture-master", decryptedMaster.out().strip());
                            final var dir = fixture(consumer, "server-from-" + producer.name());
                            Files.writeString(
                                    dir.resolve("repository/settings.xml"), "<settings><servers/></settings>");
                            Files.writeString(dir.resolve("repository/settings-security.xml"), content);
                            final var server =
                                    runAt(consumer, dir, "server", List.of("cipher-password", "--password", SECRET));
                            success(List.of(server));
                            final var decrypted = run(
                                    consumer,
                                    "server-decipher-from-" + producer.name(),
                                    List.of(
                                            "decipher",
                                            "--masterPassword",
                                            "fixture-master",
                                            "--value",
                                            capture(PASSWORD, server.out())));
                            success(List.of(decrypted));
                            assertEquals(SECRET, decrypted.out().strip());
                        }
                    }
                }),
                dynamicTest("server-password-without-master-fails", () -> {
                    final var runs = all("server-no-master", List.of("cipher-password", "--password", SECRET));
                    for (final var run : runs) {
                        assertEquals(1, run.exit(), run.err());
                        assertTrue(
                                run.err().contains("ensure to call create-master-password command first"), run.err());
                    }
                }));
    }

    private static Path requiredPath(final String name) {
        final var value = System.getProperty(PREFIX + name);
        assertTrue(value != null && !value.isBlank(), "Set -D" + PREFIX + name);
        final var path = Path.of(value).toAbsolutePath();
        assertTrue(Files.isRegularFile(path), "Missing artifact/executable: " + path);
        return path;
    }

    private static List<String> visitor(final String command, final String... extra) {
        final var arguments = new ArrayList<>(List.of(
                command, "--from", "fixture", "--manifest", "fixture/bundlebee/manifest.json", "--alveolus", "sample"));
        arguments.addAll(List.of(extra));
        return arguments;
    }

    private static List<Run> all(final String name, final List<String> arguments) throws Exception {
        final var results = new ArrayList<Run>();
        for (final var distribution : distributions) {
            results.add(run(distribution, name, arguments));
        }
        return results;
    }

    private static Run run(final Distribution distribution, final String name, final List<String> arguments)
            throws Exception {
        return runAt(distribution, fixture(distribution, name), name, arguments);
    }

    private static Path fixture(final Distribution distribution, final String name) throws Exception {
        final var dir = Files.createDirectories(work.resolve(name).resolve(distribution.name()));
        Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(dir.resolve("repository"));
        write(dir, "fixture/bundlebee/manifest.json", """
                {"alveoli":[{"name":"sample","descriptors":[{"name":"config","interpolate":true}]},{"name":"aaa"}]}
                """);
        write(dir, "fixture/bundlebee/kubernetes/config.yaml", """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: "{{app.name:-demo}}"
                data:
                  greeting: "{{greeting:-hello}}"
                  required: "{{required.value}}"
                  ignored: "{{service.internal:-hidden}}"
                """);
        write(
                dir,
                "unknown.json",
                "{\"future\":{\"nested\":[null,true,42]},\"alveoli\":[{\"name\":\"sample\",\"extra\":true}]}");
        write(dir, "yaml/mixed.yaml", """
                integer: 12
                float: 1.25
                truth: true
                empty: null
                quoted: "001"
                list: [1, null, false, "str", {nested: 3}]
                map: {x: "y"}
                alias: &value {a: b}
                copy: *value
                """);
        write(dir, "yaml/nested/multi.yaml", """
                apiVersion: v1
                kind: ConfigMap
                metadata: {name: first}
                ---
                apiVersion: v1
                kind: ConfigMap
                metadata: {name: second}
                """);
        write(dir, "yaml/scalar.yaml", "hello\n");
        write(dir, "yaml/empty.yaml", "{}\n");
        write(dir, "yaml/ignored.yml", "ignored: true\n");
        write(dir, "invalid-yaml/bad.yaml", "broken: [\n");
        return dir;
    }

    private static void write(final Path dir, final String file, final String content) throws Exception {
        final var path = dir.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Run runAt(
            final Distribution distribution, final Path dir, final String name, final List<String> arguments)
            throws Exception {
        final var command = new ArrayList<>(List.of(
                javaExecutable.toString(),
                "-Djava.security.manager",
                "-Djava.security.policy==" + policy,
                "-Duser.home=" + dir.resolve("home"),
                "-Duser.name=fixture-user",
                "-Duser.language=en",
                "-Duser.country=US",
                "-Duser.timezone=UTC",
                "-Dmaven.repo.local=" + dir.resolve("repository"),
                "-Dbundlebee.maven.repositories.downloads.enabled=false",
                "-Dbundlebee.kubeconfig=explicit",
                "-Dio.yupiik.logging.jul.async=false",
                "-D.handlers=io.yupiik.logging.jul.handler.StandardHandler",
                "-jar",
                distribution.jar().toString()));
        command.addAll(arguments);
        return launch(dir, name, command);
    }

    private static Run launch(final Path dir, final String name, final List<String> command) throws Exception {
        final var stdout = dir.resolve(name + ".stdout");
        final var stderr = dir.resolve(name + ".stderr");
        Files.writeString(dir.resolve(name + ".arguments"), String.join("\n", command) + '\n');
        final var builder = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().clear();
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Timed out: " + dir + '/' + name);
            Files.writeString(dir.resolve(name + ".exit"), Integer.toString(process.exitValue()));
            return new Run(
                    dir, process.exitValue(), normalize(Files.readString(stdout)), normalize(Files.readString(stderr)));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Failed to terminate: " + dir);
            }
        }
    }

    private static String normalize(final String value) {
        return LOG_PREFIX.matcher(value.replace("\r\n", "\n")).replaceAll("");
    }

    private static Set<String> matches(final Pattern pattern, final String value) {
        return pattern.matcher(value).results().map(match -> match.group(1)).collect(toCollection(TreeSet::new));
    }

    private static Set<String> lines(final String value) {
        return value.lines().filter(line -> !line.isBlank()).collect(toCollection(TreeSet::new));
    }

    private static Set<String> compatibleSharedOptions(final Set<String> options) {
        return options.stream()
                .filter(option -> !ADDED_SHARED_OPTIONS.contains(option))
                // Only the documented metadata rename is allowed; the runtime alias is tested by fixtures.
                .map(option -> option.equals("--kubeconfig") ? "--bundlebee.kube.kubeconfig" : option)
                .collect(toCollection(TreeSet::new));
    }

    private static String exceptionSummary(final String error) {
        return error.lines()
                .filter(line -> line.startsWith("Exception in thread"))
                .findFirst()
                .orElseThrow();
    }

    private static String capture(final Pattern pattern, final String value) {
        final var matcher = pattern.matcher(value);
        assertTrue(matcher.find(), value);
        return matcher.group(1);
    }

    private static Object json(final String value) {
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(JsonMapper.class)) {
            return lookup.instance().fromString(Object.class, value);
        }
    }

    private static void success(final List<Run> runs) {
        assertAll(runs.stream().map(run -> () -> assertEquals(0, run.exit(), run.toString())));
    }

    private static void compare(final List<Run> runs, final Observation observation, final boolean additive)
            throws Exception {
        final var actual = observation.get(runs.get(0));
        final var expected = new LinkedHashMap<Run, Object>();
        for (final var baseline : runs.subList(1, runs.size())) {
            expected.put(baseline, observation.get(baseline));
        }
        assertAll(expected.entrySet().stream().map(entry -> () -> {
            if (additive && actual instanceof Set<?> actualSet && entry.getValue() instanceof Set<?> expectedSet) {
                assertTrue(
                        actualSet.containsAll(expectedSet),
                        () -> entry.getKey().dir() + " missing values: " + expectedSet + " actual: " + actualSet);
            } else {
                assertEquals(
                        entry.getValue(),
                        actual,
                        () -> "current versus " + entry.getKey().dir());
            }
        }));
    }

    private record Distribution(String name, Path jar) {}

    private record Run(Path dir, int exit, String out, String err) {}

    private record ShellCase(String name, List<String> arguments, int exit, String token, boolean error) {}

    @FunctionalInterface
    private interface Observation {
        Object get(Run run) throws Exception;
    }

    /** Subprocess proof that the very same policy used by the CLI denies network connections. */
    public static class OfflineProbe {
        @SuppressWarnings("removal")
        public static void main(final String[] args) throws Exception {
            try (final var socket = new Socket("127.0.0.1", 9)) {
                throw new IllegalStateException("Network unexpectedly permitted: " + socket);
            } catch (final AccessControlException expected) {
                System.out.println("network-denied");
            }
        }
    }
}
