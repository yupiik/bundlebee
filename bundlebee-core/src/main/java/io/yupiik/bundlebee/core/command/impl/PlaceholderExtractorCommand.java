/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.json.JsonArrayBuilder;
import javax.json.JsonString;
import javax.json.spi.JsonProvider;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class PlaceholderExtractorCommand extends VisitorCommand {
    @Inject
    @Description("Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("" +
            "If set only this descriptor is handled, not that you can use a regex if you make the value prefixed with `r/`. " +
            "Note it generally only makes sense with verbose option.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.descriptor", defaultValue = UNSET)
    private String descriptor;

    @Inject
    @Description("How to dump the placeholders, by default (`LOG`) it will print it but `FILE` will store it in a local file (using `dumpLocation`).")
    @ConfigProperty(name = "bundlebee.placeholder-extract.outputType", defaultValue = "LOG")
    private OutputType outputType;

    @Inject
    @Description("Extraction location (directory) when `outputType` is `FILE`.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.dumpLocation", defaultValue = "target/bundlebee_extract")
    private String dumpLocation;

    @Inject
    @Description("Properties filename (relative to `dumpLocation`) when `outputType` is `FILE`. Ignores properties extraction if value is `skip`.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.propertiesFilename", defaultValue = "placeholders.properties")
    private String propertiesFilename;

    @Inject
    @Description("Completion properties filename - see https://github.com/rmannibucau/vscode-properties-custom-completion - (relative to `dumpLocation`) when `outputType` is `FILE`. Ignores this extraction if value is `skip`.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.completionFilename", defaultValue = "placeholders.completion.properties")
    private String completionFilename;

    @Inject
    @Description("Asciidoc filename (relative to `dumpLocation`) when `outputType` is `FILE`. Ignores this extraction if value is `skip`.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.docFilename", defaultValue = "placeholders.adoc")
    private String docFilename;

    @Inject
    @Description("Properties file locations which contain key=the placeholder and value=the placeholder description.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.descriptions", defaultValue = "src/bundlebee/descriptions.properties")
    private String descriptions;

    @Inject
    @Description("List of placeholders or prefixes (ended with `.*`) to ignore. This is common for templates placeholders which don't need documentation since they are wired in the manifest in general.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.ignoredPlaceholders", defaultValue = "service..*")
    private List<String> ignoredPlaceholders;

    @Inject
    @Description("Should documentation generation fail on missing/unexpected placeholder description.")
    @ConfigProperty(name = "bundlebee.placeholder-extract.failOnInvalidDescription", defaultValue = "false")
    private boolean failOnInvalidDescription;

    @Inject
    @BundleBee
    private JsonProvider json;

    @Inject
    private PlaceholderSpy placeholderSpy;

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
        Stream.of(this.descriptions.split(","))
                .map(String::strip)
                .filter(Predicate.not(String::isBlank))
                .forEach(file -> {
                    if (file.startsWith("env:")) {
                        try (final var reader = new StringReader(System.getenv(file.substring("env:".length())))) {
                            descriptions.load(reader);
                        } catch (final IOException e) {
                            throw new IllegalArgumentException("Can't read '" + file + "'", e);
                        }
                    } else {
                        final var path = Path.of(file);
                        if (Files.exists(path)) {
                            try (final var reader = Files.newBufferedReader(path)) {
                                descriptions.load(reader);
                            } catch (final IOException e) {
                                throw new IllegalArgumentException("Can't read '" + file + "'", e);
                            }
                        }
                    }
                });

        final var lock = new ReentrantLock();
        final var collector = new HashSet<OnPlaceholder>();
        final var oldListener = placeholderSpy.getListener();
        placeholderSpy.setListener(p -> {
            if (ignoredPlaceholders.stream().anyMatch(it -> Objects.equals(it, p.getName()) ||
                    (it.endsWith(".*") && p.getName().startsWith(it.substring(0, it.length() - 2))))) {
                return;
            }
            lock.lock();
            try {
                collector.add(p);
            } finally {
                lock.unlock();
            }
        });
        return doExecute(from, manifest, alveolus, descriptor)
                .thenAccept(data -> {
                    final var placeholders = collector.stream()
                            .collect(groupingBy(OnPlaceholder::getName)).entrySet().stream()
                            .map(e -> {
                                final var defaultValues = e.getValue().stream()
                                        .map(OnPlaceholder::getDefaultValue)
                                        .filter(Objects::nonNull)
                                        .collect(toList());
                                return new Placeholder(
                                        e.getKey(), defaultValues.size() == 1 ? defaultValues.get(0) : null, defaultValues);
                            })
                            .collect(toList());

                    if (outputType == OutputType.ARGOCD) {
                        final var argoCdDynamicConf = placeholders.stream()
                                .collect(Collector.of(
                                        json::createArrayBuilder,
                                        (a, p) -> {
                                            final var conf = json.createObjectBuilder()
                                                    .add("name", p.getName())
                                                    .add("title", p.getName());
                                            if (p.getDefaultValue() != null) {
                                                conf.add("required", false)
                                                        .add("string", p.getDefaultValue());
                                            } else if (p.getDefaultValues() != null) {
                                                conf.add("required", false)
                                                        .add("string", String.join(",", p.getDefaultValues()));
                                            } else {
                                                conf.add("required", true);
                                            }

                                            final var desc = descriptions.getProperty(p.getName());
                                            if (desc != null) {
                                                conf.add("tooltip", desc);
                                            }

                                            a.add(conf);
                                        },
                                        JsonArrayBuilder::addAll,
                                        JsonArrayBuilder::build));
                        System.out.println(argoCdDynamicConf);
                    } else {
                        if (!"skip".equals(propertiesFilename)) {
                            doWrite("Sample",
                                    () -> Path.of(dumpLocation).resolve(propertiesFilename), () -> placeholders.stream()
                                            .map(p -> {
                                                final var key = p.getName();
                                                final var desc = descriptions.getProperty(key, key);
                                                final var defaultValue = p.getDefaultValue();
                                                return (desc != null && !desc.isBlank() ? "# HELP: " + desc.replace("\n", "\n# HELP: ") + "\n" : "") +
                                                        "# " + key + " = " + (defaultValue != null ? formatSampleDefault(defaultValue) : (p.getDefaultValues() != null ? p.getDefaultValues().stream().map(this::formatSampleDefault).collect(joining(" OR ")) : "-"));
                                            })
                                            .collect(joining("\n\n", "", "\n")));
                        }

                        if (!"skip".equals(completionFilename)) {
                            doWrite("Completion",
                                    () -> Path.of(dumpLocation).resolve(completionFilename), () -> placeholders.stream()
                                            .map(p -> p.getName() + " = " + descriptions.getProperty(p.getName(), p.getName()).replace("\n", " "))
                                            .collect(joining("\n", "", "\n")));
                        }

                        if (!"skip".equals(docFilename)) {
                            doWrite("Doc", () -> Path.of(dumpLocation).resolve(docFilename), () -> formatDoc(placeholders, descriptions));
                        }
                    }
                })
                .whenComplete((ok, ko) -> placeholderSpy.setListener(oldListener));
    }

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
                    return '`' + key + "`" + (defaultValue == null ? "*" : "") + "::" +
                            (desc == null ? "" : ('\n' + desc.strip())) +
                            formatDefault(key, defaultValue, null);
                })
                .collect(joining("\n\n"));
        if (failOnInvalidDescription && !missingDescriptionsPlaceholders.isEmpty()) {
            throw new IllegalStateException("Missing placeholder descriptions:\n" + missingDescriptionsPlaceholders.stream()
                    .sorted()
                    .collect(joining("\n")));
        }
        return adoc;
    }

    private String unescapeJson(final String value) {
        try (final var reader = json.createReader(new StringReader(value))) {
            return ((JsonString) reader.readValue()).getString();
        }
    }

    private String formatDefault(final String key, final String defaultValue, final String alveolus) {
        return defaultValue == null ?
                "\n" :
                ("\nDefault" + (alveolus != null ? " in alveolus `" + alveolus + "`" : "") + ": " + (
                        defaultValue.contains("\n") || key.startsWith("bundlebee-json-inline-file:") ? "" +
                                "\n[example%collapsible]\n" +
                                "====\n" +
                                "[source]\n" +
                                "----\n" +
                                (key.startsWith("bundlebee-json-inline-file:") ?
                                        unescapeJson('"' + defaultValue + '"') :
                                        defaultValue) + '\n' +
                                "----\n" +
                                "====\n" :
                                '`' + defaultValue + "`.")) + '\n';
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
        ARGOCD
    }

    @Data
    protected static class Placeholder {
        private final String name;
        private final String defaultValue;
        private final List<String> defaultValues;
    }

    @ApplicationScoped
    public static class PlaceholderSpy {
        @Setter
        @Getter
        private Consumer<OnPlaceholder> listener;

        public void onPlaceholder(@Observes final OnPlaceholder placeholder) {
            if (listener != null) {
                listener.accept(placeholder);
            }
        }
    }
}
