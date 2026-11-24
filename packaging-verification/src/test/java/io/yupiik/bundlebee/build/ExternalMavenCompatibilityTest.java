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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in, offline tests of the installed plugin, never the application reactor.
 * Run with -Dbundlebee.external.maven=/absolute/path/to/mvn and
 * -Dbundlebee.external.javaHome=/absolute/path/to/java17.
 * Optional bundlebee.external.version selects the installed baseline (default 2.0.0-SNAPSHOT).
 * All generated fixtures and subprocess logs are retained under /tmp/bundlebee-maven-concurrency*.
 */
@EnabledIfSystemProperty(named = "bundlebee.external.maven", matches = ".+")
class ExternalMavenCompatibilityTest {
    private final String version = System.getProperty("bundlebee.external.version", "2.0.0-SNAPSHOT");

    @Test
    void parallelModulesKeepConfigurationAndMavenExpressionsIsolated() throws Exception {
        final var root = fixture("parallel", List.of("one", "two", "three", "four", "five", "six", "seven", "eight"));
        for (final var name : List.of("one", "two", "three", "four", "five", "six", "seven", "eight")) {
            module(root, name, "", false);
        }
        // Repeated executions share a plugin realm within one Maven process; repeated processes
        // exercise scheduling variation without serializing the reactor's intended concurrency.
        for (var iteration = 0; iteration < 3; iteration++) {
            for (final var name : List.of("one", "two", "three", "four", "five", "six", "seven", "eight")) {
                Files.deleteIfExists(root.resolve(name + "/result/sample"));
            }
            final var result = maven(root, "parallel-" + iteration, "-T", "4", goal(), goal());
            assertEquals(0, result.exit(), result.log());
            assertTrue(result.text().contains("thread count of 4"), result.log());
            for (final var name : List.of("one", "two", "three", "four", "five", "six", "seven", "eight")) {
                verifyModule(root, name);
            }
        }
    }

    @Test
    void failureDoesNotContaminateIndependentModulesAndNextInvocation() throws Exception {
        final var root = fixture("failure", List.of("broken", "healthy", "skipped"));
        module(root, "broken", "", true);
        module(root, "healthy", "", false);
        module(root, "skipped", "<skip>true</skip>", true);
        final var failed = maven(root, "failure", "-T", "3", "-fae", goal());
        assertTrue(failed.exit() != 0, failed.log());
        assertTrue(failed.text().contains("broken") && failed.text().contains("FAILURE"), failed.log());
        assertTrue(failed.text().contains("execution skipped"), failed.log());
        assertFalse(Files.exists(root.resolve("broken/result/sample")), failed.log());
        assertFalse(Files.exists(root.resolve("skipped/result/sample")), failed.log());
        verifyModule(root, "healthy");
        writeManifest(root.resolve("broken"), false);
        final var recovered = maven(root, "recovered", "-T", "3", goal());
        assertEquals(0, recovered.exit(), recovered.log());
        verifyModule(root, "broken");
        verifyModule(root, "healthy");
    }

    @Test
    void explicitAndPackagingSkipsDoNotLoadInvalidInputs() throws Exception {
        final var root = fixture("skip", List.of("skipped"));
        module(root, "skipped", "<skip>true</skip>", true);
        final var result = maven(root, "skip", "-T", "2", goal());
        assertEquals(0, result.exit(), result.log());
        assertEquals(
                2,
                result.text()
                        .lines()
                        .filter(it -> it.contains("execution skipped"))
                        .count(),
                result.log());
        assertFalse(Files.exists(root.resolve("skipped/result")), result.log());
        assertFalse(Files.exists(root.resolve("result")), result.log());
        // Removing the default pom packaging skip must really invoke the command.
        final var override = maven(root, "packaging-override", "-N", "-Dbundlebee.skipPackaging=", goal());
        assertTrue(override.exit() != 0, override.log());
        assertFalse(override.text().contains("execution skipped"), override.log());
    }

    @Test
    void mavenExpressionsInConfigurationAndImportsWorkWithForcedFilteringDisabled() throws Exception {
        final var root = fixture("expressions", List.of("expressions"));
        module(
                root,
                "expressions",
                "<skipMavenForcedFilteringForPlaceholders>true</skipMavenForcedFilteringForPlaceholders>",
                false);
        final var pom = root.resolve("expressions/pom.xml");
        Files.writeString(
                pom,
                Files.readString(pom)
                        .replace(
                                "</customPlaceholders>",
                                "<expression>${project.artifactId}:${project.version}:${fixture.value}</expression></customPlaceholders>"));
        Files.writeString(
                root.resolve("expressions/bundlebee/kubernetes/sample.yaml"),
                "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"expressions\"},"
                        + "\"data\":{\"expression\":\"{{expression}}\",\"imported\":\"{{imported}}\"}}");
        final var result = maven(root, "expressions", "-T", "2", goal());
        assertEquals(0, result.exit(), result.log());
        assertEquals(
                Map.of(
                        "expression",
                        "expressions:1-expressions:property-expressions",
                        "imported",
                        "expressions:1-expressions"),
                output(root.resolve("expressions/result/sample")).get("data"),
                result.log());
    }

