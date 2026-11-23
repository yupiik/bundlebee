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
package io.yupiik.bundlebee.maven.build;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MojoGeneratorTest {
    @Test
    void repeatedGenerationUsesDetachedMetadata(@TempDir final Path output) throws Exception {
        final var first = output.resolve("first");
        final var second = output.resolve("second");
        MojoGenerator.main("unused", first.toString());
        MojoGenerator.main("unused", second.toString());
        final var packagePath = "io/yupiik/bundlebee/maven/generated/mojo";
        try (final var files = Files.list(first.resolve(packagePath))) {
            final var generated = files.toList();
            assertFalse(generated.isEmpty());
            for (final var file : generated) {
                assertEquals(
                        Files.readString(file),
                        Files.readString(second.resolve(packagePath).resolve(file.getFileName())));
            }
        }
    }

    @Test
    void preservesLegacyMavenContract(@TempDir final Path output) throws Exception {
        MojoGenerator.main("unused", output.toString());
        final var mojos = output.resolve("io/yupiik/bundlebee/maven/generated/mojo");
        final var apply = Files.readString(mojos.resolve("ApplyMojo.java"));
        final var newBundle = Files.readString(mojos.resolve("NewMojo.java"));
        final var add = Files.readString(mojos.resolve("AddAlveolusMojo.java"));
        final var build = Files.readString(mojos.resolve("BuildMojo.java"));
        final var process = Files.readString(mojos.resolve("ProcessMojo.java"));
        final var http = Files.readString(mojos.resolve("HttpMojo.java"));
        assertAll(
                () -> assertTrue(
                        http.contains("defaultValue = \"Accept: application/jsonnContent-Type: application/json\"")),
                () -> assertTrue(
                        Files.exists(mojos.resolve("CompletionMojo.java")),
                        "Legacy completion goal must remain available even when hidden from CLI help"),
                () -> assertTrue(apply.contains(
                        "@Parameter(property = \"bundlebee.awaiter.retryInterval\", defaultValue = \"500\")")),
                () -> assertTrue(apply.contains("private long awaiterRetryInterval;")),
                () -> assertTrue(apply.contains("private Set<String> kubeFiltersStatefulesetSpecAllowed;")),
                () -> assertTrue(apply.contains(
                        "@Parameter(property = \"bundlebee.kube.kubeconfig\", defaultValue = \"auto\")")),
                () -> assertFalse(apply.contains("@Parameter(property = \"kubeconfig\"")),
                () -> assertTrue(
                        apply.contains(
                                "\"--bundlebee-awaiter-retryInterval\",\n                                String.valueOf(awaiterRetryInterval)")),
                () -> assertTrue(newBundle.contains("@Mojo(name = \"new\", requiresProject = true")),
                () -> assertTrue(newBundle.contains("defaultValue = \"${project.build.outputDirectory}\"")),
                () -> assertTrue(
                        add.contains("defaultValue = \"${project.groupId}:${project.artifactId}:${project.version}\"")),
                () -> assertTrue(
                        add.contains("defaultValue = \"${project.build.outputDirectory}/bundlebee/manifest.json\"")),
                () -> assertTrue(build.contains("defaultValue = \"${project.artifactId}\"")),
                () -> assertFalse(build.contains("awaiterRetryInterval")),
                () -> assertTrue(
                        process.contains("@Parameter(property = \"bundlebee.manifest\", defaultValue = \"skip\")")),
                () -> assertTrue(process.contains("\"skip\".equals(manifest) ? defaultManifest : manifest")),
                () -> assertTrue(process.contains("toArgs(customPlaceholders)")));
        for (final var name : List.of(
                "Cipher", "Decipher", "ListAlveoli", "ListLintRules", "Version", "AddAlveolus", "New", "Build")) {
            assertFalse(Files.readString(mojos.resolve(name + "Mojo.java")).contains("private String kubeApi;"), name);
        }
    }
}
