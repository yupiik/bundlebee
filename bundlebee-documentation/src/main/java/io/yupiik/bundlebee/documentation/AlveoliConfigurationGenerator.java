package io.yupiik.bundlebee.documentation;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.lang.Substitutor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Comparator.comparing;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Log
@RequiredArgsConstructor
public class AlveoliConfigurationGenerator implements Runnable {
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon-.skip-cdi", true))) {
            final var output = sourceBase.resolve("content/alveoli");
            java.nio.file.Files.createDirectories(output);

            final var alveoliRoot = sourceBase
                    .getParent().getParent().getParent().getParent()
                    .resolve("alveolus");

            final var docs = generateAndReturnLinks(output, alveoliRoot, jsonb);
            final var commandsAdoc = output.getParent().resolve("alveoli.adoc");
            java.nio.file.Files.writeString(
                    commandsAdoc,
                    "= Available Alveoli\n" +
                            ":minisite-index: 400\n" +
                            ":minisite-index-title: Alveoli\n" +
                            ":minisite-index-description: Available alveoli/recipes/deployments.\n" +
                            ":minisite-index-icon: terminal\n" +
                            "\n" +
                            "\n" +
                            docs.stream()
                                    .sorted()
                                    .map(it -> "- " + it)
                                    .collect(joining("\n")) +
                            "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + commandsAdoc);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> generateAndReturnLinks(final Path base, final Path alveolusRoot, final Jsonb jsonb) {
        final var links = new ArrayList<String>();
        final var docFormatter = new DocEntryFormatter();
        try {
            Files.walkFileTree(alveolusRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    final var manifest = dir.resolve("target/classes/bundlebee/manifest.json");
                    if (Files.exists(manifest)) {
                        links.addAll(generateAlveolusDoc(dir.getFileName().toString(), manifest, jsonb, base));
                    }
                    if (!Files.exists(dir.resolve("pom.xml"))) { // no need to go further
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

    private List<String> generateAlveolusDoc(final String artifactId, final Path manifest,
                                             final Jsonb jsonb, final Path alveoliOutputBase) {
        try {
            final var mfString = Files.readString(manifest, StandardCharsets.UTF_8);
            final var json = jsonb.fromJson(mfString, JsonObject.class); // here we'll keep our internal comments (//)
            final var mf = jsonb.fromJson(mfString, Manifest.class);
            return mf.getAlveoli().stream()
                    .map(it -> {
                        final var fileName = artifactId + "-" + it.getName().replaceAll("[^a-zA-Z0-9\\-_]", "-") + ".adoc";
                        final var target = alveoliOutputBase.resolve(fileName);
                        try {
                            Files.writeString(
                                    target,
                                    toAlveolusDoc(
                                            it, manifest.getParent(),
                                            json.getJsonArray("alveoli").stream()
                                                    .map(JsonValue::asJsonObject)
                                                    .filter(item -> Objects.equals(it.getName(), item.getString("name")))
                                                    .findFirst()
                                                    .orElseThrow(),
                                            artifactId),
                                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                        log.info("Created " + target);
                        return artifactId + " xref:alveoli/" + fileName + '[' + it.getName() + ']';
                    })
                    .collect(toList());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Alveolus doc is taken from multiple places:
     * <ul>
     *     <li>Alveolus itself, the attribute {@code //} is used as alveolus description</li>
     *     <li>Alveolus itself, the attribute {@code placeholdersDoc} which contains object with this shape {@code {name,description}} is used for placeholder descriptions</li>
     *     <li>Descriptors placeholders which are extracted with their defaults</li>
     * </ul>
     */
    private String toAlveolusDoc(final Manifest.Alveolus alveolus,
                                 final Path bundleBeeFolder,
                                 final JsonObject rawAlveolusSpec,
                                 final String artifactId) {
        final var placeholders = findPlaceholders(
                bundleBeeFolder, alveolus,
                rawAlveolusSpec.containsKey("placeholdersDoc") ?
                        rawAlveolusSpec.getJsonArray("placeholdersDoc").stream()
                                .map(JsonValue::asJsonObject)
                                .collect(toMap(o -> o.getString("name"), identity())) :
                        Map.of());
        return "= " + alveolus.getName() + "\n" +
                "\n" +
                (rawAlveolusSpec.containsKey("//") ? rawAlveolusSpec.getString("//").trim() + '\n' : "") +
                "\n" +
                "== Maven Dependency\n" +
                "\n" +
                "[source,xml]\n" +
                "----\n" +
                "<dependency>\n" +
                "  <groupId>io.yupiik.alveoli</groupId>\n" +
                "  <artifactId>" + artifactId + "</artifactId>\n" +
                "  <version>" + configuration.get("version") + "</version>\n" +
                "</dependency>\n" +
                "----\n" +
                "\n" +
                "== Sample Usage\n" +
                "\n" +
                "[source,json]\n" +
                "----\n" +
                "{\n" +
                "  \"alveoli\": [\n" +
                "    {\n" +
                "      \"//\": \"My alveolus.\",\n" +
                "      \"name\": \"com.company:my-app:1.0.0\",\n" +
                "      \"descriptors\": []\n" +
                "      \"dependencies\": [" +
                "        {\n" +
                "          \"name\": \"" + alveolus.getName() + "\",\n" +
                "          \"location\": \"io.yupiik.alveoli:" + artifactId + ":" + configuration.get("version") + "\",\n" +
                "        }\n" +
                "      ]" + (placeholders.isEmpty() ? "" : ",") + "\n" +
                (placeholders.isEmpty() ?
                        "" : ("      \"patches\": [{\n" +
                        "        \"descriptorName\": \"" + alveolus.getName() + "\",\n" +
                        "        \"interpolate\": true\n" +
                        "      }]\n")) +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "----\n" +
                "\n" +
                "== Configuration\n" +
                "\n" +
                placeholders.stream()
                        .map(it -> it.getName() + "::\n" +
                                it.getDescription() + "\n" +
                                of(it.getDefaultValue())
                                        .filter(v -> !ConfigProperty.UNCONFIGURED_VALUE.equals(v))
                                        .map(v -> "Default value: `" + v + "`.")
                                        .orElse("No default value."))
                        .collect(joining("\n\n", "", "\n"));
    }

    private List<Placeholder> findPlaceholders(final Path bundleBeeFolder, final Manifest.Alveolus alveolus,
                                               final Map<String, JsonObject> placeholderDocs) {
        return alveolus.getDescriptors().stream()
                .flatMap(desc -> {
                    try {
                        final var file = Files.list(bundleBeeFolder.resolve(desc.getType()))
                                .filter(it -> {
                                    final var name = it.getFileName().toString();
                                    return Objects.equals(name, desc.getName()) ||
                                            Objects.equals(name, desc.getName() + ".yaml") ||
                                            Objects.equals(name, desc.getName() + ".yml") ||
                                            Objects.equals(name, desc.getName() + ".json");
                                })
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("No descriptor " + desc + " found"));

                        // capture placeholders
                        final var placeholders = new HashSet<Placeholder>();
                        new Substitutor((key, defVal) -> {
                            placeholders.add(new Placeholder(
                                    key,
                                    ofNullable(defVal).orElse(ConfigProperty.UNCONFIGURED_VALUE),
                                    ofNullable(placeholderDocs.get(key))
                                            // we enforce it to well document our alveoli
                                            .orElseThrow(() -> new IllegalArgumentException("Missing\n\n\"placeholdersDoc\": [\n" +
                                                    "        {\n" +
                                                    "          \"name\": \"" + key + "\",\n" +
                                                    "          \"description\": \"....\"\n" +
                                                    "        }\n" +
                                                    "      ]\n\nin " + alveolus))
                                            .getString("description")));
                            return "";
                        }).replace(Files.readString(file, StandardCharsets.UTF_8));

                        return placeholders.stream();
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .distinct()
                .sorted(comparing(Placeholder::getName))
                .collect(toList());
    }

    @Data
    private static class Placeholder {
        private final String name;
        private final String defaultValue;
        private final String description;
    }
}
