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

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import lombok.Data;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.json.JsonArrayBuilder;
import javax.json.JsonBuilderFactory;
import javax.json.bind.annotation.JsonbProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class ListAlveoliCommand implements Executable {
    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.list-alveoli.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.list-alveoli.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("`logger` means the standard bundlebee logging output stream else it is considered as a file path. " +
            "Note that if you end the filename with `.json` it will be formatted as json else just readable text.")
    @ConfigProperty(name = "bundlebee.list-alveoli.output", defaultValue = "logger")
    private String output;

    @Inject
    private AlveolusHandler visitor;

    @Inject
    @BundleBee
    private JsonBuilderFactory json;

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
                .thenApply(m -> m == null || m.getAlveoli() == null ?
                        visitor.findManifestsFromClasspath(null)
                                .flatMap(it -> it.getAlveoli() == null ? Stream.empty() : it.getAlveoli().stream())
                                .collect(toList()) :
                        m.getAlveoli())
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
                                Files.writeString(out, output.endsWith(".json") ?
                                        asJson(items) :
                                        asText(items));
                            } catch (final IOException ioe) {
                                throw new IllegalStateException(ioe);
                            }
                    }
                    return items;
                });
    }

    private String asJson(final List<Manifest.Alveolus> items) {
        return json.createObjectBuilder()
                .add("items", items.stream()
                        .sorted(comparing(Manifest.Alveolus::getName))
                        .map(it -> json.createObjectBuilder().add("name", it.getName()).build())
                        .collect(Collector.of(
                                json::createArrayBuilder,
                                JsonArrayBuilder::add,
                                JsonArrayBuilder::addAll)))
                .build()
                .toString();
    }

    private static String asText(final List<Manifest.Alveolus> a) {
        return a.isEmpty() ?
                "No alveolus found." :
                a.stream()
                        .sorted(comparing(Manifest.Alveolus::getName))
                        .map(i -> "- " + i.getName())
                        .collect(joining("\n", "Found alveoli:\n", "\n"));
    }

    @Data
    public static class Item {
        @JsonbProperty
        private final String name;
    }

    @Data
    public static class Items {
        @JsonbProperty
        private final List<Item> item;
    }
}
