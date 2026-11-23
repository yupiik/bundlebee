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

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.helm.HelmChartLoader;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "placeholder-extract",
        description = "Extracts placeholders from an alveolus (often for documentation).")
public class PlaceholderExtractorCommand extends VisitorCommand {
    private static final Pattern COMMA = Pattern.compile(",");
    private final Logger log = Logger.getLogger(PlaceholderExtractorCommand.class.getName());
    private final JsonMapper jsonb;

    public PlaceholderExtractorCommand(
            final Options options,
            JsonMapper jsonb,
            PlaceholderSpy placeholderSpy,
            HelmChartLoader helmChartLoader,
            Emitter onPlaceholderEvent,
            io.yupiik.bundlebee.core.service.AlveolusHandler visitor,
            io.yupiik.bundlebee.core.service.ArchiveReader archives) {
        super(visitor, archives);
        this.jsonb = jsonb;
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.descriptor = options.descriptor();
        this.outputType = options.outputType();
        this.dumpLocation = options.dumpLocation();
        this.propertiesFilename = options.propertiesFilename();
        this.jsonFilename = options.jsonFilename();
        this.completionFilename = options.completionFilename();
        this.docFilename = options.docFilename();
        this.descriptions = options.descriptions();
        this.ignoredPlaceholders = options.ignoredPlaceholders();
        this.failOnInvalidDescription = options.failOnInvalidDescription();
        this.placeholderSpy = placeholderSpy;
        this.helmChartLoader = helmChartLoader;
        this.onPlaceholderEvent = onPlaceholderEvent;
    }

    private final String alveolus;
    private final String manifest;
    private final String from;
    private final String descriptor;
    private final OutputType outputType;
    private final String dumpLocation;
    private final String propertiesFilename;
    private final String jsonFilename;
    private final String completionFilename;
    private final String docFilename;
    private final String descriptions;
    private final List<String> ignoredPlaceholders;
    private final boolean failOnInvalidDescription;
    private final PlaceholderSpy placeholderSpy;
    private final HelmChartLoader helmChartLoader;
    private final Emitter onPlaceholderEvent;

    @Override
    public String name() {
        return "placeholder-extract";
    }

    @Override
    public String description() {
        return "Extracts placeholders from an alveolus (often for documentation).";
    }