    @Test
    void pluginRealmResourceImportsRemainVisible() throws Exception {
        final var root = fixture("classloader", List.of("realm"));
        module(root, "realm", "", false);
        final var pom = root.resolve("realm/pom.xml");
        Files.writeString(
                pom,
                Files.readString(pom)
                        .replace(
                                "</customPlaceholders>",
                                "<bundlebee-placeholder-import-realm>META-INF/maven/io.yupiik/bundlebee-maven-plugin/pom.properties</bundlebee-placeholder-import-realm></customPlaceholders>"));
        Files.writeString(
                root.resolve("realm/bundlebee/kubernetes/sample.yaml"),
                "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"realm\"},"
                        + "\"data\":{\"pluginVersion\":\"{{version}}\",\"pluginArtifact\":\"{{artifactId}}\"}}");
        final var result = maven(root, "realm", "-T", "2", goal());
        assertEquals(0, result.exit(), result.log());
        assertEquals(
                Map.of("pluginVersion", version, "pluginArtifact", "bundlebee-maven-plugin"),
                output(root.resolve("realm/result/sample")).get("data"),
                result.log());
    }

    private Path fixture(final String name, final List<String> modules) throws Exception {
        final var root =
                Files.createTempDirectory(Path.of("/tmp"), "bundlebee-maven-concurrency-" + version + "-" + name + "-");
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>external.fixture</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><modules>"
                        + String.join(
                                "",
                                modules.stream()
                                        .map(it -> "<module>" + it + "</module>")
                                        .toList())
                        + "</modules><build><plugins><plugin><groupId>io.yupiik</groupId><artifactId>bundlebee-maven-plugin</artifactId><version>"
                        + version
                        + "</version><configuration><manifest>${project.basedir}/missing.json</manifest>"
                        + "<kubeconfig>explicit</kubeconfig></configuration>"
                        + execution() + "</plugin></plugins></build></project>");
        System.out.println("External Maven fixture: " + root);
        return root;
    }

    private void module(final Path root, final String name, final String extra, final boolean broken) throws Exception {
        final var directory = Files.createDirectories(root.resolve(name));
        Files.writeString(
                directory.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>external.fixture</groupId>
                <artifactId>%s</artifactId><version>1-%s</version>
                <properties><fixture.value>property-%s</fixture.value></properties>
                <build><plugins><plugin><groupId>io.yupiik</groupId><artifactId>bundlebee-maven-plugin</artifactId><version>%s</version>
                <configuration><manifest>${project.basedir}/bundlebee/manifest.json</manifest>
                <output>${project.basedir}/result</output><injectTimestamp>false</injectTimestamp>
                <injectBundleBeeMetadata>false</injectBundleBeeMetadata><kubeconfig>explicit</kubeconfig>
                <customPlaceholders><direct>direct-%s</direct>
                <bundlebee-placeholder-import-file>${project.basedir}/values.properties</bundlebee-placeholder-import-file>
                </customPlaceholders>%s</configuration>%s</plugin></plugins></build></project>
                """.formatted(name, name, name, version, name, extra, execution()));
        Files.writeString(
                directory.resolve("values.properties"), "imported=${project.artifactId}:${project.version}\n");
        writeManifest(directory, broken);
        Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/kubernetes/sample.yaml"), """
                {"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"{{project.artifactId}}"},
                "data":{"version":"{{project.version}}","property":"{{fixture.value}}","direct":"{{direct}}",
                "imported":"{{imported}}","basedir":"{{project.basedir}}"}}
                """);
    }

    private void writeManifest(final Path directory, final boolean broken) throws Exception {
        Files.createDirectories(directory.resolve("bundlebee"));
        Files.writeString(
                directory.resolve("bundlebee/manifest.json"),
                broken
                        ? "{invalid-json"
                        : "{\"alveoli\":[{\"name\":\"fixture\",\"descriptors\":[{\"name\":\"sample\",\"interpolate\":true}]}]}");
    }

    private void verifyModule(final Path root, final String name) throws Exception {
        final var processed = output(root.resolve(name + "/result/sample"));
        assertEquals(name, ((Map<?, ?>) processed.get("metadata")).get("name"), root.toString());
        assertEquals(
                Map.of(
                        "version",
                        "1-" + name,
                        "property",
                        "property-" + name,
                        "direct",
                        "direct-" + name,
                        "imported",
                        name + ":1-" + name,
                        "basedir",
                        root.resolve(name).toString()),
                processed.get("data"),
                root.toString());
    }

    private Map<?, ?> output(final Path path) throws Exception {
        assertTrue(Files.isRegularFile(path), "Missing processed descriptor: " + path);
        try (final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
            return mapper.fromString(Map.class, Files.readString(path));
        }
    }

    private String execution() {
        // A requiresProject=false CLI goal is root-only on Maven 3.6.3. Bind it to
        // a lifecycle phase so the external reactor really executes every module.
        return "<executions><execution><id>external-process</id><phase>validate</phase>"
                + "<goals><goal>process</goal></goals></execution></executions>";
    }

    private String goal() {
        return "validate";
    }

    private Result maven(final Path root, final String name, final String... arguments) throws Exception {
        final var javaHome =
                Path.of(System.getProperty("bundlebee.external.javaHome", System.getProperty("java.home")));
        assertTrue(
                Files.readString(javaHome.resolve("release")).contains("JAVA_VERSION=\"17."),
                "Explicit Java 17 required: " + javaHome);
        final var command =
                new ArrayList<>(List.of(System.getProperty("bundlebee.external.maven"), "-o", "-B", "-ntp"));
        command.addAll(List.of(arguments));
        final var log = root.resolve(name + ".log");
        Files.writeString(
                root.resolve(name + ".command"), "JAVA_HOME=" + javaHome + "\n" + String.join(" ", command) + "\n");
        final var builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        final var environment = builder.environment();
        environment.clear();
        environment.put("JAVA_HOME", javaHome.toString());
        environment.put("PATH", javaHome.resolve("bin") + ":/usr/bin:/bin");
        environment.put("HOME", System.getProperty("user.home"));
        environment.put("LANG", "C.UTF-8");
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Maven timed out: " + log);
            return new Result(process.exitValue(), Files.readString(log), log.toString());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private record Result(int exit, String text, String log) {}
}
