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

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import lombok.NoArgsConstructor;
import org.apache.xbean.finder.AnnotationFinder;
import org.apache.xbean.finder.ClassFinder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.xbean.finder.archive.ClasspathArchive.archive;

@NoArgsConstructor(access = PRIVATE)
public final class MojoGenerator {
    private static final Logger LOGGER = Logger.getLogger(MojoGenerator.class.getName());

    public static void main(final String... args) {
        final var output = Paths.get(args[1]);
        final var pck = "io.yupiik.bundlebee.maven.generated.mojo";
        final AnnotationFinder finder;
        try {
            finder = new AnnotationFinder(archive(Thread.currentThread().getContextClassLoader(), Paths.get(args[0]).toUri().toURL()))
                    .enableFindImplementations();
        } catch (final MalformedURLException e) {
            throw new IllegalStateException(e);
        }

        // todo: drop maven ones to use actual maven config
        final var sharedParameters = finder.findAnnotatedFields(ConfigProperty.class).stream()
                .filter(it -> it.isAnnotationPresent(Description.class))
                .filter(it -> !it.getDeclaringClass().getName().startsWith("io.yupiik.bundlebee.core.command."))
                .collect(toMap(
                        it -> it.getAnnotation(ConfigProperty.class).name(),
                        it -> {
                            final var configProperty = it.getAnnotation(ConfigProperty.class);
                            final var key = configProperty.name();
                            final var defaultValue = configProperty.defaultValue();
                            final var desc = it.getAnnotation(Description.class).value();
                            final var paramName = fromParameterToFieldName(key);
                            final var type = it.getType().getName();
                            return "" +
                                    "    /**\n" +
                                    "     * " + desc.replace('\n', ' ') + "\n" +
                                    "     */\n" +
                                    "    @Parameter(property = \"" + key + "\"" +
                                    ", defaultValue = \"" + defaultValue.replaceAll("\n", "\\n") + "\")\n" +
                                    "    private " +
                                    ("java.util.Set".equals(type) ? "Set<String>" : type.replace("java.lang.", "").replace("$", ".")) + " " +
                                    paramName + ";";
                        }));

        finder
                .findImplementations(Executable.class).stream()
                .filter(it -> !Modifier.isAbstract(it.getModifiers()) && !it.isInterface())
                .map(it -> {
                    try {
                        return it.asSubclass(Executable.class).getConstructor().newInstance();
                    } catch (final InstantiationException | IllegalAccessException | InvocationTargetException |
                                   NoSuchMethodException e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .filter(it -> !it.hidden() && !"help".equals(it.name()))
                .forEach(instance -> {
                    try {
                        final var name = instance.name();
                        final var prefix = Pattern.compile("^bundlebee\\." + name + "\\."); // see io.yupiik.bundlebee.core.BundleBee.toProperties
                        final var parameters = new ClassFinder(instance.getClass())
                                .findAnnotatedFields(ConfigProperty.class).stream()
                                .collect(toMap(
                                        it -> it.getAnnotation(ConfigProperty.class).name(),
                                        identity()));
                        final var parameterDeclarationPerName = parameters.entrySet().stream()
                                .collect(toMap(
                                        Map.Entry::getKey,
                                        it -> {
                                            final var configProperty = it.getValue().getAnnotation(ConfigProperty.class);
                                            final var key = configProperty.name();
                                            final var defaultValue = configProperty.defaultValue();
                                            final var desc = it.getValue().getAnnotation(Description.class).value();
                                            final var paramName = fromParameterToFieldName(prefix.matcher(key).replaceAll(""));
                                            return "" +
                                                    "    /**\n" +
                                                    "     * " + desc.replace('\n', ' ') + "\n" +
                                                    "     */\n" +
                                                    "    @Parameter(property = \"" + key + "\"" +
                                                    ", defaultValue = \"" + findDefault(key, defaultValue.replaceAll("\n", "\\n")) + "\")\n" +
                                                    "    private " + it.getValue().getType().getName().replace("java.lang.", "").replace("$", ".") + " " + paramName + ";";
                                        },
                                        (a, b) -> a,
                                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));
                        final var className = toClassName(name) + "Mojo";
                        final var mojo = output.resolve(pck.replace('.', '/') + '/' + className + ".java");
                        Files.createDirectories(mojo.getParent());

                        final var skipSharedConfig = skipSharedConfig(name);

                        final var sharedConfigParameters = skipSharedConfig ?
                                "" :
                                sharedParameters.keySet().stream()
                                        .flatMap(k -> Stream.of(
                                                "\"--" + k + "\"",
                                                "bundlebee.kube.filters.statefuleset.spec.allowed".equals(k) ?
                                                        "String.join(\",\", " + fromParameterToFieldName(k) + ")" :
                                                        "String.valueOf(" + fromParameterToFieldName(k) + ")"))
                                        .collect(joining(",\n                                ", "                                ", ""));

                        final var addManifestAlias = parameterDeclarationPerName.keySet().stream()
                                .anyMatch(MojoGenerator::shouldAddManifestAlias);

                        final var specificParameters = parameterDeclarationPerName.keySet().stream()
                                .flatMap(k -> {
                                    final var simpleName = prefix.matcher(k).replaceAll("");
                                    final var fieldName = fromParameterToFieldName(simpleName);
                                    return Stream.of(
                                            "\"--" + simpleName + "\"",
                                            "manifest".equals(simpleName) ?
                                                    "String.valueOf(\"skip\".equals(" + fieldName + ") ? defaultManifest : " + fieldName + ")" :
                                                    (parameters.get(k).getType() == List.class ?
                                                            "String.join(\",\", " + fieldName + ")" :
                                                            "String.valueOf(" + fieldName + ")"));
                                })
                                .collect(joining(",\n                                ", "                                ", ""));

                        final var launchArgs = Stream.of(
                                        "                                \"" + name + "\"",
                                        sharedConfigParameters,
                                        specificParameters)
                                .filter(it -> !it.isBlank())
                                .collect(joining(",\n"));

                        Files.writeString(
                                mojo,
                                "/*\n" +
                                        " * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com\n" +
                                        " * Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                                        " * you may not use this file except in compliance\n" +
                                        " * with the License.  You may obtain a copy of the License at\n" +
                                        " *\n" +
                                        " *  http://www.apache.org/licenses/LICENSE-2.0\n" +
                                        " *\n" +
                                        " * Unless required by applicable law or agreed to in writing,\n" +
                                        " * software distributed under the License is distributed on an\n" +
                                        " * \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\n" +
                                        " * KIND, either express or implied.  See the License for the\n" +
                                        " * specific language governing permissions and limitations\n" +
                                        " * under the License.\n" +
                                        " */\n" +
                                        "package " + pck + ";\n" +
                                        "\n" +
                                        "import org.apache.maven.plugins.annotations.Mojo;\n" +
                                        "import org.apache.maven.plugins.annotations.Parameter;\n" +
                                        "\n" +
                                        "import io.yupiik.bundlebee.core.BundleBee;\n" +
                                        "import io.yupiik.bundlebee.maven.mojo.BaseMojo;\n" +
                                        "\n" +
                                        "import java.util.Map;\n" +
                                        (!skipSharedConfig ? "import java.util.Set;\n" : "") +
                                        "import java.util.stream.Stream;\n" +
                                        "\n" +
                                        "/**\n" +
                                        " * " + instance.description().replace("// end of short description\n", "").replace('\n', ' ') + "\n" +
                                        " */\n" +
                                        "@Mojo(name = \"" + name + "\", requiresProject = " + needsProject(parameterDeclarationPerName.values()) + ", threadSafe = true /* not strictly true but avoids warning inaccurate for builds */)\n" +
                                        "public class " + className + " extends BaseMojo {\n" +
                                        (skipSharedConfig ? "" : sharedParameters.values().stream()
                                                .collect(joining("\n\n", "", "\n\n"))) +
                                        parameterDeclarationPerName.values().stream()
                                                .collect(joining("\n\n", "", "\n\n")) +
                                        "    /**\n" +
                                        "     * Custom properties injected in the main, it is often used for placeholders.\n" +
                                        "     * If the key (tag in pom) starts with `bundlebee-placeholder-import` then the value is resolved as a properties file\n" +
                                        "     * which is injected in the resulting placeholders (indirect placeholders).\n" +
                                        "     */\n" +
                                        "    @Parameter(property = \"bundlebee." + name + ".customPlaceholders\")\n" +
                                        "    private Map<String, String> customPlaceholders;\n" +
                                        (!addManifestAlias ? "" : "\n" +
                                                "    /**\n" +
                                                "     * Just an alias for the built-in manifest property to ease the pom configuration for all commands.\n" +
                                                "     */\n" +
                                                "    @Parameter(property = \"bundlebee.manifest\", defaultValue = \"skip\")\n" +
                                                "    private String defaultManifest;\n") +
                                        "\n" +
                                        "    @Override\n" +
                                        "    public void doExecute() {\n" +
                                        "        new BundleBee().launch(Stream.concat(\n" +
                                        "                        Stream.of(\n" +
                                        launchArgs + "),\n" +
                                        "                        toArgs(customPlaceholders))\n" +
                                        "                .toArray(String[]::new));\n" +
                                        "    }\n" +
                                        "}" +
                                        "\n",
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        LOGGER.info("Created " + mojo);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                });
    }

    private static boolean shouldAddManifestAlias(final String key) {
        return key.startsWith("bundlebee.") && key.endsWith(".manifest") && !"bundlebee.manifest".equals(key);
    }

    private static boolean needsProject(final Collection<String> parameters) {
        return parameters.stream().anyMatch(it -> it.contains("${project."));
    }

    private static String findDefault(final String key, final String defaultDefault) {
        switch (key) {
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
                if (!UNSET.equals(defaultDefault) && !"bundlebee.new.version".equals(key) /*this one has a default we want to override*/) {
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
        return "cipher".equals(name) ||
                "decipher".equals(name) ||
                "add-alveolus".equals(name) ||
                "build".equals(name) ||
                "cipher-password".equals(name) ||
                "create-master-password".equals(name) ||
                "list-lint-rules".equals(name) ||
                "new".equals(name) ||
                "version".equals(name);
    }
}