    @Override
    public CompletionStage<?> execute() {
        final var descriptions = new Properties();
        Stream.of(COMMA.split(this.descriptions))
                .map(String::strip)
                .filter(Predicate.not(String::isBlank))
                .forEach(file -> {
                    if (file.startsWith("env:")) {
                        try (var reader = new StringReader(System.getenv(file.substring("env:".length())))) {
                            descriptions.load(reader);
                        } catch (final IOException e) {
                            throw new IllegalArgumentException("Can\'t read \'" + file + "\'", e);
                        }
                    } else {
                        final var path = Path.of(file);
                        if (Files.exists(path)) {
                            try (var reader = Files.newBufferedReader(path)) {
                                descriptions.load(reader);
                            } catch (final IOException e) {
                                throw new IllegalArgumentException("Can\'t read \'" + file + "\'", e);
                            }
                        }
                    }
                });
        final var id = UUID.randomUUID().toString();
        final var lock = new ReentrantLock();
        final var collector = new HashSet<OnPlaceholder>();
        final Consumer<OnPlaceholder> consumer = p -> {
            if (Objects.equals(id, p.getId())
                    && ignoredPlaceholders.stream()
                            .anyMatch(it -> Objects.equals(it, p.getName())
                                    || (it.endsWith(".*")
                                            && p.getName().startsWith(it.substring(0, it.length() - 2))))) {
                return;
            }
            lock.lock();
            try {
                collector.add(p);
            } finally {
                lock.unlock();
            }
        };
        placeholderSpy.getListener().add(consumer);
        return
        // Extract placeholders from helm chart values.yaml for helm descriptors
        // ArgoCD reads stdout so writes it there
        doExecute(from, manifest, alveolus, descriptor, id)
                .thenAccept(data -> {
                    data.getDescriptors().values().stream()
                            .flatMap(List::stream)
                            .filter(desc ->
                                    "helm".equals(desc.getConfiguration().type()))
                            .filter(desc -> desc.getConfiguration().helm() != null)
                            .forEach(desc -> extractHelmPlaceholders(desc, id));
                    final var placeholders =
                            collector.stream().collect(groupingBy(OnPlaceholder::getName)).entrySet().stream()
                                    .map(e -> {
                                        final var defaultValues = e.getValue().stream()
                                                .map(OnPlaceholder::getDefaultValue)
                                                .filter(Objects::nonNull)
                                                .collect(toList());
                                        return new Placeholder(
                                                e.getKey(),
                                                defaultValues.size() == 1 ? defaultValues.get(0) : null,
                                                defaultValues);
                                    })
                                    .sorted(comparing(Placeholder::getName))
                                    .collect(toList());
                    if (outputType == OutputType.ARGOCD) {
                        final var argoCdDynamicConf = placeholders.stream()
                                .map(p -> new ArgoParameter(
                                        p.getName(),
                                        p.getName(),
                                        p.getDefaultValue() == null && p.getDefaultValues() == null,
                                        p.getDefaultValue() != null
                                                ? p.getDefaultValue()
                                                : (p.getDefaultValues() != null
                                                        ? String.join(",", p.getDefaultValues())
                                                        : null),
                                        descriptions.getProperty(p.getName())))
                                .collect(toList());
                        System.out.println(jsonb.toString(argoCdDynamicConf));
                    } else {
                        if (!"skip".equals(jsonFilename)) {
                            doWrite(
                                    "JSON",
                                    () -> Path.of(dumpLocation).resolve(jsonFilename),
                                    () -> jsonb.toString(new PlaceholderItems(placeholders.stream()
                                                    .map(p -> new PlaceholderItem(
                                                            p.getName(),
                                                            descriptions.getProperty(p.getName(), p.getName()),
                                                            p.getDefaultValue(),
                                                            p.getDefaultValue() == null,
                                                            p.getDefaultValues()))
                                                    .collect(toList())))
                                            + '\n');
                        }
                        if (!"skip".equals(propertiesFilename)) {
                            doWrite(
                                    "Sample",
                                    () -> Path.of(dumpLocation).resolve(propertiesFilename),
                                    () -> placeholders.stream()
                                            .map(p -> {
                                                final var key = p.getName();
                                                final var desc = descriptions.getProperty(key, key);
                                                final var defaultValue = p.getDefaultValue();
                                                return (desc != null && !desc.isBlank()
                                                                ? "# HELP: " + desc.replace("\n", "\n# HELP: ") + "\n"
                                                                : "")
                                                        + "# " + key + " = "
                                                        + (defaultValue != null
                                                                ? formatSampleDefault(defaultValue)
                                                                : (p.getDefaultValues() != null
                                                                        ? p.getDefaultValues().stream()
                                                                                .map(this::formatSampleDefault)
                                                                                .collect(joining(" OR "))
                                                                        : "-"));
                                            })
                                            .collect(joining("\n\n", "", "\n")));
                        }
                        if (!"skip".equals(completionFilename)) {
                            doWrite(
                                    "Completion",
                                    () -> Path.of(dumpLocation).resolve(completionFilename),
                                    () -> placeholders.stream()
                                            .map(p -> p.getName() + " = "
                                                    + descriptions
                                                            .getProperty(p.getName(), p.getName())
                                                            .replace("\n", " "))
                                            .collect(joining("\n", "", "\n")));
                        }
                        if (!"skip".equals(docFilename)) {
                            doWrite(
                                    "Doc",
                                    () -> Path.of(dumpLocation).resolve(docFilename),
                                    () -> formatDoc(placeholders, descriptions));
                        }
                    }
                })
                .whenComplete((ok, ko) -> placeholderSpy.getListener().remove(consumer));
    }

    @JsonModel
    public record ArgoParameter(String name, String title, boolean required, String string, String tooltip) {}

    @JsonModel
    public record PlaceholderItems(List<PlaceholderItem> items) {}

    @JsonModel
    public record PlaceholderItem(
            @JsonProperty(order = 0) String name,
            @JsonProperty(order = 1) String description,
            @JsonProperty(order = 2) String defaultValue,
            @JsonProperty(order = 3) boolean required,
            @JsonProperty(order = 4) List<String> defaultValues) {}

