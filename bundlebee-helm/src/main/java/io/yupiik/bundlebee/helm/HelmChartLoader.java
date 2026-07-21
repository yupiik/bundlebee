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

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.lang.CompletionFutures;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * Loads a local or remote Helm chart directory into a {@link HelmChart} model.
 * Supports resolving Chart.yaml dependencies and filtering ignored descriptors.
 */
@Log
@Dependent
public class HelmChartLoader {

    @Inject
    private HelmChartDownloader downloader;

    @Inject
    @ConfigProperty(name = "bundlebee.helm.failOnDependencyError", defaultValue = "false")
    @Description("If true, fails when a Helm dependency cannot be resolved.")
    private boolean failOnDependencyError;

    /**
     * Load a chart from a local path.
     *
     * @param chartDir the chart directory
     * @return a future resolving to the loaded chart
     */
    public CompletionStage<HelmChart> load(final Path chartDir) {
        return load(chartDir, null, null, null, null, true);
    }

    /**
     * Load a chart with full configuration.
     *
     * @param chartDir           the chart directory (local or resolved from URI)
     * @param username           optional username for authentication
     * @param password           optional password (can use maven:serverId syntax)
     * @param resolveDependencies whether to resolve Chart.yaml dependencies
     * @param ignoredDescriptors  list of template paths to ignore
     * @return a future resolving to the loaded chart
     */
    public CompletionStage<HelmChart> load(final Path chartDir, final String username, final String password,
                                           final Boolean resolveDependencies, final List<String> ignoredDescriptors,
                                           final boolean resolveDeps) {
        try {
            if (!Files.isDirectory(chartDir)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Chart path is not a directory: " + chartDir));
            }
            final var chart = new HelmChart();
            final var yaml = new Yaml();

            // Parse Chart.yaml
            final var chartYaml = chartDir.resolve("Chart.yaml");
            Map<String, Object> chartMeta = null;
            if (Files.exists(chartYaml)) {
                try (final var in = Files.newInputStream(chartYaml)) {
                    chartMeta = yaml.load(in);
                    chart.setName((String) chartMeta.get("name"));
                    chart.setVersion((String) chartMeta.get("version"));
                    chart.setAppVersion((String) chartMeta.get("appVersion"));
                } catch (final IOException e) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Failed to read Chart.yaml: " + chartYaml, e));
                }
            }

            // Parse values.yaml
            final var valuesYaml = chartDir.resolve("values.yaml");
            if (Files.exists(valuesYaml)) {
                try (final var in = Files.newInputStream(valuesYaml)) {
                    chart.setValues(yaml.load(in));
                } catch (final IOException e) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Failed to read values.yaml: " + valuesYaml, e));
                }
            } else {
                chart.setValues(new LinkedHashMap<>());
            }

            final Set<String> ignored = ignoredDescriptors != null ? Set.copyOf(ignoredDescriptors) : Set.of();
            loadTemplates(chartDir, chart, ignored);

            // Chain with dependency resolution if enabled
            if (resolveDeps && chartMeta != null) {
                return resolveDependencies(chartDir, chartMeta, username, password)
                        .thenApply(ignoredResult -> chart);
            }

