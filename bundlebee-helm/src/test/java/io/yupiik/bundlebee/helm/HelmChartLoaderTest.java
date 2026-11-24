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
package io.yupiik.bundlebee.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.lang.spi.BundleBeeResources;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class HelmChartLoaderTest {

    private final Yaml yaml = new Yaml();

    @Test
    void loadLocalChart(@TempDir final Path tempDir) throws Exception {
        // Create a simple chart structure
        final var chartDir = tempDir.resolve("my-chart");
        Files.createDirectories(chartDir.resolve("templates"));

        // Chart.yaml
        Files.writeString(chartDir.resolve("Chart.yaml"), "name: my-chart\nversion: 1.0.0\nappVersion: 1.0.0\n");

        // values.yaml
        Files.writeString(
                chartDir.resolve("values.yaml"), "replicaCount: 1\nimage:\n  repository: nginx\n  tag: latest\n");

        // Template
        Files.writeString(
                chartDir.resolve("templates/deployment.yaml"),
                "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: {{ .Release.Name }}\n");

        final var loader = new HelmChartLoader(null, Configuration.of(Map.of()));
        final var chart = loader.load(chartDir).toCompletableFuture().get();

        assertEquals("my-chart", chart.getName());
        assertEquals("1.0.0", chart.getVersion());
        assertEquals("1.0.0", chart.getAppVersion());
        assertNotNull(chart.getValues());
        assertEquals(1, chart.getValues().get("replicaCount"));
        assertTrue(chart.getTemplates().containsKey("deployment.yaml"));
    }

    @Test
    void ignoredDescriptorsFilterTemplates(@TempDir final Path tempDir) throws Exception {
        final var chartDir = tempDir.resolve("my-chart");
        Files.createDirectories(chartDir.resolve("templates"));

        Files.writeString(chartDir.resolve("Chart.yaml"), "name: my-chart\nversion: 1.0.0\n");

        Files.writeString(chartDir.resolve("templates/deployment.yaml"), "apiVersion: apps/v1\nkind: Deployment\n");

        Files.writeString(chartDir.resolve("templates/NOTES.txt"), "Thank you for installing {{ .Chart.Name }}.\n");

        Files.writeString(chartDir.resolve("templates/service.yaml"), "apiVersion: v1\nkind: Service\n");

        final var loader = new HelmChartLoader(null, Configuration.of(Map.of()));
        final var chart = loader.load(chartDir, null, null, true, List.of("NOTES.txt"), true)
                .toCompletableFuture()
                .get();

        assertTrue(chart.getTemplates().containsKey("deployment.yaml"));
        assertTrue(chart.getTemplates().containsKey("service.yaml"));
        assertFalse(
                chart.getTemplates().containsKey("NOTES.txt"),
                "NOTES.txt should be filtered out by ignoredDescriptors");
    }

    @Test
    void ignoredDescriptorsWithRelativePath(@TempDir final Path tempDir) throws Exception {
        final var chartDir = tempDir.resolve("my-chart");
        Files.createDirectories(chartDir.resolve("templates/subdir"));

        Files.writeString(chartDir.resolve("Chart.yaml"), "name: my-chart\nversion: 1.0.0\n");

        Files.writeString(chartDir.resolve("templates/deployment.yaml"), "apiVersion: apps/v1\nkind: Deployment\n");

        Files.writeString(chartDir.resolve("templates/subdir/config.yaml"), "apiVersion: v1\nkind: ConfigMap\n");

        final var loader = new HelmChartLoader(null, Configuration.of(Map.of()));
        final var chart = loader.load(chartDir, null, null, true, List.of("templates/subdir/config.yaml"), true)
                .toCompletableFuture()
                .get();

        assertTrue(chart.getTemplates().containsKey("deployment.yaml"));
        assertFalse(chart.getTemplates().containsKey("config.yaml"), "config.yaml should be filtered by relative path");
    }

    @Test
    void resolveDependenciesDisabled(@TempDir final Path tempDir) throws Exception {
        final var chartDir = tempDir.resolve("my-chart");
        Files.createDirectories(chartDir.resolve("templates"));

        Files.writeString(
                chartDir.resolve("Chart.yaml"),
                "name: my-chart\nversion: 1.0.0\ndependencies:\n  - name: subchart\n    version: 1.0.0\n    repository: https://charts.example.com\n");

        Files.writeString(chartDir.resolve("templates/deployment.yaml"), "apiVersion: apps/v1\nkind: Deployment\n");

        final var loader = new HelmChartLoader(null, Configuration.of(Map.of()));
        final var chart = loader.load(chartDir, null, null, false, null, false)
                .toCompletableFuture()
                .get();

        assertEquals("my-chart", chart.getName());
        // charts/ directory should not be created when dependencies are disabled
        assertFalse(
                Files.isDirectory(chartDir.resolve("charts")),
                "charts/ directory should not exist when dependencies are disabled");
    }

    @Test
    void loadChartWithNullValues(@TempDir final Path tempDir) throws Exception {
        final var chartDir = tempDir.resolve("my-chart");
        Files.createDirectories(chartDir.resolve("templates"));

        Files.writeString(chartDir.resolve("Chart.yaml"), "name: my-chart\nversion: 1.0.0\n");

        Files.writeString(chartDir.resolve("templates/deployment.yaml"), "apiVersion: apps/v1\nkind: Deployment\n");

        final var loader = new HelmChartLoader(null, Configuration.of(Map.of()));
        final var chart = loader.load(chartDir).toCompletableFuture().get();

        assertNotNull(chart.getValues());
        assertTrue(chart.getValues().isEmpty());
    }

    @Test
    void loadChartWithEmptyTemplates(@TempDir final Path tempDir) throws Exception {
        final var chartDir = tempDir.resolve("my-chart");
        Files.createDirectories(chartDir.resolve("templates"));

        Files.writeString(chartDir.resolve("Chart.yaml"), "name: my-chart\nversion: 1.0.0\n");

        final var loader = new HelmChartLoader(null, Configuration.of(Map.of()));
        final var chart = loader.load(chartDir).toCompletableFuture().get();

        assertNotNull(chart.getTemplates());
        assertTrue(chart.getTemplates().isEmpty());
    }

    @Test
    void dependencyFailureConfigurationUsesOriginalKey(@TempDir final Path chartDir) throws Exception {
        Files.writeString(
                chartDir.resolve("Chart.yaml"),
                "name: parent\nversion: 1.0.0\ndependencies:\n"
                        + "  - name: missing\n    version: 1.0.0\n    repository: oci://unsupported\n");
        final var downloader = new HelmChartDownloader(id -> Optional.empty(), new BundleBeeResources() {
            @Override
            public HttpClient httpClient() {
                return null; // OCI resolution does not use HTTP.
            }

            @Override
            public ScheduledExecutorService scheduler() {
                return null; // No asynchronous work is scheduled for OCI.
            }
        });
        final var tolerant = new HelmChartLoader(downloader, Configuration.of(Map.of()));
        assertEquals(
                "parent", tolerant.load(chartDir).toCompletableFuture().join().getName());
        final var explicitlyTolerant = new HelmChartLoader(
                downloader, Configuration.of(Map.of("bundlebee.helm.failOnDependencyError", "false")));
        assertEquals(
                "parent",
                explicitlyTolerant.load(chartDir).toCompletableFuture().join().getName());
        final var strict = new HelmChartLoader(
                downloader, Configuration.of(Map.of("bundlebee.helm.failOnDependencyError", "true")));
        assertThrows(
                CompletionException.class,
                () -> strict.load(chartDir).toCompletableFuture().join());
    }

    @Test
    void dependencyFlagsAndFirstLoadTemplates(@TempDir final Path tempDir) throws Exception {
        for (final var publicFlag : new Boolean[] {null, false, true}) {
            for (final var recursiveFlag : new boolean[] {false, true}) {
                final var root = Files.createDirectories(tempDir.resolve(publicFlag + "-" + recursiveFlag));
                final var chartDir = Files.createDirectories(root.resolve("parent/templates"))
                        .getParent();
                Files.writeString(
                        chartDir.resolve("Chart.yaml"),
                        "name: parent\nversion: 1.0.0\ndependencies:\n"
                                + "  - name: child\n    version: 2.0.0\n    repository: https://example.invalid\n");
                Files.writeString(chartDir.resolve("templates/parent.yaml"), "parent");
                // Already vendored templates remain available even when resolution is disabled.
                final var vendored = Files.createDirectories(chartDir.resolve("charts/vendored/templates"));
                Files.writeString(vendored.resolve("existing.yaml"), "existing");
                final var downloaded = Files.createDirectories(root.resolve("download/templates"))
                        .getParent();
                Files.writeString(downloaded.resolve("templates/child.yaml"), "child");
                Files.writeString(downloaded.resolve("templates/ignored.yaml"), "ignored");
                final var resolved = new CompletableFuture<Path>();
                final var calls = new ArrayList<String>();
                final var downloader =
                        new HelmChartDownloader(null, new BundleBeeResources() {
                            @Override
                            public HttpClient httpClient() {
                                return null; // Stubbed resolution never uses HTTP.
                            }

                            @Override
                            public ScheduledExecutorService scheduler() {
                                return null; // Completion is controlled by the test.
                            }
                        }) {
                            @Override
                            public CompletionStage<Path> resolve(
                                    final String uri, final String username, final String password) {
                                calls.add(uri);
                                assertEquals("user", username);
                                assertEquals("password", password);
                                return resolved;
                            }
                        };
                final var loader = new HelmChartLoader(downloader, Configuration.of(Map.of()));
                final var result = loader.load(
                                chartDir,
                                "user",
                                "password",
                                publicFlag,
                                List.of("charts/child/templates/ignored.yaml"),
                                recursiveFlag)
                        .toCompletableFuture();
                final var enabled = recursiveFlag && !Boolean.FALSE.equals(publicFlag);
                assertEquals(enabled ? List.of("https://example.invalid/child-2.0.0.tgz") : List.of(), calls);
                if (enabled) {
                    assertFalse(result.isDone(), "template loading must await the download");
                    resolved.complete(downloaded);
                }
                final var chart = result.join();
                final var expected = new LinkedHashMap<>(
                        Map.of("parent.yaml", "parent", "charts/vendored/existing.yaml", "existing"));
                if (enabled) {
                    expected.put("charts/child/child.yaml", "child");
                }
                assertEquals(expected, chart.getTemplates());
                assertEquals(enabled, Files.exists(chartDir.resolve("charts/child/templates/child.yaml")));
                // A second load uses the vendored copy rather than downloading again.
                assertEquals(
                        expected,
                        loader.load(
                                        chartDir,
                                        "user",
                                        "password",
                                        publicFlag,
                                        List.of("charts/child/templates/ignored.yaml"),
                                        recursiveFlag)
                                .toCompletableFuture()
                                .join()
                                .getTemplates());
                assertEquals(enabled ? 1 : 0, calls.size());
            }
        }
    }

    private byte[] createChartTgz(final Map<String, String> files) throws IOException {
        final var baos = new ByteArrayOutputStream();
        try (final var gzos = new GZIPOutputStream(baos)) {
            // Use reflection to create TarArchiveOutputStream
            // For simplicity, we'll just create a simple tar-like structure
            // In real tests, we'd use commons-compress
            final var tarBytes = createTarBytes(files);
            gzos.write(tarBytes);
        }
        return baos.toByteArray();
    }

    private byte[] createTarBytes(final Map<String, String> files) throws IOException {
        // Simple tar format for testing
        // Each file entry: 100 bytes name + 12 bytes size + file content
        final var baos = new ByteArrayOutputStream();
        for (final var entry : files.entrySet()) {
            final var nameBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            final var contentBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);

            // Pad name to 100 bytes
            baos.write(nameBytes, 0, Math.min(nameBytes.length, 100));
            if (nameBytes.length < 100) {
                baos.write(new byte[100 - nameBytes.length]);
            }

            // Write size (12 bytes, octal string)
            final var sizeStr = String.format("%011o", contentBytes.length);
            baos.write(sizeStr.getBytes(StandardCharsets.UTF_8));

            // Write content
            baos.write(contentBytes);

            // Pad to 512-byte boundary
            final var totalSize = 100 + 12 + contentBytes.length;
            final var padding = (512 - (totalSize % 512)) % 512;
            baos.write(new byte[padding]);
        }
        return baos.toByteArray();
    }
}