    protected String formatDoc(final List<Placeholder> placeholders, final Properties descriptions) {
        final var missingDescriptionsPlaceholders = new HashSet<String>();
        final var adoc = placeholders.stream()
                .map(p -> {
                    final var key = p.getName();
                    final var desc = descriptions.getProperty(key);
                    if (desc == null) {
                        missingDescriptionsPlaceholders.add(key);
                    }
                    final var defaultValue = p.getDefaultValue();
                    return '`' + key + "`" + (defaultValue == null ? "*" : "") + "::"
                            + (desc == null ? "" : ('\n' + desc.strip())) + formatDefault(key, defaultValue, null);
                })
                .collect(joining("\n\n"));
        if (failOnInvalidDescription && !missingDescriptionsPlaceholders.isEmpty()) {
            throw new IllegalStateException("Missing placeholder descriptions:\n"
                    + missingDescriptionsPlaceholders.stream().sorted().collect(joining("\n")));
        }
        return adoc;
    }

    private String unescapeJson(final String value) {
        return jsonb.fromString(String.class, value);
    }

    private String formatDefault(final String key, final String defaultValue, final String alveolus) {
        return defaultValue == null
                ? "\n"
                : ("\nDefault" + (alveolus != null ? " in alveolus `" + alveolus + "`" : "") + ": "
                                + (defaultValue.contains("\n") || key.startsWith("bundlebee-json-inline-file:")
                                        ? "\n[example%collapsible]\n" + "====\n" + "[source]\n" + "----\n"
                                                + (key.startsWith("bundlebee-json-inline-file:")
                                                        ? unescapeJson('\"' + defaultValue + '\"')
                                                        : defaultValue)
                                                + '\n' + "----\n" + "====\n"
                                        : '`' + defaultValue + "`."))
                        + '\n';
    }

    protected String formatSampleDefault(final String defaultValue) {
        if (defaultValue == null) {
            return "";
        }
        if (defaultValue.contains("\n")) {
            return defaultValue.replace("\n", "\\\n");
        }
        return defaultValue;
    }

