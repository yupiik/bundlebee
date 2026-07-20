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

import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import lombok.extern.java.Log;
import org.yaml.snakeyaml.Yaml;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;

/**
 * Orchestrates Helm chart loading and rendering.
 * This is the main entry point for consuming Helm charts in BundleBee.
 */
@Log
@Dependent
public class HelmRenderer {

    @Inject
    private HelmFunctionProducer functionProducer;

    @Inject
    private HelmChartLoader chartLoader;

    @Inject
    private NamespaceProvider namespaceProvider;

    private final HelmHookSorter hookSorter = new HelmHookSorter(new Yaml());

    /**
     * Render a Helm chart and return the list of rendered YAML documents.
     *
     * @param chartPath    path to the local Helm chart directory
     * @param placeholders values to use for rendering (merged with chart's values.yaml)
     * @param releaseName  release name for the Helm context
     * @param releaseNamespace release namespace for the Helm context
     * @return a future resolving to the list of rendered YAML strings, one per template file
     */
    public CompletionStage<List<String>> render(final String chartPath, final Map<String, String> placeholders,
                                                 final String releaseName, final String releaseNamespace) {
        return render(chartPath, placeholders, releaseName, releaseNamespace, true, null);
    }

    /**
     * Render a Helm chart with full configuration.
     *
     * @param chartPath           path to the local Helm chart directory
     * @param placeholders        values to use for rendering (merged with chart's values.yaml)
     * @param releaseName         release name for the Helm context
     * @param releaseNamespace    release namespace for the Helm context
     * @param resolveDependencies whether to resolve Chart.yaml dependencies
     * @param ignoredDescriptors  list of template paths to ignore
     * @return a future resolving to the list of rendered YAML strings, one per template file
     */
    public CompletionStage<List<String>> render(final String chartPath, final Map<String, String> placeholders,
                                                 final String releaseName, final String releaseNamespace,
                                                 final Boolean resolveDependencies, final List<String> ignoredDescriptors) {
        return chartLoader.load(Path.of(chartPath), null, null, resolveDependencies, ignoredDescriptors, true)
                .thenApply(chart -> {
                    final var functions = functionProducer.getFunctions();
                    final var renderer = new HelmGoTemplateRenderer(functions);

                    // Merge chart values with placeholders
                    final var values = buildValues(chart.getValues(), placeholders);

                    // Build Helm context
                    final var context = new LinkedHashMap<String, Object>();
                    context.put("Values", values);
                    context.put("Release", Map.of(
                            "Name", releaseName != null ? releaseName : (chart.getName() != null ? chart.getName() : "RELEASE-NAME"),
                            "Namespace", releaseNamespace != null ? releaseNamespace : namespaceProvider.namespace(),
                            "IsInstall", true,
                            "IsUpgrade", false));
                    context.put("Chart", Map.of(
                            "Name", chart.getName(),
                            "Version", chart.getVersion(),
                            "AppVersion", chart.getAppVersion()));
                    context.put("Capabilities", Map.of(
                            "KubeVersion", Map.of(
                                    "Version", "v1.28.0",
                                    "GitCommit", "unknown",
                                    "GitTreeState", "clean")));
                    context.put("Template", Map.of(
                            "Name", "",
                            "BasePath", ""));

                    // Parse all templates
                    final var allNodes = new LinkedHashMap<String, List<HelmGoTemplateNode>>();
                    for (final var entry : chart.getTemplates().entrySet()) {
                        final var tokens = new HelmGoTemplateLexer().tokenize(entry.getValue());
                        final var nodes = new HelmGoTemplateParser(tokens).parse();
                        allNodes.put(entry.getKey(), nodes);
                    }

                    // Register defines from _helpers.tpl (main chart and subcharts)
                    for (final var entry : chart.getTemplates().entrySet()) {
                        if (entry.getKey().endsWith("_helpers.tpl")) {
                            final var helperTokens = new HelmGoTemplateLexer().tokenize(entry.getValue());
                            final var helperNodes = new HelmGoTemplateParser(helperTokens).parse();
                            renderer.registerDefines(helperNodes);
                        }
                    }

                    // Render each template
                    final var results = new ArrayList<String>();
                    for (final var entry : allNodes.entrySet()) {
                        if (entry.getKey().startsWith("_")) {
                            continue;
                        }
                        try {
                            final var rendered = renderer.render(entry.getValue(), context);
                            final var trimmed = rendered.strip();
                            if (!trimmed.isEmpty()) {
                                results.add(trimmed);
                            }
                        } catch (final Exception e) {
                            log.log(Level.WARNING, "Failed to render template '" + entry.getKey() + "': " + e.getMessage(), e);
                            log.log(Level.WARNING, "Stack trace:", e);
                        }
                    }

                    return hookSorter.sort(results);
                });
    }

    /**
     * Render a Helm chart using descriptor configuration.
     */
    public CompletionStage<List<String>> render(final String chartPath, final Map<String, String> placeholders,
                                                 final String releaseName) {
        return render(chartPath, placeholders, releaseName, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildValues(final Map<String, Object> chartValues, final Map<String, String> placeholders) {
        final var result = new LinkedHashMap<String, Object>();
        if (chartValues != null) {
            result.putAll(chartValues);
        }
        if (placeholders != null) {
            for (final var entry : placeholders.entrySet()) {
                setNestedValue(result, entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void setNestedValue(final Map<String, Object> map, final String key, final String value) {
        final var parts = key.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            final var existing = current.get(parts[i]);
            if (existing instanceof Map) {
                current = (Map<String, Object>) existing;
            } else {
                final var newMap = new LinkedHashMap<String, Object>();
                current.put(parts[i], newMap);
                current = newMap;
            }
        }
        current.put(parts[parts.length - 1], value);
    }
}
