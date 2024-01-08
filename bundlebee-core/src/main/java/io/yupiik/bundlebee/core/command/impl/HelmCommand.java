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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import lombok.Data;
import lombok.Getter;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.chain;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class HelmCommand extends BaseLabelEnricherCommand implements CompletingExecutable {
    @Inject
    @Description("Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.helm.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to deploy (a file path or inline). This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.helm.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.helm.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("" +
            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. " +
            "Note that using this setting, location is set to `*` so only the name is matched.")
    @ConfigProperty(name = "bundlebee.helm.excludedDescriptors", defaultValue = "none")
    private String excludedDescriptors;

    @Inject
    @Description("" +
            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
    @ConfigProperty(name = "bundlebee.helm.excludedLocations", defaultValue = "none")
    private String excludedLocations;

    @Inject
    @Description("Where to create helm chart.")
    @ConfigProperty(name = "bundlebee.helm.output", defaultValue = "none")
    private String output;

    @Inject
    @Description("Helm chart name, if `auto` it will be the alveolus name if a single one is selected else it is `alveolus`.")
    @ConfigProperty(name = "bundlebee.helm.chart.name", defaultValue = "auto")
    private String chartName;

    @Inject
    @Description("Helm chart description.")
    @ConfigProperty(name = "bundlebee.helm.chart.description", defaultValue = UNSET)
    private String chartDescription;

    @Inject
    @Description("Helm chart version.")
    @ConfigProperty(name = "bundlebee.helm.chart.version", defaultValue = UNSET)
    private String chartVersion;

    @Inject
    @Description("Helm chart app version.")
    @ConfigProperty(name = "bundlebee.helm.chart.appVersion", defaultValue = UNSET)
    private String chartAppVersion;

    @Inject
    @Description("Should `-SNAPSHOT` be removed from the version if present.")
    @ConfigProperty(name = "bundlebee.helm.chart.dropSnapshot", defaultValue = "true")
    private boolean dropSnapshot;

    @Inject
    @Description("An optional file path to a properties file where the keys are placeholders and the value some description to inject in `values.yaml`.")
    @ConfigProperty(name = "bundlebee.helm.placeholderDescriptions", defaultValue = "true")
    private String placeholderDescriptions;

    @Inject
    private KubeClient kube;

    @Inject
    private ArchiveReader archives;

    @Inject
    private HelmChartSpy placeholderObserver;

    @Override
    public String name() {
        return "helm";
    }

    @Override
    public boolean hidden() {
        return true; // is it official?
    }

    @Override
    public String description() {
        return "Converts an alveolus deployment to a simple helm chart.\"" +
                "// end of short description\n" +
                "Note you will loose some features doing that but in case some rigid rules force to use helm it enables to " +
                "still rely on bundlebee setup and share a helm chart to ops.\n" +
                "Note that this conversion has some limitation in placeholder syntax for example (ensure no conflict under a key).";
    }

    private void doExport(final List<Manifest.Alveolus> foundAlveoli, final String id) {
        final var root = Path.of(output);

        final var placeholders = new ArrayList<>(placeholderObserver.getSpies().get(id).getEvents());
        final var placeholderLoadedDescriptions = new Properties();

        try {
            Files.createDirectories(root);

            final var props = Path.of(this.placeholderDescriptions);
            if (Files.exists(props)) {
                try (final var in = Files.newBufferedReader(props)) {
                    placeholderLoadedDescriptions.load(in);
                }
            }

            final var name = "auto".equals(chartName) ?
                    (foundAlveoli.size() == 1 ? foundAlveoli.get(0).getName() : "alveolus") :
                    chartName;
            Files.writeString(root.resolve("Chart.yaml"), chart(name));
            Files.writeString(root.resolve(".helmignore"), helmIgnore());
            Files.writeString(root.resolve("values.yaml"), valuesFor(placeholders, placeholderLoadedDescriptions).strip() + '\n');

            // todo: support charts creating nested charts == alveoli?
            //       -> technically it would be as easy as doing this logic per alveolus in the visitor instead of globally
            final var charts = Files.createDirectories(root.resolve("templates"));
            final var descriptors = placeholderObserver.getSpies().get(id).getDescriptors();
            for (final var entry : descriptors) {
                copyDescriptor(entry.getContent(), charts.resolve(entry.getAlveolus() + "." + stripExtension(entry.getDescriptor()) + ".yaml"), id);
            }
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    private String stripExtension(final String descriptor) {
        if (descriptor.endsWith(".yaml")) {
            return descriptor.substring(descriptor.length() - ".yaml".length());
        }
        if (descriptor.endsWith(".yml")) {
            return descriptor.substring(descriptor.length() - ".yml".length());
        }
        if (descriptor.endsWith(".json")) {
            return descriptor.substring(descriptor.length() - ".json".length());
        }
        return descriptor;
    }

    private void copyDescriptor(final String value, final Path target, final String id) throws IOException {
        final var helmContent = new Substitutor(k -> "@{@{@ .Values." + k + " @}@}@")
                .replace(value, id)
                .replace("@{@{@", "{{")
                .replace("@}@}@", "}}");
        Files.writeString(target, helmContent);
    }

    private String valuesFor(final List<OnPlaceholder> placeholders,
                             final Properties placeholderDescriptions) {
        final var splitter = Pattern.compile("\\.");
        final var keyParsed = placeholders.stream()
                .map(it -> new Placeholder(splitter.split(it.getName()), it, placeholderDescriptions.getProperty(it.getName(), "")))
                .distinct()
                .collect(toList());
        return valuesAtLevel("", keyParsed, placeholderDescriptions);
    }

    private String valuesAtLevel(final String indentPrefix, final List<Placeholder> placeholders, final Properties placeholderDescriptions) {
        final var grouped = placeholders.stream()
                .collect(groupingBy(it -> it.getName()[0]));
        final var leaves = grouped.entrySet().stream()
                .filter(it -> it.getValue().size() == 1 /* if > 1 we ignore for now */ && it.getValue().get(0).getName().length == 1)
                .sorted(Map.Entry.comparingByKey())
                .map(it -> {
                    final var item = it.getValue().get(0);
                    final var value = findValue(item);
                    return "" +
                            generatePlaceholderDesc(indentPrefix, item.getComment()) +
                            indentPrefix + (value != null ? "" : "#") +
                            item.getName()[0] + ": \"" + item.getPlaceholder().getDefaultValue() + "\"";
                })
                .collect(joining("\n"));
        final var unknownLeaves = grouped.entrySet().stream()
                .filter(it -> it.getValue().size() > 1 && it.getValue().get(0).getName().length == 1)
                .sorted(Map.Entry.comparingByKey())
                .map(it -> {
                    final var item = it.getValue().get(0);
                    final var value = findValue(item);
                    return "" +
                            generatePlaceholderDesc(indentPrefix, item.getComment()) +
                            indentPrefix + "# IMPORTANT: this is an available placeholder but it is not used a single time so you can't globally configure it\n" +
                            indentPrefix + "# " + item.getName()[0] + ": " + value;
                })
                .collect(joining("\n"));
        final var nested = grouped.entrySet().stream()
                .filter(it -> it.getValue().get(0).getName().length > 1)
                .sorted(Map.Entry.comparingByKey())
                .map(it -> {
                    final var nestedConfigs = valuesAtLevel(indentPrefix + "  ", it.getValue().stream()
                            .map(p -> new Placeholder(
                                    Stream.of(p.getName()).skip(1).toArray(String[]::new),
                                    p.getPlaceholder(),
                                    p.getComment()))
                            .collect(toList()), placeholderDescriptions);
                    return generatePlaceholderDesc(indentPrefix, placeholderDescriptions.getProperty(it.getKey(), "")) +
                            indentPrefix + it.getKey() + ":" + (nestedConfigs.isBlank() ? " {}" : ('\n' + nestedConfigs));
                })
                .collect(joining("\n"));
        return Stream.of(leaves, unknownLeaves, nested).filter(Predicate.not(String::isBlank)).collect(joining("\n")) + '\n';
    }

    private static String findValue(final Placeholder item) {
        return item.getPlaceholder().getDefaultValue() != null && !item.getPlaceholder().getDefaultValue().isBlank() ?
                '"' + item.getPlaceholder().getDefaultValue() + '"' :
                (item.getPlaceholder().getResolvedValue() != null && !item.getPlaceholder().getResolvedValue().isBlank() ?
                        '"' + item.getPlaceholder().getResolvedValue() + '"' :
                        null);
    }

    private static String generatePlaceholderDesc(final String indentPrefix, final String comment) {
        return !comment.isBlank() ? indentPrefix + "# " + comment.replace("\n", indentPrefix + "# ") + '\n' : "";
    }

    private String chart(final String name) {
        return "" +
                "apiVersion: v2\n" +
                "type: application\n" +
                "name: " + name + "\n" +
                "description: " + (UNSET.equals(chartDescription) ? "-" : chartDescription) + "\n" +
                "version: \"" + dropSnapshotIfNeeded(UNSET.equals(chartVersion) ? "1.0.0" : chartVersion) + "\"\n" +
                "appVersion: \"" + dropSnapshotIfNeeded(UNSET.equals(chartAppVersion) ? "1.0.0" : chartAppVersion) + "\"\n" +
                "";
    }

    private String helmIgnore() {
        return "" +
                ".DS_Store\n" +
                ".git/\n" +
                ".gitignore\n" +
                ".bzr/\n" +
                ".bzrignore\n" +
                ".hg/\n" +
                ".hgignore\n" +
                ".svn/\n" +
                "*.swp\n" +
                "*.bak\n" +
                "*.tmp\n" +
                "*.orig\n" +
                "*~\n" +
                ".project\n" +
                ".idea/\n" +
                "*.tmproj\n" +
                ".vscode/\n";
    }

    @Override
    public CompletionStage<?> execute() {
        final var id = UUID.randomUUID().toString();
        placeholderObserver.getSpies().put(id, new SpyCollector());
        return doExecute(from, manifest, alveolus, archives.newCache(), id)
                .whenComplete((ok, ko) -> placeholderObserver.getSpies().remove(id));
    }

    private String dropSnapshotIfNeeded(final String version) {
        return version.endsWith("-SNAPSHOT") ? version.substring(0, version.length() - "-SNAPSHOT".length()) : version;
    }

    private CompletionStage<?> doExecute(final String from, final String manifest, final String alveolus,
                                         final ArchiveReader.Cache cache, final String id) {
        final var foundAlveoli = new ArrayList<Manifest.Alveolus>();
        return visitor
                .findRootAlveoli(from, manifest, alveolus, id)
                .thenApply(alveoli -> alveoli.stream().map(it -> it.exclude(excludedLocations, excludedDescriptors)).collect(toList()))
                .thenCompose(alveoli -> {
                    foundAlveoli.addAll(alveoli.stream().map(AlveolusHandler.ManifestAndAlveolus::getAlveolus).collect(toList()));
                    return chain(alveoli.stream()
                            .map(it -> (Supplier<CompletionStage<?>>) () -> doExecute(cache, it, id))
                            .iterator(), true);

                })
                .thenRun(() -> doExport(foundAlveoli, id));
    }

    private CompletionStage<?> doExecute(final ArchiveReader.Cache cache,
                                         final AlveolusHandler.ManifestAndAlveolus it,
                                         final String id) {
        final var spyCollector = placeholderObserver.getSpies().get(id);
        it.getAlveolus().setChainDependencies(true); // forced to ensure we process everything - including placeholders - synchronously
        return visitor.executeOnceOnAlveolus(
                "Visiting", it.getManifest(), it.getAlveolus(), null,
                (ctx, desc) -> kube.forDescriptor("Visiting", desc.getContent(), desc.getExtension(), CompletableFuture::completedFuture),
                cache, desc -> {
                    spyCollector.getIgnoredKeys().clear();
                    return completedFuture(null);
                }, "visited", id);
    }

    @Getter
    @ApplicationScoped
    public static class HelmChartSpy {
        @Getter
        private Map<String, SpyCollector> spies = new ConcurrentHashMap<>();

        public void onDescriptor(@Observes final OnPrepareDescriptor onPrepareDescriptor) {
            if (onPrepareDescriptor.getId() == null) {
                return;
            }
            final var spy = spies.get(onPrepareDescriptor.getId());
            if (spy == null) {
                return;
            }
            synchronized (spy.descriptors) {
                spy.descriptors.add(onPrepareDescriptor);
                spy.ignoredKeys.addAll(onPrepareDescriptor.getPlaceholders().keySet());
            }
        }

        public void onPlaceholder(@Observes final OnPlaceholder onPlaceholder) {
            if (onPlaceholder.getId() == null) {
                return;
            }
            final var spy = spies.get(onPlaceholder.getId());
            if (spy == null) {
                return;
            }
            if (spy.ignoredKeys.contains(onPlaceholder.getName())) {
                return;
            }
            synchronized (spy.events) {
                spy.events.add(onPlaceholder);
            }
        }
    }

    @Data
    private static class SpyCollector {
        private final Collection<String> ignoredKeys = new HashSet<>();
        private final Collection<OnPlaceholder> events = new ArrayList<>();
        private final Collection<OnPrepareDescriptor> descriptors = new ArrayList<>();
    }

    @Data
    private static class Placeholder {
        private final String[] name;
        private final OnPlaceholder placeholder;
        private final String comment;
    }
}
