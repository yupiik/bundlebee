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
package io.yupiik.bundlebee.maven.build;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MojoGenerator {
    private static final Logger LOGGER = Logger.getLogger(MojoGenerator.class.getName());

    public static void main(final String... args) {
        final var output = Paths.get(args[1]);
        final var pck = "io.yupiik.bundlebee.maven.generated.mojo";
        final var metadata = metadata();
        final var sharedOptions = array(metadata, "globalOptions").stream()
                .map(MojoGenerator::object)
                .collect(toMap(option -> string(option, "name"), identity()));
        // This service setting is not exposed by help's CLI metadata, but is part of the Maven XML contract.
        sharedOptions.putIfAbsent(
                "bundlebee.awaiter.retryInterval",
                Map.of(
                        "name",
                        "bundlebee.awaiter.retryInterval",
                        "type",
                        "long",
                        "defaultValue",
                        "500",
                        "description",
                        "Interval in milliseconds between Kubernetes condition checks."));
        // Maven XML can configure this legacy parameter as a set even though the
        // generated runtime configuration uses a list. Preserve its descriptor type.
        sharedOptions.computeIfPresent("bundlebee.kube.filters.statefuleset.spec.allowed", (key, option) -> {
            final var compatible = new LinkedHashMap<String, Object>(option);
            compatible.put("type", "java.util.Set");
            return compatible;
        });
        final var sharedParameters = sharedOptions.values().stream()
                .collect(toMap(
                        option -> string(option, "name"),
                        option -> declaration(option, fromParameterToFieldName(string(option, "name")), false),
                        (a, b) -> {
                            throw new IllegalStateException("Duplicate global option");
                        },
                        TreeMap::new));
        array(metadata, "commands").stream()
                .map(MojoGenerator::object)
                .filter(command ->
                        (!Boolean.TRUE.equals(command.get("hidden")) || "completion".equals(string(command, "name")))
                                && !"help".equals(string(command, "name"))
                                && !string(command, "name").startsWith("completion/"))
                .sorted(Comparator.comparing(command -> string(command, "name")))
                .forEach(command -> {
                    try {
                        final var name = string(command, "name");
                        final var prefix = Pattern.compile("^bundlebee\\." + Pattern.quote(name) + "\\.");
                        final var parameters = array(command, "options").stream()
                                .map(MojoGenerator::object)
                                .collect(toMap(option -> string(option, "name"), identity()));
                        final var parameterDeclarationPerName = parameters.entrySet().stream()
                                .collect(toMap(
                                        Map.Entry::getKey,
                                        it -> {
                                            return declaration(
                                                    it.getValue(),
                                                    fromParameterToFieldName(prefix.matcher(it.getKey())
                                                            .replaceFirst("")),
                                                    true);
                                        },
                                        (a, b) -> a,
                                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));
                        final var className = toClassName(name) + "Mojo";
                        final var mojo = output.resolve(pck.replace('.', '/') + '/' + className + ".java");
                        Files.createDirectories(mojo.getParent());
                        final var skipSharedConfig = skipSharedConfig(name);
                        final var sharedConfigParameters = skipSharedConfig
                                ? ""
                                : sharedParameters.keySet().stream()
                                        .flatMap(k -> Stream.of(
                                                "\"--" + k.replace('.', '-') + "\"",
                                                ("java.util.Set".equals(string(sharedOptions.get(k), "type"))
                                                                || "java.util.List"
                                                                        .equals(string(sharedOptions.get(k), "type")))
                                                        ? "String.join(\",\", " + fromParameterToFieldName(k) + ")"
                                                        : "String.valueOf(" + fromParameterToFieldName(k) + ")"))
                                        .collect(joining(
                                                ",\n                                ",
                                                "                                ",
                                                ""));
                        final var addManifestAlias = parameterDeclarationPerName.keySet().stream()
                                .anyMatch(MojoGenerator::shouldAddManifestAlias);
                        final var specificParameters = parameterDeclarationPerName.keySet().stream()
                                .flatMap(k -> {
                                    final var simpleName = prefix.matcher(k).replaceAll("");
                                    final var fieldName = fromParameterToFieldName(simpleName);
                                    return Stream.of(
                                            "\"--" + k.replace('.', '-') + "\"",
                                            "manifest".equals(simpleName)
                                                    ? "String.valueOf(\"skip\".equals(" + fieldName
                                                            + ") ? defaultManifest : " + fieldName + ")"
                                                    : ("java.util.List".equals(string(parameters.get(k), "type"))
                                                                    || "java.util.Set"
                                                                            .equals(string(parameters.get(k), "type"))
                                                            ? "String.join(\",\", " + fieldName + ")"
                                                            : "String.valueOf(" + fieldName + ")"));
                                })
                                .collect(joining(
                                        ",\n                                ", "                                ", ""));
                        final var launchArgs = Stream.of(
                                        "                                \"" + name + "\"",
                                        sharedConfigParameters,
                                        specificParameters)
                                .filter(it -> !it.isBlank())
                                .collect(joining(",\n"));
                        Files.writeString(
                                mojo,
                                "/*\n" + " * Copyright (c) 2021 - present - Yupiik SAS - https://www.yupiik.com\n"
                                        + " * Licensed under the Apache License, Version 2.0 (the \"License\");\n"
                                        + " * you may not use this file except in compliance\n"
                                        + " * with the License.  You may obtain a copy of the License at\n" + " *\n"
                                        + " *  http://www.apache.org/licenses/LICENSE-2.0\n" + " *\n"
                                        + " * Unless required by applicable law or agreed to in writing,\n"
                                        + " * software distributed under the License is distributed on an\n"
                                        + " * \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\n"
                                        + " * KIND, either express or implied.  See the License for the\n"
                                        + " * specific language governing permissions and limitations\n"
                                        + " * under the License.\n" + " */\n" + "package " + pck + ";\n" + "\n"
                                        + "import org.apache.maven.plugins.annotations.Mojo;\n"
                                        + "import org.apache.maven.plugins.annotations.Parameter;\n" + "\n"
                                        + "import io.yupiik.bundlebee.core.BundleBee;\n"
                                        + "import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;\n"
                                        + "import io.yupiik.bundlebee.maven.mojo.BaseMojo;\n" + "\n"
                                        + "import java.util.Map;\n" + "import java.util.Set;\n"
                                        + "import java.util.List;\n" + "import java.util.stream.Stream;\n" + "\n"
                                        + "/**\n" + " * "
                                        + string(command, "description")
                                                .replace("// end of short description\n", "")
                                                .replace('\n', ' ')
                                        + "\n" + " */\n" + "@Mojo(name = \"" + name + "\", requiresProject = "
                                        + needsProject(parameterDeclarationPerName.values()) + ", threadSafe = true)\n"
                                        + "public class " + className + " extends BaseMojo {\n"
                                        + (skipSharedConfig
                                                ? ""
                                                : sharedParameters.values().stream()
                                                        .collect(joining("\n\n", "", "\n\n")))
                                        + parameterDeclarationPerName.values().stream()
                                                .collect(joining("\n\n", "", "\n\n"))
                                        + "    /**\n"
                                        + "     * Custom properties injected in the main, it is often used for placeholders.\n"
                                        + "     * If the key (tag in pom) starts with `bundlebee-placeholder-import` then the value is resolved as a properties file\n"
                                        + "     * which is injected in the resulting placeholders (indirect placeholders).\n"
                                        + "     */\n" + "    @Parameter(property = \"bundlebee." + name
                                        + ".customPlaceholders\")\n"
                                        + "    private Map<String, String> customPlaceholders;\n"
                                        + (!addManifestAlias
                                                ? ""
                                                : "\n" + "    /**\n"
                                                        + "     * Just an alias for the built-in manifest property to ease the pom configuration for all commands.\n"
                                                        + "     */\n"
                                                        + "    @Parameter(property = \"bundlebee.manifest\", defaultValue = \"skip\")\n"
                                                        + "    private String defaultManifest;\n")
                                        + "\n" + "    @Override\n"
                                        + "    public void doExecute(final ConfigurationSource configuration) {\n"
                                        + "        new BundleBee().launch(Stream.concat(\n"
                                        + "                        Stream.of(\n" + launchArgs + "),\n"
                                        + "                        toArgs(customPlaceholders))\n"
                                        + "                .toArray(String[]::new), configuration);\n" + "    }\n" + "}"
                                        + "\n",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                        LOGGER.info("Created " + mojo);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                });
    }

    private static Map<String, Object> metadata() {
        try (final var container = ConfiguringContainer.of().start();
                final var extractor = container.lookup(ParameterExtractor.class)) {
            return extractor.instance().metadata();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(final Map<String, Object> value, final String key) {
        return (List<Object>) value.getOrDefault(key, List.of());
    }

    private static String string(final Map<String, Object> value, final String key) {
        return (String) value.get(key);
    }

    private static String declaration(final Map<String, Object> option, final String field, final boolean specific) {
        final var key = string(option, "name");
        final var desc = string(option, "description");
        final var rawDefault = string(option, "defaultValue");
        final var defaultValue = specific ? findDefault(key, rawDefault == null ? UNSET : rawDefault) : rawDefault;
        final var rawType = string(option, "type");
        final var type = rawType == null
                ? "String"
                : ("java.util.List".equals(rawType)
                        ? "List<String>"
                        : ("java.util.Set".equals(rawType)
                                ? "Set<String>"
                                : rawType.replace("java.lang.", "").replace('$', '.')));
        if (key == null || desc == null || desc.isBlank()) {
            throw new IllegalStateException("Incomplete option metadata: " + option);
        }
        return "    /**\n     * " + desc.replace("*/", "* /").replace('\n', ' ') + "\n     */\n"
                + "    @Parameter(property = \"" + escape(key) + "\""
                + (defaultValue == null || "\000".equals(defaultValue)
                        ? ""
                        : ", defaultValue = \"" + escape(defaultValue) + "\"")
                + ")\n" + "    private " + type + " " + field + ";";
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static boolean shouldAddManifestAlias(final String key) {
        return key.startsWith("bundlebee.") && key.endsWith(".manifest") && !"bundlebee.manifest".equals(key);
    }

    private static boolean needsProject(final Collection<String> parameters) {
        return parameters.stream().anyMatch(it -> it.contains("${project."));
    }

    private static String findDefault(final String key, final String defaultDefault) {
        switch (key) {
            case "bundlebee.http.headers":
                // Preserve the published plugin's historical default, including its literal n.
                // CLI defaults remain independent; changing this Maven wire contract needs opt-in.
                return "Accept: application/jsonnContent-Type: application/json";
            case "bundlebee.add-alveolus.alveolus":
                return "${project.groupId}:${project.artifactId}:${project.version}";
            case "bundlebee.add-alveolus.manifest":
                return "${project.build.outputDirectory}/bundlebee/manifest.json";
            case "bundlebee.add-alveolus.overwrite":
                return "false";
            case "bundlebee.new.dir":
                return "${project.build.outputDirectory}";
            case "bundlebee.new.skipPom":
            case "bundlebee.new.skipSamples":
            case "bundlebee.new.force":
                return "true";
            default:
                if (!UNSET.equals(defaultDefault)
                        && !"bundlebee.new.version".equals(key) /*this one has a default we want to override*/) {
                    return defaultDefault;
                }
                switch (key.substring(key.lastIndexOf('.') + 1)) {
                    case "group":
                        return "${project.groupId}";
                    case "artifact":
                        return "${project.artifactId}";
                    case "version":
                        return "${project.version}";
                    default:
                        return defaultDefault;
                }
        }
    }

    private static String fromParameterToFieldName(final String name) {
        if (name.startsWith("comp.")) {
            return fromParameterToFieldName(name.substring("comp.".length()));
        }
        if (name.startsWith("bundlebee.")) {
            return fromParameterToFieldName(name.substring("bundlebee.".length()));
        }
        return toCamelCase(name, Character.toLowerCase(name.charAt(0)));
    }

    private static String toClassName(final String name) {
        return toCamelCase(name, Character.toUpperCase(name.charAt(0)));
    }

    private static String toCamelCase(final String name, final char firstChar) {
        final var out = new StringBuilder(name.length());
        out.append(firstChar);
        boolean up = false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                up = true;
            } else if (up) {
                up = false;
                out.append(Character.toUpperCase(name.charAt(i)));
            } else {
                out.append(name.charAt(i));
            }
        }
        return out.toString();
    }

    // see io.yupiik.bundlebee.documentation.CommandConfigurationGenerator.skipSharedConfig
    private static boolean skipSharedConfig(final String name) {
        return "cipher".equals(name)
                || "decipher".equals(name)
                || "add-alveolus".equals(name)
                || "build".equals(name)
                || "cipher-password".equals(name)
                || "create-master-password".equals(name)
                || "list-alveoli".equals(name)
                || "list-lint-rules".equals(name)
                || "new".equals(name)
                || "version".equals(name);
    }

    private MojoGenerator() {}
}
