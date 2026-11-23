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
package io.yupiik.bundlebee.documentation;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static io.yupiik.bundlebee.documentation.JsonSupport.array;
import static io.yupiik.bundlebee.documentation.JsonSupport.object;
import static io.yupiik.bundlebee.documentation.JsonSupport.string;
import static java.util.Comparator.comparing;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public class AlveoliConfigurationGenerator implements Runnable {
    private final Logger log = Logger.getLogger(AlveoliConfigurationGenerator.class.getName());
    private static final Pattern VERSION = Pattern.compile(":([0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?)$");
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^a-zA-Z0-9_-]");
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        final var yaml = new Yaml();
        try (final var container = ConfiguringContainer.of().start()) {
            final var jsonMapper = container.lookup(JsonMapper.class).instance();
            final var output = sourceBase.resolve("content/alveoli");
            java.nio.file.Files.createDirectories(output);
            final var alveoliRoot =
                    sourceBase.getParent().getParent().getParent().getParent().resolve("alveolus");
            final var docs = generateAndReturnLinks(output, alveoliRoot, jsonMapper, yaml);
            if (docs.isEmpty()) {
                throw new IllegalStateException("No built alveoli found under " + alveoliRoot);
            }
            final var commandsAdoc = output.getParent().resolve("alveoli.adoc");
            java.nio.file.Files.writeString(
                    commandsAdoc,
                    "= Available Alveoli\n" + ":minisite-index: 400\n" + ":minisite-index-title: Alveoli\n"
                            + ":minisite-index-description: Available alveoli/recipes/deployments.\n"
                            + ":minisite-index-icon: puzzle-piece\n" + "\n" + "\n"
                            + docs.stream().sorted().map(it -> "* " + it).collect(joining("\n\n")) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + commandsAdoc);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> generateAndReturnLinks(
            final Path base, final Path alveolusRoot, final JsonMapper jsonMapper, final Yaml yaml) {
        final var links = new ArrayList<String>();
        try {
            Files.walkFileTree(alveolusRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    final var manifest = dir.resolve("target/classes/bundlebee/manifest.json");
                    if (Files.exists(manifest)) {
                        links.addAll(
                                generateAlveolusDoc(dir.getFileName().toString(), manifest, jsonMapper, yaml, base));
                    }
                    if (!Files.exists(dir.resolve("pom.xml"))) {
                        // no need to go further
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return links;
    }

    private List<String> generateAlveolusDoc(
            final String artifactId,
            final Path manifest,
            final JsonMapper jsonMapper,
            final Yaml yaml,
            final Path alveoliOutputBase) {
        try {
            final var mfString = Files.readString(manifest, StandardCharsets.UTF_8);
            final var json = jsonMapper.fromString(DocumentedManifest.class, mfString);
            return json.alveoli().stream()
                    .map(it -> {
                        final var fileName = artifactId + "-"
                                + UNSAFE_NAME
                                        .matcher(VERSION.matcher(it.name()).replaceFirst(""))
                                        .replaceAll("-") + ".adoc";
                        final var target = alveoliOutputBase.resolve(fileName);
                        final var description =
                                it.description() != null ? it.description().trim() + '\n' : "";
                        try {
                            Files.writeString(
                                    target,
                                    toAlveolusDoc(it, manifest.getParent(), artifactId, description, jsonMapper, yaml),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                        log.info("Created " + target);
                        final var desc = description.isBlank()
                                ? it.name()
                                : extractShortDescription(Character.toLowerCase(description.charAt(0))
                                        + description.substring(1).trim());
                        return '*' + artifactId + '*' + " (xref:alveoli/" + fileName + '[' + it.name() + "]): "
                                + addDotIfNeeded(desc);
                    })
                    .collect(toList());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // go until first comment if it exists
    private String extractShortDescription(final String s) {
        final int end = s.indexOf("\n//");
        return end > 0 ? s.substring(0, end) : s;
    }

    private String addDotIfNeeded(String desc) {
        return desc + (desc.endsWith(".") ? "" : ".");
    }

    /**
     * Alveolus doc is taken from multiple places:
     * <ul>
     *     <li>Alveolus itself, the attribute {@code //} is used as alveolus description</li>
     *     <li>Alveolus itself, the attribute {@code placeholdersDoc} which contains object with this shape {@code {name,description}} is used for placeholder descriptions</li>
     *     <li>Descriptors placeholders which are extracted with their defaults</li>
     * </ul>
     */
    private String toAlveolusDoc(
            final DocumentedAlveolus alveolus,
            final Path bundleBeeFolder,
            final String artifactId,
            final String description,
            final JsonMapper jsonMapper,
            final Yaml yaml) {
        final var placeholders = findPlaceholders(
                bundleBeeFolder,
                alveolus,
                alveolus.placeholdersDoc().stream().collect(toMap(PlaceholderDoc::name, identity())));
        placeholders.forEach(p -> {
            if (!p.getName().startsWith(artifactId)) {
                throw new IllegalArgumentException(
                        "Built-in alveolus must use the artifactId as placeholder prefix (naming convention). " + p
                                + " does not respect that (" + artifactId + " expected).");
            }
        });
        final var dependencies = ofNullable(alveolus.dependencies())
                .map(deps -> "== Dependencies\n\n"
                        + deps.stream()
                                .map(dep -> "* `" + dep.name() + "` from `" + dep.location() + "` dependency")
                                .collect(joining("\n", "", "\n")))
                .orElse("");
        return "= " + alveolus.name() + "\n" + "\n" + description + "\n" + "== Maven Dependency\n" + "\n"
                + "[source,xml]\n" + "----\n" + "<dependency>\n" + "  <groupId>io.yupiik.alveoli</groupId>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n" + "  <version>" + configuration.get("version")
                + "</version>\n" + "</dependency>\n" + "----\n" + "\n"
                + addDefaultConfigurationDocIfAny(bundleBeeFolder, alveolus, jsonMapper, yaml)
                + addPortsDocIfAny(bundleBeeFolder, alveolus, jsonMapper, yaml) + "== Sample Usage\n" + "\n"
                + "[source,json]\n" + "----\n" + "{\n" + "  \"alveoli\": [\n" + "    {\n"
                + "      \"//\": \"My alveolus.\",\n" + "      \"name\": \"com.company:my-app:1.0.0\",\n"
                + "      \"descriptors\": [],\n" + "      \"dependencies\": [\n" + "        {\n"
                + "          \"name\": \"" + alveolus.name() + "\",\n" + "          \"location\": \"io.yupiik.alveoli:"
                + artifactId + ":" + configuration.get("version") + "\"\n" + "        }\n" + "      ]"
                + (placeholders.isEmpty() ? "" : ",") + "\n"
                + (placeholders.isEmpty()
                        ? ""
                        : ("      \"patches\": [{\n" + "        \"descriptorName\": \"" + alveolus.name() + "\",\n"
                                + "        \"interpolate\": true\n" + "      }]\n"))
                + "    }\n" + "  ]\n" + "}\n" + "----\n" + "\n" + dependencies
                + (placeholders.isEmpty()
                        ? ""
                        : ("== Configuration\n" + "\n"
                                + placeholders.stream()
                                        .map(it -> it.getName() + "::\n" + it.getDescription() + "\n"
                                                + of(it.getDefaultValue())
                                                        .filter(v -> !UNSET.equals(v))
                                                        .map(v -> "Default value: `" + v + "`.")
                                                        .orElse("No default value."))
                                        .collect(joining("\n\n", "", "\n"))));
    }

    private String addDefaultConfigurationDocIfAny(
            final Path bundleBeeFolder,
            final DocumentedAlveolus alveolus,
            final JsonMapper jsonMapper,
            final Yaml yaml) {
        final var configs = // todo: support secret (we don't use it yet)
                alveolus.descriptors().stream()
                        .filter(it -> it.name().endsWith("configmap"))
                        .map(desc -> toJson(bundleBeeFolder, jsonMapper, yaml, desc))
                        .filter(desc -> "ConfigMap".equals(string(desc, "kind")) && desc.containsKey("data"))
                        .sorted(comparing(desc -> string(object(desc, "metadata"), "name")))
                        .map(desc -> object(desc, "data"))
                        .map(data -> data.entrySet().stream()
                                .map(e -> new AbstractMap.SimpleImmutableEntry<>(e.getKey(), toString(e.getValue())))
                                .map(e -> e.getKey() + "::\n"
                                        + (e.getValue().contains("\n")
                                                ? "[source]\n----\n" + e.getValue() + "\n----\n"
                                                : ('`' + e.getValue() + '`')))
                                .collect(joining("\n", "", "\n\n")))
                        .collect(joining("\n"));
        return configs.isBlank() ? "" : ("== Default Configuration\n\n" + configs);
    }

    private String addPortsDocIfAny(
            final Path bundleBeeFolder,
            final DocumentedAlveolus alveolus,
            final JsonMapper jsonMapper,
            final Yaml yaml) {
        final var content = alveolus.descriptors().stream()
                .filter(it -> it.name().endsWith("service"))
                .map(desc -> toJson(bundleBeeFolder, jsonMapper, yaml, desc))
                .filter(desc -> "Service".equals(string(desc, "kind"))
                        && "NodePort".equals(string(object(desc, "spec"), "type"))
                        && object(desc, "spec").containsKey("ports"))
                .sorted(comparing(desc -> string(object(desc, "metadata"), "name")))
                .map(desc -> {
                    final var ports = array(object(desc, "spec"), "ports");
                    final var name = string(object(desc, "metadata"), "name");
                    return ports.stream()
                            .map(JsonSupport::object)
                            .map(port -> "* Name: `"
                                    + (port.containsKey("name") ? name + " (" + toString(port.get("name")) + ")" : name)
                                    + "`\n"
                                    + (port.containsKey("protocol")
                                            ? "** Protocol: " + string(port, "protocol") + '\n'
                                            : "")
                                    + (port.containsKey("port") ? "** Port: " + toString(port.get("port")) + '\n' : "")
                                    + (port.containsKey("targetPort")
                                            ? "** Target Port: " + toString(port.get("targetPort")) + '\n'
                                            : "")
                                    + (port.containsKey("nodePort")
                                            ? "** Node Port: " + toString(port.get("nodePort")) + '\n'
                                            : ""))
                            .collect(joining(
                                    "\n",
                                    "",
                                    ports.stream().anyMatch(it -> object(it).containsKey("nodePort"))
                                            ? "\nTIP: on linux and with minikube you can access this service using `http://$(minikube ip):"
                                                    + ports.stream()
                                                            .filter(it ->
                                                                    object(it).containsKey("nodePort"))
                                                            .findFirst()
                                                            .map(it -> toString(
                                                                    object(it).get("nodePort")))
                                                            .orElseThrow()
                                                    + "` on your host.\n\n"
                                            : "\n"));
                })
                .collect(joining("\n"));
        return content.isBlank() ? "" : ("== Ports\n\n" + content);
    }

    private Map<String, Object> toJson(
            final Path bundleBeeFolder, final JsonMapper jsonMapper, final Yaml yaml, final DocumentedDescriptor desc) {
        try {
            return object(yaml.load(
                    Files.readString(bundleBeeFolder.resolve(desc.type()).resolve(desc.name() + ".yaml"))));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toString(final Object json) {
        if (json instanceof String) {
            return (String) json;
        }
        try (final var container = ConfiguringContainer.of().start()) {
            return container.lookup(JsonMapper.class).instance().toString(json);
        }
    }

    private List<Placeholder> findPlaceholders(
            final Path bundleBeeFolder,
            final DocumentedAlveolus alveolus,
            final Map<String, PlaceholderDoc> placeholderDocs) {
        return
        // capture placeholders
        // we enforce it to well document our alveoli
        alveolus.descriptors().stream()
                .flatMap(desc -> {
                    try (var files = Files.list(bundleBeeFolder.resolve(desc.type()))) {
                        final var file = files.filter(it -> {
                                    final var name = it.getFileName().toString();
                                    return Objects.equals(name, desc.name())
                                            || Objects.equals(name, desc.name() + ".yaml")
                                            || Objects.equals(name, desc.name() + ".yml")
                                            || Objects.equals(name, desc.name() + ".json");
                                })
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("No descriptor " + desc + " found"));
                        final var placeholders = new HashSet<Placeholder>();
                        new Substitutor((key, defVal) -> {
                                    if (key.startsWith("bundlebee-")) {
                                        return "";
                                    }
                                    placeholders.add(new Placeholder(
                                            key,
                                            ofNullable(defVal).orElse(UNSET),
                                            ofNullable(placeholderDocs.get(key))
                                                    .orElseThrow(() -> new IllegalArgumentException(
                                                            "Missing\n\n\"placeholdersDoc\": [\n" + "        {\n"
                                                                    + "          \"name\": \"" + key + "\",\n"
                                                                    + "          \"description\": \"....\"\n"
                                                                    + "        }\n" + "      ]\n\nin " + alveolus))
                                                    .description()));
                                    return "";
                                })
                                .replace(Files.readString(file, StandardCharsets.UTF_8), null);
                        return placeholders.stream();
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .distinct()
                .sorted(comparing(Placeholder::getName))
                .collect(toList());
    }

    /** Documentation projection: the manifest also has documentation-only properties. */
    @JsonModel
    public record DocumentedManifest(List<DocumentedAlveolus> alveoli) {
        public DocumentedManifest {
            alveoli = alveoli == null ? List.of() : alveoli;
        }
    }

    @JsonModel
    public record DocumentedAlveolus(
            String name,
            @JsonProperty("//") String description,
            List<DocumentedDescriptor> descriptors,
            List<PlaceholderDoc> placeholdersDoc,
            List<DocumentedDependency> dependencies) {
        public DocumentedAlveolus {
            descriptors = descriptors == null ? List.of() : descriptors;
            placeholdersDoc = placeholdersDoc == null ? List.of() : placeholdersDoc;
        }
    }

    @JsonModel
    public record DocumentedDescriptor(String name, String type) {
        public DocumentedDescriptor {
            type = type == null ? "kubernetes" : type;
        }
    }

    @JsonModel
    public record DocumentedDependency(String name, String location) {}

    @JsonModel
    public record PlaceholderDoc(String name, String description) {}

    private static class Placeholder {
        private final String name;
        private final String defaultValue;
        private final String description;

        public Placeholder(final String name, final String defaultValue, final String description) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.description = description;
        }

        public String getName() {
            return this.name;
        }

        public String getDefaultValue() {
            return this.defaultValue;
        }

        public String getDescription() {
            return this.description;
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
                    && Objects.equals(this.getDescription(), other.getDescription());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof AlveoliConfigurationGenerator.Placeholder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getName(), this.getDefaultValue(), this.getDescription());
        }

        @Override
        public String toString() {
            return "AlveoliConfigurationGenerator.Placeholder(name=" + this.getName() + ", defaultValue="
                    + this.getDefaultValue() + ", description=" + this.getDescription() + ")";
        }
    }

    public AlveoliConfigurationGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.configuration = configuration;
    }
}