    private void extractHelmPlaceholders(final AlveolusHandler.LoadedDescriptor desc, final String id) {
        final var helmConfig = desc.getConfiguration().helm();
        final var rawPath = Path.of(helmConfig.chart());
        final var chartPath =
                rawPath.isAbsolute() ? rawPath : Path.of("").toAbsolutePath().resolve(helmConfig.chart());
        helmChartLoader.load(chartPath).whenComplete((chart, error) -> {
            if (error != null) {
                log.warning(() -> "Failed to load helm chart values from \'" + chartPath + "\': " + error.getMessage());
                return;
            }
            final var values = chart.getValues();
            if (values != null) {
                flattenValues(values, "", id);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void flattenValues(final Map<String, Object> values, final String prefix, final String id) {
        for (final var entry : values.entrySet()) {
            final var key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            final var value = entry.getValue();
            if (value instanceof Map) {
                flattenValues((Map<String, Object>) value, key, id);
            } else {
                final var stringValue = value == null ? null : value.toString();
                onPlaceholderEvent.emit(new OnPlaceholder(key, stringValue, stringValue, id));
            }
        }
    }

    private void doWrite(final String what, final Supplier<Path> location, final Supplier<String> contentProvider) {
        switch (outputType) {
            case FILE:
                final var out = location.get();
                try {
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, contentProvider.get());
                } catch (final IOException ioe) {
                    throw new IllegalStateException(ioe);
                }
                break;
            default:
                log.info(() -> what + '\n' + contentProvider.get());
        }
    }

    public enum OutputType {
        @Description("Just log the output.")
        LOG,
        @Description("Write the output to a file.")
        FILE,
        @Description("Use an output formatting ArgoCD understands.")
        ARGOCD;
    }

    protected static class Placeholder {
        private final String name;
        private final String defaultValue;
        private final List<String> defaultValues;

        public Placeholder(final String name, final String defaultValue, final List<String> defaultValues) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.defaultValues = defaultValues;
        }

        public String getName() {
            return this.name;
        }

        public String getDefaultValue() {
            return this.defaultValue;
        }

        public List<String> getDefaultValues() {
            return this.defaultValues;
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
                    && Objects.equals(this.getName(), other.getName())
                    && Objects.equals(this.getDefaultValue(), other.getDefaultValue())
                    && Objects.equals(this.getDefaultValues(), other.getDefaultValues());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof PlaceholderExtractorCommand.Placeholder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getName(), this.getDefaultValue(), this.getDefaultValues());
        }

        @Override
        public String toString() {
            return "PlaceholderExtractorCommand.Placeholder(name=" + this.getName() + ", defaultValue="
                    + this.getDefaultValue() + ", defaultValues=" + this.getDefaultValues() + ")";
        }
    }

    @ApplicationScoped
    public static class PlaceholderSpy {
        private final List<Consumer<OnPlaceholder>> listener = new CopyOnWriteArrayList<>();

        public void onPlaceholder(@OnEvent final OnPlaceholder placeholder) {
            if (listener != null) {
                for (final var it : listener) {
                    it.accept(placeholder);
                }
            }
        }

        public List<Consumer<OnPlaceholder>> getListener() {
            return this.listener;
        }
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.placeholder-extract.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.placeholder-extract.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.placeholder-extract.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.placeholder-extract.descriptor",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If set only this descriptor is handled, not that you can use a regex if you make the value prefixed with `r/`. "
                                    + "Note it generally only makes sense with verbose option.")
            String descriptor,

            @Property(
                    value = "bundlebee.placeholder-extract.outputType",
                    defaultValue = "io.yupiik.bundlebee.core.command.impl.PlaceholderExtractorCommand.OutputType.LOG",
                    documentation =
                            "How to dump the placeholders, by default (`LOG`) it will print it but `FILE` will store it in a local file (using `dumpLocation`).")
            OutputType outputType,

            @Property(
                    value = "bundlebee.placeholder-extract.dumpLocation",
                    defaultValue = "\"target/bundlebee_extract\"",
                    documentation = "Extraction location (directory) when `outputType` is `FILE`.")
            String dumpLocation,

            @Property(
                    value = "bundlebee.placeholder-extract.propertiesFilename",
                    defaultValue = "\"placeholders.properties\"",
                    documentation =
                            "Properties filename (relative to `dumpLocation`) when `outputType` is `FILE`. Ignores properties extraction if value is `skip`.")
            String propertiesFilename,

            @Property(
                    value = "bundlebee.placeholder-extract.jsonFilename",
                    defaultValue = "\"placeholders.json\"",
                    documentation =
                            "JSON filename (relative to `dumpLocation`) when `outputType` is `FILE`. Ignores JSON dump if value is `skip`.")
            String jsonFilename,

            @Property(
                    value = "bundlebee.placeholder-extract.completionFilename",
                    defaultValue = "\"placeholders.completion.properties\"",
                    documentation =
                            "Completion properties filename - see https://github.com/rmannibucau/vscode-properties-custom-completion - (relative to `dumpLocation`) when `outputType` is `FILE`. Ignores this extraction if value is `skip`.")
            String completionFilename,

            @Property(
                    value = "bundlebee.placeholder-extract.docFilename",
                    defaultValue = "\"placeholders.adoc\"",
                    documentation =
                            "Asciidoc filename (relative to `dumpLocation`) when `outputType` is `FILE`. Ignores this extraction if value is `skip`.")
            String docFilename,

            @Property(
                    value = "bundlebee.placeholder-extract.descriptions",
                    defaultValue = "\"src/bundlebee/descriptions.properties\"",
                    documentation =
                            "Properties file locations which contain key=the placeholder and value=the placeholder description.")
            String descriptions,

            @Property(
                    value = "bundlebee.placeholder-extract.ignoredPlaceholders",
                    defaultValue = "java.util.List.of(\"service..*\")",
                    documentation =
                            "List of placeholders or prefixes (ended with `.*`) to ignore. This is common for templates placeholders which don't need documentation since they are wired in the manifest in general.")
            List<String> ignoredPlaceholders,

            @Property(
                    value = "bundlebee.placeholder-extract.failOnInvalidDescription",
                    defaultValue = "false",
                    documentation =
                            "Should documentation generation fail on missing/unexpected placeholder description.")
            boolean failOnInvalidDescription) {}
}
