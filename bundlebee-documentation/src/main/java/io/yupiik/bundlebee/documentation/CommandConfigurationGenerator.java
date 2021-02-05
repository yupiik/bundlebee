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
package io.yupiik.bundlebee.documentation;

import io.yupiik.bundlebee.core.command.Executable;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.xbean.finder.AnnotationFinder;
import org.apache.xbean.finder.ClassFinder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
@RequiredArgsConstructor
public class CommandConfigurationGenerator implements Runnable {
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        try {
            final var output = sourceBase
                    .resolve("content/commands");
            java.nio.file.Files.createDirectories(output);
            final var docs = generate(output, new FinderFactory(configuration).finder());
            final var commandsAdoc = output.getParent().resolve("commands.adoc");
            java.nio.file.Files.writeString(
                    commandsAdoc,
                    "= Available commands\n" +
                            ":minisite-index: 300\n" +
                            ":minisite-index-title: Commands\n" +
                            ":minisite-index-description: Available commands.\n" +
                            ":minisite-index-icon: terminal\n" +
                            "\n" +
                            "\n" +
                            docs.stream()
                                    .sorted(Path::compareTo)
                                    .map(it -> {
                                        final var filename = it.getFileName().toString();
                                        return "- xref:commands/" + filename + '[' + filename.substring(0, filename.length() - ".configuration.adoc".length()) + ']';
                                    })
                                    .collect(joining("\n")) +
                            "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + commandsAdoc);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Path> generate(final Path base, final AnnotationFinder finder) {
        final var formatter = new DocEntryFormatter();
        return finder.enableFindImplementations().findImplementations(Executable.class).stream()
                .filter(it -> !Modifier.isAbstract(it.getModifiers()) && !it.isInterface())
                .map(command -> {
                    try {
                        final Executable instance = command.asSubclass(Executable.class).getConstructor().newInstance();
                        final var name = instance.name();
                        final var prefix = Pattern.compile("^bundlebee\\." + name + "\\."); // see io.yupiik.bundlebee.core.BundleBee.toProperties
                        final var config = new ClassFinder(command).findAnnotatedFields(ConfigProperty.class).stream()
                                .map(it -> formatter.format(it, k -> prefix.matcher(k).replaceAll("")))
                                .sorted() // by key name to ensure it is deterministic
                                .collect(joining("\n\n"));
                        final var conf = base.resolve(name + ".configuration.adoc");
                        java.nio.file.Files.writeString(
                                conf,
                                "= " + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "\n" +
                                        "\n" +
                                        instance.description() + "\n" +
                                        "\n" +
                                        "Name: `" + name + "`.\n" +
                                        "\n" +
                                        "== Configuration\n" +
                                        "\n" +
                                        (config.isEmpty() ? "No configuration." : config) + "\n",
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        log.info("Created " + conf);
                        return conf;
                    } catch (final IOException | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .collect(toList());
    }
}
