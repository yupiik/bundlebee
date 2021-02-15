/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
                            return "" +
                                    "    /**\n" +
                                    "     * " + desc.replace('\n', ' ') + "\n" +
                                    "     */\n" +
                                    "    @Parameter(property = \"" + key + "\"" +
                                    ", defaultValue = \"" + defaultValue.replaceAll("\n", "\\n") + "\")\n" +
                                    "    private " + it.getType().getName().replace("java.lang.", "") + " " + paramName + ";";
                        }));

        finder
                .findImplementations(Executable.class).stream()
                .filter(it -> !Modifier.isAbstract(it.getModifiers()) && !it.isInterface())
                .forEach(executable -> {
                    try {
                        final Executable instance = executable.asSubclass(Executable.class).getConstructor().newInstance();
                        final var name = instance.name();
                        if ("help".equals(name)) {
                            return;
                        }

                        final var prefix = Pattern.compile("^bundlebee\\." + name + "\\."); // see io.yupiik.bundlebee.core.BundleBee.toProperties
                        final var parameterDeclarationPerName = new ClassFinder(executable)
                                .findAnnotatedFields(ConfigProperty.class).stream()
                                .collect(toMap(
                                        it -> it.getAnnotation(ConfigProperty.class).name(),
                                        it -> {
                                            final var configProperty = it.getAnnotation(ConfigProperty.class);
                                            final var key = configProperty.name();
                                            final var defaultValue = configProperty.defaultValue();
                                            final var desc = it.getAnnotation(Description.class).value();
                                            final var paramName = prefix.matcher(key).replaceAll("");
                                            return "" +
                                                    "    /**\n" +
                                                    "     * " + desc.replace('\n', ' ') + "\n" +
                                                    "     */\n" +
                                                    "    @Parameter(property = \"" + key + "\"" +
                                                    ", defaultValue = \"" + defaultValue.replaceAll("\n", "\\n") + "\")\n" +
                                                    "    private " + it.getType().getName().replace("java.lang.", "") + " " + paramName + ";";
                                        },
                                        (a, b) -> a,
                                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));
                        final var className = toClassName(name) + "Mojo";
                        final var mojo = output.resolve(pck.replace('.', '/') + '/' + className + ".java");
                        Files.createDirectories(mojo.getParent());
                        Files.writeString(
                                mojo,
                                "/*\n" +
                                        " * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com\n" +
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
                                        "/**\n" +
                                        " * " + instance.description().replace("// end of short description\n", "").replace('\n', ' ') + "\n" +
                                        " */\n" +
                                        "@Mojo(name = \"" + name + "\", threadSafe = true /* not strictly true but avoids warning inaccurate for builds */)\n" +
                                        "public class " + className + " extends BaseMojo {\n" +
                                        sharedParameters.values().stream()
                                                .collect(joining("\n\n", "", "\n\n")) +
                                        parameterDeclarationPerName.values().stream()
                                                .collect(joining("\n\n", "", "\n\n")) +
                                        "    @Override\n" +
                                        "    public void doExecute() {\n" +
                                        "        new BundleBee().launch(\n" +
                                        "            \"" + name + "\",\n" +
                                        sharedParameters.keySet().stream()
                                                .flatMap(k -> Stream.of("\"--" + k + "\"", "String.valueOf(" + fromParameterToFieldName(k) + ")"))
                                                .collect(joining(",\n            ", "            ", "")) +
                                        (!parameterDeclarationPerName.isEmpty() ? ",\n" : "") +
                                        parameterDeclarationPerName.keySet().stream()
                                                .flatMap(k -> {
                                                    final var simpleName = prefix.matcher(k).replaceAll("");
                                                    return Stream.of("\"--" + simpleName + "\"", "String.valueOf(" + simpleName + ")");
                                                })
                                                .collect(joining(",\n            ", "            ", "")) + ");\n" +
                                        "    }\n" +
                                        "}" +
                                        "\n",
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        LOGGER.info("Created " + mojo);
                    } catch (final IOException | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        throw new IllegalArgumentException(e);
                    }
                });
    }

    private static String fromParameterToFieldName(final String name) {
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
}
