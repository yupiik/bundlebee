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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecipeCompatibilityTest {
    @TempDir
    Path temporary;

    @Test
    void builtInRecipesRetainRenderedResources() throws Exception {
        final var baseline = System.getProperty("bundlebee.baseline.jar");
        final var rootValue = System.getProperty("bundlebee.root");
        assumeTrue(
                baseline != null && rootValue != null,
                "Set bundlebee.baseline.jar and bundlebee.root for differential recipe execution");
        final var root = Path.of(rootValue).toAbsolutePath();
        final Path current;
        try (final var jars = Files.list(root.resolve("bundlebee-core/target"))) {
            current = jars.filter(path -> path.getFileName().toString().endsWith("-fat.jar"))
                    .findFirst()
                    .orElseThrow();
        }
        final List<Path> manifests;
        try (final var recipes = Files.list(root.resolve("alveolus"))) {
            manifests = recipes.map(path -> path.resolve("target/classes/bundlebee/manifest.json"))
                    .filter(Files::exists)
                    .sorted()
                    .toList();
        }
        assertEquals(9, manifests.size(), "All built-in recipes must be built before comparison");
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(JsonMapper.class)) {
            for (final var manifest : manifests) {
                final var expected = render(Path.of(baseline), manifest, lookup.instance());
                final var actual = render(current, manifest, lookup.instance());
                assertFalse(expected.isEmpty(), () -> "No resources rendered from " + manifest);
                // Independent resources may be emitted concurrently. Retain duplicate resources
                // and all JSON scalar/array semantics while ignoring only root emission order.
                final var remaining = new ArrayList<>(actual);
                for (final var resource : expected) {
                    assertTrue(
                            remaining.remove(resource),
                            () -> "Missing or changed resource from " + manifest + ": " + resource);
                }
                assertTrue(remaining.isEmpty(), () -> "Additional resources from " + manifest + ": " + remaining);
            }
        }
    }

    private List<Map<?, ?>> render(final Path jar, final Path manifest, final JsonMapper mapper) throws Exception {
        final var output = Files.createTempFile(temporary, "recipe-", ".out");
        final var error = Files.createTempFile(temporary, "recipe-", ".err");
        final var builder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Duser.home=" + temporary,
                        "-Dmaven.repo.local="
                                + System.getProperty(
                                        "bundlebee.fixture.repository",
                                        Path.of(System.getProperty("user.home"), ".m2", "repository")
                                                .toString()),
                        "-D.handlers=io.yupiik.logging.jul.handler.StandardHandler",
                        "-Dio.yupiik.bundlebee.level=SEVERE",
                        "-jar",
                        jar.toString(),
                        "process",
                        "--from",
                        manifest.getParent().getParent().toString(),
                        "--manifest",
                        manifest.toString(),
                        "--alveolus",
                        "auto",
                        "--output",
                        "stdout",
                        "--injectTimestamp",
                        "false",
                        "--injectBundleBeeMetadata",
                        "false",
                        "--kubeconfig",
                        "explicit")
                .directory(temporary.toFile())
                .redirectOutput(output.toFile())
                .redirectError(error.toFile());
        builder.environment().clear();
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Recipe subprocess timed out: " + manifest);
            assertEquals(0, process.exitValue(), () -> read(error));
            final var resources = new ArrayList<Map<?, ?>>();
            for (final var line : Files.readAllLines(output)) {
                if (!line.isBlank()) {
                    assertTrue(line.startsWith("{"), () -> "Non-JSON stdout: " + line);
                    resources.add(mapper.fromString(Map.class, line));
                }
            }
            return resources;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Recipe subprocess did not terminate");
            }
        }
    }

    private String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final Exception error) {
            return error.toString();
        }
    }
}
