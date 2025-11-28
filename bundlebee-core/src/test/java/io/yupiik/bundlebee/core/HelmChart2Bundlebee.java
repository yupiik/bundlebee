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
package io.yupiik.bundlebee.core;

import io.yupiik.bundlebee.core.json.JsonProducer;
import io.yupiik.bundlebee.core.lang.Tuple2;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import io.yupiik.bundlebee.core.yaml.YamlProducer;
import org.yaml.snakeyaml.Yaml;

import javax.enterprise.inject.se.SeContainerInitializer;
import javax.enterprise.util.AnnotationLiteral;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

// dump helm output to a file:
// $ helm template --debug . > /tmp/debug.yaml
// then run this main with first arg being the output (/tmp/debug.yaml) and the second the bundlebee descriptor folder
public final class HelmChart2Bundlebee {
    private HelmChart2Bundlebee() {
        // no-op
    }

    public static void main(final String... args) throws IOException {
        final var from = Path.of(args[0]);
        final var to = Files.createDirectories(Path.of(args[1]));

        try (final var container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addBeanClasses(JsonProducer.class, YamlProducer.class, Yaml2JsonConverter.class)
                .initialize()) {
            final var bundleBeeAnnotationLiteral = new AnnotationLiteral<BundleBee>() {
            };
            final var yaml = container.select(Yaml.class, bundleBeeAnnotationLiteral).get();
            final var converter = container.select(Yaml2JsonConverter.class).get();

            final var source = normalize(Files.readAllLines(from)).replace("RELEASE-NAME-", "");
            final var yamls = StreamSupport.stream(yaml.loadAll(source).spliterator(), false).collect(toList());

            final var logger = Logger.getLogger(HelmChart2Bundlebee.class.getName());
            final var jsonWriterFactory = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
            final var descriptors = new ArrayList<String>(yamls.size());
            for (final var file : yamls) {
                final var w = toFormatted(yaml, converter, jsonWriterFactory, file);
                if (Boolean.getBoolean("debug")) {
                    System.out.println("---- " + w.getSecond());
                    System.out.println(w.getFirst());
                } else {
                    logger.info("Write '" + Files.writeString(to.resolve(w.getSecond()), w.getFirst()) + "'");
                }
                descriptors.add(w.getSecond());
            }

            logger.info(() -> "Created alveolus descriptors\n" +
                    "{\n" +
                    "  \"name\": \"my-alveolus\",\n" +
                    "  \"descriptors\": [\n" +
                    descriptors.stream().map(it -> "    {\n" +
                            "      \"name\": \"" + it + "\",\n" +
                            "      \"interpolate\": true\n" +
                            "    }").collect(joining(",\n", "", "\n")) +
                    "  ]\n" +
                    "}\n");
        }
    }

    private static String normalize(final List<String> lines) {
        final var out = new ArrayList<String>(lines.size());
        int labels = -1;
        for (final var line : lines) {
            if (labels > 0) {
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) != ' ') {
                        if (i != labels) {
                            // inject labels now
                            final var prefix = IntStream.rangeClosed(0, labels).mapToObj(it -> "").collect(joining(" "));
                            out.add(prefix + "app.kubernetes.io/managed-by: \"bundlebee\"");
                            out.add(prefix + "deploy.by: \"{{user.name:-unknown}}\"");
                            out.add(prefix + "deploy.time: \"{{annotations.deploytime}}\"");
                            out.add(prefix + "deploy.environment: \"{{annotations.environment}}\"");
                            out.add(prefix + "project.version: \"{{project.version}}\"");
                            labels = -1;
                            out.add(line);
                        } else {
                            final var stripped = line.strip();
                            if (!stripped.startsWith("helm.sh/") &&
                                    !stripped.startsWith("app.kubernetes.io/managed-by:") &&
                                    !stripped.startsWith("release:") &&
                                    !stripped.startsWith("heritage:") &&
                                    !stripped.startsWith("release:") &&
                                    !stripped.startsWith("chart:")) {
                                out.add(line);
                            }
                        }
                        break;
                    }
                }
            } else if (line.strip().equals("labels:")) {
                out.add(line);
                labels = line.indexOf("l") + 2 /* default indent */;
            } else {
                out.add(line);
            }
        }
        return String.join("\n", out);
    }

    private static Tuple2<String, String> toFormatted(final Yaml yaml, final Yaml2JsonConverter converter,
                                                      final JsonWriterFactory jsonWriterFactory,
                                                      final Object content) {
        final var desc = converter.convert(JsonObject.class, yaml.dump(content));
        final var stringWriter = new StringWriter();
        try (final var writer = jsonWriterFactory.createWriter(stringWriter)) {
            writer.write(desc);
        }
        final var meta = desc.getJsonObject("metadata");
        return new Tuple2<>(stringWriter.toString(), meta.getString("name") + "." + desc.getString("kind").toLowerCase(ROOT) + ".json");
    }
}
