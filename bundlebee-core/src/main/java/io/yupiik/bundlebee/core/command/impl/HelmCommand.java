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

import static io.yupiik.bundlebee.lang.CompletionFutures.chain;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "helm",
        description =
                "Converts an alveolus deployment to a simple helm chart.\n" + "// end of short description\n"
                        + "Note you will loose some features doing that but in case some rigid rules force to use helm it enables to "
                        + "still rely on bundlebee setup and share a helm chart to ops.\n"
                        + "Note that this conversion has some limitation in placeholder syntax for example (ensure no conflict under a key).")
public class HelmCommand extends BaseLabelEnricherCommand implements CompletingExecutable {
    private static final Pattern DOT = Pattern.compile("\\.");
    private final Logger log = Logger.getLogger(HelmCommand.class.getName());

    public HelmCommand(
            final Options options,
            KubeClient kube,
            ArchiveReader archives,
            HelmChartSpy placeholderObserver,
            io.yupiik.bundlebee.core.service.LabelSanitizerService labelSanitizerService,
            io.yupiik.bundlebee.core.service.VersioningService versioningService,
            io.yupiik.bundlebee.core.service.AlveolusHandler visitor) {
        super(labelSanitizerService, versioningService, visitor);
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.excludedDescriptors = options.excludedDescriptors();
        this.excludedLocations = options.excludedLocations();
        this.output = options.output();
        this.chartName = options.chartName();
        this.chartDescription = options.chartDescription();
        this.chartVersion = options.chartVersion();
        this.chartAppVersion = options.chartAppVersion();
        this.dropSnapshot = options.dropSnapshot();
        this.placeholderDescriptions = options.placeholderDescriptions();
        this.kube = kube;
        this.archives = archives;
        this.placeholderObserver = placeholderObserver;
    }

    private String alveolus;
    private String manifest;
    private String from;
    private String excludedDescriptors;
    private String excludedLocations;
    private String output;
    private String chartName;
    private String chartDescription;
    private String chartVersion;
    private String chartAppVersion;
    private boolean dropSnapshot;
    private String placeholderDescriptions;
    private KubeClient kube;
    private ArchiveReader archives;
    private HelmChartSpy placeholderObserver;

    @Override
    public String name() {
        return "helm";
    }

    @Override
    public boolean hidden() {
        // is it official?
        return true;
    }

    @Override
    public String description() {
        return "Converts an alveolus deployment to a simple helm chart.\n" + "// end of short description\n"
                + "Note you will loose some features doing that but in case some rigid rules force to use helm it enables to "
                + "still rely on bundlebee setup and share a helm chart to ops.\n"
                + "Note that this conversion has some limitation in placeholder syntax for example (ensure no conflict under a key).";
    }