            return completedFuture(chart);
        } catch (final RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void loadTemplates(final Path chartDir, final HelmChart chart, final Set<String> ignored) {
        final var templates = new LinkedHashMap<String, String>();
        final var defines = new LinkedHashMap<String, String>();
        loadMainTemplates(chartDir.resolve("templates"), chartDir, templates, ignored);
        // Load subchart templates from charts/ directory
        final var chartsDir = chartDir.resolve("charts");
        if (Files.isDirectory(chartsDir)) {
            try (final var subchartDirs = Files.list(chartsDir)) {
                subchartDirs
                        .filter(Files::isDirectory)
                        .sorted()
                        .forEach(subchart -> {
                            final var subchartName = subchart.getFileName().toString();
                            final var prefix = "charts/" + subchartName + "/";
                            loadSubchartTemplates(subchart.resolve("templates"), chartDir, prefix, templates, ignored);
                        });
            } catch (final IOException e) {
                throw new IllegalStateException("Failed to list charts directory: " + chartsDir, e);
            }
        }
        chart.setTemplates(templates);
        chart.setDefines(defines);
    }

    private void loadMainTemplates(final Path templatesDir, final Path chartDir,
                                    final Map<String, String> templates, final Set<String> ignored) {
        if (!Files.isDirectory(templatesDir)) {
            return;
        }
        try (final var stream = Files.walk(templatesDir)) {
            stream.sorted()
                    .filter(p -> {
                        final var name = p.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".tpl");
                    })
                    .forEach(p -> {
                        try {
                            final var relativePath = chartDir.relativize(p).toString().replace('\\', '/');
                            if (ignored.contains(relativePath) || ignored.contains(p.getFileName().toString())) {
                                log.fine("Ignoring template: " + relativePath);
                                return;
                            }
                            final var content = Files.readString(p);
                            templates.put(p.getFileName().toString(), content);
                        } catch (final IOException e) {
                            throw new IllegalStateException("Failed to read template: " + p, e);
                        }
                    });
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to list templates directory: " + templatesDir, e);
        }
    }

    private void loadSubchartTemplates(final Path templatesDir, final Path chartDir,
                                        final String prefix, final Map<String, String> templates,
                                        final Set<String> ignored) {
        if (!Files.isDirectory(templatesDir)) {
            return;
        }
        try (final var stream = Files.walk(templatesDir)) {
            stream.sorted()
                    .filter(p -> {
                        final var name = p.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".tpl");
                    })
                    .forEach(p -> {
                        try {
                            final var relativePath = chartDir.relativize(p).toString().replace('\\', '/');
                            if (ignored.contains(relativePath) || ignored.contains(p.getFileName().toString())) {
                                log.fine("Ignoring template: " + relativePath);
                                return;
                            }
                            final var content = Files.readString(p);
                            templates.put(prefix + p.getFileName().toString(), content);
                        } catch (final IOException e) {
                            throw new IllegalStateException("Failed to read subchart template: " + p, e);
                        }
                    });
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to list subchart templates directory: " + templatesDir, e);
        }
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<Void> resolveDependencies(final Path chartDir, final Map<String, Object> chartMeta,
                                                      final String username, final String password) {
        final var deps = chartMeta.get("dependencies");
        if (!(deps instanceof List)) {
            return completedFuture(null);
        }

        final var chartsDir = chartDir.resolve("charts");
        try {
            Files.createDirectories(chartsDir);
        } catch (final IOException e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Failed to create charts directory: " + chartsDir, e));
        }

        final var depFutures = new ArrayList<CompletionStage<Path>>();
        for (final var depObj : (List<Map<String, Object>>) deps) {
            final var depName = (String) depObj.get("name");
            final var depVersion = (String) depObj.get("version");
            final var depRepo = (String) depObj.get("repository");

            if (depName == null || depRepo == null) {
                log.warning("Skipping dependency with missing name or repository: " + depObj);
                continue;
            }

            log.info("Resolving dependency: " + depName + " " + depVersion + " from " + depRepo);

            // Check if dependency already exists in charts/ directory
            final var depDir = chartsDir.resolve(depName);
            if (Files.isDirectory(depDir)) {
                log.fine("Dependency already exists: " + depName);
                continue;
            }

            final var depUri = buildDependencyUri(depRepo, depName, depVersion);
            depFutures.add(
                    downloader.resolve(depUri, username, password)
                            .thenCompose(resolved -> {
                                try {
                                    copyDirectory(resolved, depDir);
                                    return completedFuture(resolved);
                                } catch (final Exception e) {
                                    return CompletableFuture.failedFuture(e);
                                }
                            }));
        }

        if (depFutures.isEmpty()) {
            return completedFuture(null);
        }

        final var allFutures = CompletionFutures.all(depFutures,
                java.util.stream.Collectors.<Path>toList(), failOnDependencyError);
        return allFutures.thenApply(r -> null);
    }

    private String buildDependencyUri(final String repo, final String name, final String version) {
        if (repo.startsWith("http://") || repo.startsWith("https://")) {
            final var base = repo.endsWith("/") ? repo : repo + "/";
            return base + name + "-" + version + ".tgz";
        }
        if (repo.startsWith("oci://")) {
            return repo + "/" + name + ":" + version;
        }
        throw new UnsupportedOperationException("Unsupported repository format: " + repo);
    }

    private void copyDirectory(final Path source, final Path target) {
        try {
            Files.walk(source).forEach(sourcePath -> {
                try {
                    final var targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (final IOException e) {
                    throw new RuntimeException("Failed to copy: " + sourcePath, e);
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to copy directory: " + source + " to " + target, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeValues(final Map<String, Object> base, final Map<String, Object> override) {
        final var result = new LinkedHashMap<>(base);
        for (final var entry : override.entrySet()) {
            if (entry.getValue() instanceof Map && base.containsKey(entry.getKey()) && base.get(entry.getKey()) instanceof Map) {
                result.put(entry.getKey(), mergeValues((Map<String, Object>) base.get(entry.getKey()), (Map<String, Object>) entry.getValue()));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
