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
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

@Log
@Dependent
public class InspectCommand extends VisitorCommand {
    @Inject
    @Description("Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.inspect.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.inspect.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.inspect.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("" +
            "If set only this descriptor is logged, not that you can use a regex if you make the value prefixed with `r/`. " +
            "Note it generally only makes sense with verbose option.")
    @ConfigProperty(name = "bundlebee.inspect.descriptor", defaultValue = UNSET)
    private String descriptor;

    @Inject
    @Description("If `true`, descriptors are logged too.")
    @ConfigProperty(name = "bundlebee.inspect.verbose", defaultValue = "false")
    private boolean verbose;

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
        return super.doExecute(from, manifest, alveolus, descriptor)
                .thenAccept(collected -> {
                    log.info("Inspection Report for alveolus=" + alveolus);
                    log.info("");
                    collected.getDescriptors().entrySet().stream()
                            // keep addition order since it is the most likely right one
                            .flatMap(it -> Stream.concat(
                                    toLogs(collected.getAlveoli().get(it.getKey()), it.getValue()),
                                    Stream.of("") /*empty line between alveolus*/))
                            .flatMap(it -> Stream.of(it.split("\n")))
                            .forEach(log::info);
                });
    }

    private Stream<String> toLogs(final Manifest.Alveolus alveolus,
                                  final List<AlveolusHandler.LoadedDescriptor> descriptors) {
        return Stream.concat(
                Stream.of("* Alveolus '" + alveolus.getName() + "'"),
                Stream.concat(
                        descriptors.stream()
                                .map(desc -> "  > Descriptor '" + desc.getConfiguration().getName() + "'" +
                                        toFrom(desc.getConfiguration().getLocation()) +
                                        (verbose ? '\n' + formatDescriptorContent(desc.getContent()) : "") +
                                        toPatches(alveolus, desc) +
                                        toPlaceholders(alveolus)),
                        alveolus.getDependencies() != null && !alveolus.getDependencies().isEmpty() ?
                                alveolus.getDependencies().stream()
                                        .map(it -> "  - Dependency '" + it.getName() + "'" + toFrom(it.getLocation())) :
                                Stream.of()));
    }

    private String formatDescriptorContent(final String content) {
        try (final BufferedReader reader = new BufferedReader(new StringReader(content))) {
            return "    " + reader.lines()
                    .filter(it -> !it.trim().startsWith("#"))
                    .map(it -> "    " + it)
                    .collect(joining("\n"))
                    .trim();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toFrom(final String from) {
        return from != null && !"auto".equals(from) ? ", from '" + from + "'" : "";
    }

    private String toPatches(final Manifest.Alveolus alveolus, final AlveolusHandler.LoadedDescriptor desc) {
        if (alveolus.getPatches() == null) {
            return "";
        }
        final var patches = alveolus.getPatches().stream()
                .filter(p -> Objects.equals(p.getDescriptorName(), desc.getConfiguration().getName()))
                .map(it -> "    . Patch " + (it.getIncludeIf() != null ? conditions(it.getIncludeIf()) : "") + it.getPatch())
                .collect(joining("\n", "", ""))
                .trim();
        return patches.isBlank() ? "" : ("\n" + patches + "\n");
    }

    private String conditions(final Manifest.Conditions includeIf) {
        final var conditions = includeIf.getConditions();
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
                        " " + (includeIf.getOperator() == Manifest.ConditionOperator.ANY ? "OR" : "AND") + " ",
                        " (", ")"));
    }

    private String condition(final Manifest.Condition cond) {
        final String type;
        switch (cond.getType()) {
            case ENV:
                type = "env. var.";
                break;
            case SYSTEM_PROPERTY:
                type = "system prop.";
                break;
            default:
                type = "";
        }
        return type + " '" + cond.getKey() + "' has " + (cond.isNegate() ? "not " : "") + "value '" + cond.getValue() + "'";
    }

    private String toPlaceholders(final Manifest.Alveolus alveolus) {
        if (alveolus.getPlaceholders() == null || alveolus.getPlaceholders().isEmpty()) {
            return "";
        }
        final var all = alveolus.getPlaceholders().entrySet().stream()
                .map(it -> "    . Placeholder " + it.getKey() + ": '" + it.getValue() + "'")
                .collect(joining("\n", "", ""));
        return all.isBlank() ? "" : ("\n" + all + "\n");
    }
}