    private void doExport(final List<Manifest.Alveolus> foundAlveoli, final String id) {
        final var root = Path.of(output);
        final var placeholders =
                new ArrayList<>(placeholderObserver.getSpies().get(id).getEvents());
        final var placeholderLoadedDescriptions = new Properties();
        try {
            Files.createDirectories(root);
            final var props = Path.of(this.placeholderDescriptions);
            if (Files.exists(props)) {
                try (var in = Files.newBufferedReader(props)) {
                    placeholderLoadedDescriptions.load(in);
                }
            }
            final var name = "auto".equals(chartName)
                    ? (foundAlveoli.size() == 1 ? foundAlveoli.get(0).name() : "alveolus")
                    : chartName;
            Files.writeString(root.resolve("Chart.yaml"), chart(name));
            Files.writeString(root.resolve(".helmignore"), helmIgnore());
            Files.writeString(
                    root.resolve("values.yaml"),
                    valuesFor(placeholders, placeholderLoadedDescriptions).strip() + '\n');
            // todo: support charts creating nested charts == alveoli?
            //       -> technically it would be as easy as doing this logic per alveolus in the visitor instead of
            // globally
            final var charts = Files.createDirectories(root.resolve("templates"));
            final var descriptors = placeholderObserver.getSpies().get(id).getDescriptors();
            for (final var entry : descriptors) {
                copyDescriptor(
                        entry.getContent(),
                        charts.resolve(entry.getAlveolus() + "." + stripExtension(entry.getDescriptor()) + ".yaml"),
                        id);
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

    private String valuesFor(final List<OnPlaceholder> placeholders, final Properties placeholderDescriptions) {
        final var keyParsed = placeholders.stream()
                .map(it -> new Placeholder(
                        DOT.split(it.getName()), it, placeholderDescriptions.getProperty(it.getName(), "")))
                .distinct()
                .collect(toList());
        return valuesAtLevel("", keyParsed, placeholderDescriptions);
    }

    private String valuesAtLevel(
            final String indentPrefix, final List<Placeholder> placeholders, final Properties placeholderDescriptions) {
        final var grouped = placeholders.stream().collect(groupingBy(it -> it.getName()[0]));
        final var leaves = /* if > 1 we ignore for now */ grouped.entrySet().stream()
                .filter(it -> it.getValue().size() == 1 && it.getValue().get(0).getName().length == 1)
                .sorted(Map.Entry.comparingByKey())
                .map(it -> {
                    final var item = it.getValue().get(0);
                    final var value = findValue(item);
                    return generatePlaceholderDesc(indentPrefix, item.getComment()) + indentPrefix
                            + (value != null ? "" : "#") + item.getName()[0] + ": \""
                            + item.getPlaceholder().getDefaultValue() + "\"";
                })
                .collect(joining("\n"));
        final var unknownLeaves = grouped.entrySet().stream()
                .filter(it -> it.getValue().size() > 1 && it.getValue().get(0).getName().length == 1)
                .sorted(Map.Entry.comparingByKey())
                .map(it -> {
                    final var item = it.getValue().get(0);
                    final var value = findValue(item);
                    return generatePlaceholderDesc(indentPrefix, item.getComment()) + indentPrefix
                            + "# IMPORTANT: this is an available placeholder but it is not used a single time so you can\'t globally configure it\n"
                            + indentPrefix + "# " + item.getName()[0] + ": " + value;
                })
                .collect(joining("\n"));
        final var nested = grouped.entrySet().stream()
                .filter(it -> it.getValue().get(0).getName().length > 1)
                .sorted(Map.Entry.comparingByKey())
                .map(it -> {
                    final var nestedConfigs = valuesAtLevel(
                            indentPrefix + "  ",
                            it.getValue().stream()
                                    .map(p -> new Placeholder(
                                            Stream.of(p.getName()).skip(1).toArray(String[]::new),
                                            p.getPlaceholder(),
                                            p.getComment()))
                                    .collect(toList()),
                            placeholderDescriptions);
                    return generatePlaceholderDesc(indentPrefix, placeholderDescriptions.getProperty(it.getKey(), ""))
                            + indentPrefix + it.getKey() + ":"
                            + (nestedConfigs.isBlank() ? " {}" : ('\n' + nestedConfigs));
                })
                .collect(joining("\n"));
        return Stream.of(leaves, unknownLeaves, nested)
                        .filter(Predicate.not(String::isBlank))
                        .collect(joining("\n"))
                + '\n';
    }

    private static String findValue(final Placeholder item) {
        return item.getPlaceholder().getDefaultValue() != null
                        && !item.getPlaceholder().getDefaultValue().isBlank()
                ? '\"' + item.getPlaceholder().getDefaultValue() + '\"'
                : (item.getPlaceholder().getResolvedValue() != null
                                && !item.getPlaceholder().getResolvedValue().isBlank()
                        ? '\"' + item.getPlaceholder().getResolvedValue() + '\"'
                        : null);
    }

    private static String generatePlaceholderDesc(final String indentPrefix, final String comment) {
        return !comment.isBlank() ? indentPrefix + "# " + comment.replace("\n", indentPrefix + "# ") + '\n' : "";
    }

    private String chart(final String name) {
        return "apiVersion: v2\n" + "type: application\n" + "name: " + name + "\n" + "description: "
                + (UNSET.equals(chartDescription) ? "-" : chartDescription) + "\n" + "version: \""
                + dropSnapshotIfNeeded(UNSET.equals(chartVersion) ? "1.0.0" : chartVersion) + "\"\n" + "appVersion: \""
                + dropSnapshotIfNeeded(UNSET.equals(chartAppVersion) ? "1.0.0" : chartAppVersion) + "\"\n" + "";
    }

    private String helmIgnore() {
        return ".DS_Store\n" + ".git/\n" + ".gitignore\n" + ".bzr/\n" + ".bzrignore\n" + ".hg/\n" + ".hgignore\n"
                + ".svn/\n" + "*.swp\n" + "*.bak\n" + "*.tmp\n" + "*.orig\n" + "*~\n" + ".project\n" + ".idea/\n"
                + "*.tmproj\n" + ".vscode/\n";
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

    private CompletionStage<?> doExecute(
            final String from,
            final String manifest,
            final String alveolus,
            final ArchiveReader.Cache cache,
            final String id) {
        final var foundAlveoli = new ArrayList<Manifest.Alveolus>();
        return visitor.findRootAlveoli(from, manifest, alveolus, id)
                .thenApply(alveoli -> alveoli.stream()
                        .map(it -> it.exclude(excludedLocations, excludedDescriptors))
                        .collect(toList()))
                .thenCompose(alveoli -> {
                    foundAlveoli.addAll(alveoli.stream()
                            .map(AlveolusHandler.ManifestAndAlveolus::getAlveolus)
                            .collect(toList()));
                    return chain(
                            alveoli.stream()
                                    .map(it -> (Supplier<CompletionStage<?>>) () -> doExecute(cache, it, id))
                                    .iterator(),
                            true);
                })
                .thenRun(() -> doExport(foundAlveoli, id));
    }

    private CompletionStage<?> doExecute(
            final ArchiveReader.Cache cache, final AlveolusHandler.ManifestAndAlveolus it, final String id) {
        final var spyCollector = placeholderObserver.getSpies().get(id);
        // forced to ensure we process everything - including placeholders - synchronously
        final var sequentialAlveolus = it.getAlveolus().withChainDependencies(true);
        return visitor.executeOnceOnAlveolus(
                "Visiting",
                it.getManifest(),
                sequentialAlveolus,
                null,
                (ctx, desc) -> kube.forDescriptor(
                        "Visiting", desc.getContent(), desc.getExtension(), CompletableFuture::completedFuture),
                cache,
                desc -> {
                    spyCollector.getIgnoredKeys().clear();
                    return completedFuture(null);
                },
                "visited",
                id);
    }

    @ApplicationScoped
    public static class HelmChartSpy {
        private Map<String, SpyCollector> spies = new ConcurrentHashMap<>();

        public void onDescriptor(@OnEvent final OnPrepareDescriptor onPrepareDescriptor) {
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

        public void onPlaceholder(@OnEvent final OnPlaceholder onPlaceholder) {
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

        public Map<String, SpyCollector> getSpies() {
            return this.spies;
        }
    }

    public static class SpyCollector {
        private final Collection<String> ignoredKeys = new HashSet<>();
        private final Collection<OnPlaceholder> events = new ArrayList<>();
        private final Collection<OnPrepareDescriptor> descriptors = new ArrayList<>();

        public SpyCollector() {}

        public Collection<String> getIgnoredKeys() {
            return this.ignoredKeys;
        }

        public Collection<OnPlaceholder> getEvents() {
            return this.events;
        }

        public Collection<OnPrepareDescriptor> getDescriptors() {
            return this.descriptors;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof SpyCollector other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getIgnoredKeys(), other.getIgnoredKeys())
                    && Objects.equals(this.getEvents(), other.getEvents())
                    && Objects.equals(this.getDescriptors(), other.getDescriptors());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmCommand.SpyCollector;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getIgnoredKeys(), this.getEvents(), this.getDescriptors());
        }

        @Override
        public String toString() {
            return "HelmCommand.SpyCollector(ignoredKeys=" + this.getIgnoredKeys() + ", events=" + this.getEvents()
                    + ", descriptors=" + this.getDescriptors() + ")";
        }
    }

    private static class Placeholder {
        private final String[] name;
        private final OnPlaceholder placeholder;
        private final String comment;

        public Placeholder(final String[] name, final OnPlaceholder placeholder, final String comment) {
            this.name = name;
            this.placeholder = placeholder;
            this.comment = comment;
        }

        public String[] getName() {
            return this.name;
        }

        public OnPlaceholder getPlaceholder() {
            return this.placeholder;
        }

        public String getComment() {
            return this.comment;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Placeholder other)) {
                return false;
            }
            return other.canEqual(this)
                    && Arrays.deepEquals(this.getName(), other.getName())
                    && Objects.equals(this.getPlaceholder(), other.getPlaceholder())
                    && Objects.equals(this.getComment(), other.getComment());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmCommand.Placeholder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.deepHashCode(this.getName()), this.getPlaceholder(), this.getComment());
        }

        @Override
        public String toString() {
            return "HelmCommand.Placeholder(name=" + java.util.Arrays.deepToString(this.getName()) + ", placeholder="
                    + this.getPlaceholder() + ", comment=" + this.getComment() + ")";
        }
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.helm.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.helm.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to deploy (a file path or inline). This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.helm.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.helm.excludedDescriptors",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. "
                                    + "Note that using this setting, location is set to `*` so only the name is matched.")
            String excludedDescriptors,

            @Property(
                    value = "bundlebee.helm.excludedLocations",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
            String excludedLocations,

            @Property(
                    value = "bundlebee.helm.output",
                    defaultValue = "\"none\"",
                    documentation = "Where to create helm chart.")
            String output,

            @Property(
                    value = "bundlebee.helm.chart.name",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Helm chart name, if `auto` it will be the alveolus name if a single one is selected else it is `alveolus`.")
            String chartName,

            @Property(
                    value = "bundlebee.helm.chart.description",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Helm chart description.")
            String chartDescription,

            @Property(
                    value = "bundlebee.helm.chart.version",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Helm chart version.")
            String chartVersion,

            @Property(
                    value = "bundlebee.helm.chart.appVersion",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Helm chart app version.")
            String chartAppVersion,

            @Property(
                    value = "bundlebee.helm.chart.dropSnapshot",
                    defaultValue = "true",
                    documentation = "Should `-SNAPSHOT` be removed from the version if present.")
            boolean dropSnapshot,

            @Property(
                    value = "bundlebee.helm.placeholderDescriptions",
                    defaultValue = "\"true\"",
                    documentation =
                            "An optional file path to a properties file where the keys are placeholders and the value some description to inject in `values.yaml`.")
            String placeholderDescriptions) {}
}
