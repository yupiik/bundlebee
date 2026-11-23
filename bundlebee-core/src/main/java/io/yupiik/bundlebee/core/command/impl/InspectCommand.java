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

import static java.util.stream.Collectors.joining;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@DefaultScoped
@Command(name = "inspect", description = "Inspect an alveolus, i.e. list the descriptors to apply.")
public class InspectCommand extends VisitorCommand {
    private static final Pattern NEWLINE = Pattern.compile("\n");
    private final Logger log = Logger.getLogger(InspectCommand.class.getName());

    public InspectCommand(
            final Options options,
            io.yupiik.bundlebee.core.service.AlveolusHandler visitor,
            io.yupiik.bundlebee.core.service.ArchiveReader archives) {
        super(visitor, archives);
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.descriptor = options.descriptor();
        this.verbose = options.verbose();
    }

    private final String alveolus;
    private final String manifest;
    private final String from;
    private final String descriptor;
    private final boolean verbose;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "verbose":
                return Stream.of("false", "true");
            default:
                return super.complete(options, optionName);
        }
    }

    @Override
    public String name() {
        return "inspect";
    }

    @Override
    public String description() {
        return "Inspect an alveolus, i.e. list the descriptors to apply.";
    }

    @Override
    public CompletionStage<?> execute() {
        return super.doExecute(from, manifest, alveolus, descriptor, null).thenAccept(collected -> {
            log.info("Inspection Report for alveolus=" + alveolus);
            log.info("");
            // keep addition order since it is the most likely right one
            collected.getDescriptors().entrySet().stream()
                    .flatMap(it -> Stream.concat(
                            toLogs(collected.getAlveoli().get(it.getKey()), it.getValue()), Stream.of("")))
                    .flatMap(it -> Stream.of(NEWLINE.split(it)))
                    .forEach(log::info);
        });
    }

    private Stream<String> toLogs(
            final Manifest.Alveolus alveolus, final List<AlveolusHandler.LoadedDescriptor> descriptors) {
        return Stream.concat(
                Stream.of("* Alveolus \'" + alveolus.name() + "\'"),
                Stream.concat(
                        descriptors.stream()
                                .map(desc -> "  > Descriptor \'"
                                        + desc.getConfiguration().name() + "\'"
                                        + toFrom(desc.getConfiguration().location())
                                        + (verbose ? '\n' + formatDescriptorContent(desc.getContent()) : "")
                                        + toPatches(alveolus, desc) + toPlaceholders(alveolus)),
                        alveolus.dependencies() != null
                                        && !alveolus.dependencies().isEmpty()
                                ? alveolus.dependencies().stream()
                                        .map(it -> "  - Dependency \'" + it.name() + "\'" + toFrom(it.location()))
                                : Stream.of()));
    }

    private String formatDescriptorContent(final String content) {
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            return "    "
                    + reader.lines()
                            .filter(it -> !it.trim().startsWith("#"))
                            .map(it -> "    " + it)
                            .collect(joining("\n"))
                            .trim();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toFrom(final String from) {
        return from != null && !"auto".equals(from) ? ", from \'" + from + "\'" : "";
    }

    private String toPatches(final Manifest.Alveolus alveolus, final AlveolusHandler.LoadedDescriptor desc) {
        if (alveolus.patches() == null) {
            return "";
        }
        final var patches = alveolus.patches().stream()
                .filter(p -> Objects.equals(
                        p.descriptorName(), desc.getConfiguration().name()))
                .map(it -> "    . Patch " + (it.includeIf() != null ? conditions(it.includeIf()) : "") + it.patch())
                .collect(joining("\n", "", ""))
                .trim();
        return patches.isBlank() ? "" : ("\n" + patches + "\n");
    }

    private String conditions(final Manifest.Conditions includeIf) {
        final var conditions = includeIf.conditions();
        if (conditions == null || conditions.isEmpty()) {
            return "";
        }
        if (conditions.size() == 1) {
            final var cond = conditions.get(0);
            return '(' + condition(cond) + ')';
        }
        return conditions.stream()
                .map(this::condition)
                .collect(joining(
                        " " + (includeIf.operator() == Manifest.ConditionOperator.ANY ? "OR" : "AND") + " ",
                        " (",
                        ")"));
    }

    private String condition(final Manifest.Condition cond) {
        final String type;
        switch (cond.type()) {
            case ENV:
                type = "env. var.";
                break;
            case SYSTEM_PROPERTY:
                type = "system prop.";
                break;
            default:
                type = "";
        }
        return type + " \'" + cond.key() + "\' has " + (cond.negate() ? "not " : "") + "value \'" + cond.value() + "\'";
    }

    private String toPlaceholders(final Manifest.Alveolus alveolus) {
        if (alveolus.placeholders() == null || alveolus.placeholders().isEmpty()) {
            return "";
        }
        final var all = alveolus.placeholders().entrySet().stream()
                .map(it -> "    . Placeholder " + it.getKey() + ": \'" + it.getValue() + "\'")
                .collect(joining("\n", "", ""));
        return all.isBlank() ? "" : ("\n" + all + "\n");
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.inspect.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.inspect.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.inspect.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.inspect.descriptor",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If set only this descriptor is logged, not that you can use a regex if you make the value prefixed with `r/`. "
                                    + "Note it generally only makes sense with verbose option.")
            String descriptor,

            @Property(
                    value = "bundlebee.inspect.verbose",
                    defaultValue = "false",
                    documentation = "If `true`, descriptors are logged too.")
            boolean verbose) {}
}
