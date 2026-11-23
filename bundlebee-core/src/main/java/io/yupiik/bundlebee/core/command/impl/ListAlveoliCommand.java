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
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(name = "list-alveoli", description = "Lists all found alveoli")
public class ListAlveoliCommand implements Executable {
    private final Logger log = Logger.getLogger(ListAlveoliCommand.class.getName());
    private final JsonMapper jsonb;

    public ListAlveoliCommand(final Options options, JsonMapper jsonb, AlveolusHandler visitor) {
        this.jsonb = jsonb;
        this.from = options.from();
        this.manifest = options.manifest();
        this.output = options.output();
        this.visitor = visitor;
    }

    private final String from;
    private final String manifest;
    private final String output;
    private final AlveolusHandler visitor;

    @Override
    public String name() {
        return "list-alveoli";
    }

    @Override
    public String description() {
        return "Lists all found alveoli";
    }

    @Override
    public CompletionStage<?> execute() {
        return visitor.findManifest(from, manifest, null)
                .thenApply(m -> m == null || m.alveoli() == null
                        ? visitor.findManifestsFromClasspath(null)
                                .flatMap(it -> it.alveoli() == null ? Stream.empty() : it.alveoli().stream())
                                .collect(toList())
                        : m.alveoli())
                .thenApply(items -> {
                    switch (output) {
                        case "logger":
                            log.info(asText(items));
                            break;
                        case "logger.json":
                            log.info(asJson(items));
                            break;
                        default:
                            final var out = Path.of(output);
                            try {
                                if (out.getParent() != null) {
                                    Files.createDirectories(out.getParent());
                                }
                                Files.writeString(out, output.endsWith(".json") ? asJson(items) : asText(items));
                            } catch (final IOException ioe) {
                                throw new IllegalStateException(ioe);
                            }
                    }
                    return items;
                });
    }

    private String asJson(final List<Manifest.Alveolus> items) {
        return jsonb.toString(new Items(items.stream()
                .sorted(comparing(Manifest.Alveolus::name))
                .map(it -> new Item(it.name()))
                .collect(toList())));
    }

    private static String asText(final List<Manifest.Alveolus> a) {
        return a.isEmpty()
                ? "No alveolus found."
                : a.stream()
                        .sorted(comparing(Manifest.Alveolus::name))
                        .map(i -> "- " + i.name())
                        .collect(joining("\n", "Found alveoli:\n", "\n"));
    }

    @JsonModel
    public record Item(String name) {}

    @JsonModel
    public record Items(List<Item> items) {}

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.list-alveoli.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.list-alveoli.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.list-alveoli.output",
                    defaultValue = "\"logger\"",
                    documentation =
                            "`logger` means the standard bundlebee logging output stream else it is considered as a file path. "
                                    + "Note that if you end the filename with `.json` it will be formatted as json else just readable text.")
            String output) {}
}
