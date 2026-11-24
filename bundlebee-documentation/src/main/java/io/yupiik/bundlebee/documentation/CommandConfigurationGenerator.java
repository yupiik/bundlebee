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

import static java.util.Map.entry;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class CommandConfigurationGenerator implements Runnable {
    private final Logger log = Logger.getLogger(CommandConfigurationGenerator.class.getName());
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        try (final var container = ConfiguringContainer.of().start();
                final var extractor = container.lookup(ParameterExtractor.class)) {
            final var output = sourceBase.resolve("content/commands");
            java.nio.file.Files.createDirectories(output);
            final var docs = generate(output, extractor.instance().metadata());
            final var commandsAdoc = output.getParent().resolve("commands.adoc");
            java.nio.file.Files.writeString(
                    commandsAdoc,
                    "= Available commands\n" + ":minisite-index: 300\n" + ":minisite-index-title: Commands\n"
                            + ":minisite-index-description: Available commands.\n" + ":minisite-index-icon: terminal\n"
                            + "\n" + "\n"
                            + docs.stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(it -> {
                                        final var filename =
                                                it.getKey().getFileName().toString();
                                        return "* xref:commands/" + filename + '['
                                                + filename.substring(
                                                        0, filename.length() - ".configuration.adoc".length())
                                                + "]: "
                                                + Character.toLowerCase(
                                                        it.getValue().charAt(0))
                                                + it.getValue().substring(1)
                                                + (it.getValue().endsWith(".") ? "" : ".");
                                    })
                                    .collect(joining("\n"))
                            + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + commandsAdoc);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map.Entry<Path, String>> generate(final Path base, final Map<String, Object> metadata) {
        final var formatter = new DocEntryFormatter();
        final var sharedConfig = JsonSupport.array(metadata, "globalOptions").stream()
                .map(JsonSupport::object)
                .collect(toList());
        final var sharedConfigDoc = // by key name to ensure it is deterministic
                "\n=== Inherited Global Configuration\n" + "\n"
                        + "TIP: use `~/.bundlebeerc` or `--fusion-properties <path to config>` with the original dotted configuration keys.\n"
                        + "\n"
                        + sharedConfig.stream()
                                .map(it -> formatter.format(it, k -> JsonSupport.string(it, "cliName")))
                                .sorted()
                                .collect(joining("\n\n"))
                        + '\n';
        try {
            Files.createDirectories(base.resolve("../_partials/generated"));
            Files.writeString(
                    base.resolve("../_partials/generated/shared.env.configuration.adoc"),
                    sharedConfig.stream()
                                    .map(it -> formatter.format(it, k -> JsonSupport.string(it, "cliName"), true))
                                    .sorted()
                                    .collect(joining("\n\n"))
                            + '\n');
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return JsonSupport.array(metadata, "commands").stream()
                .map(JsonSupport::object)
                .filter(command -> !Boolean.TRUE.equals(command.get("hidden")))
                .sorted(java.util.Comparator.comparing(command -> JsonSupport.string(command, "name")))
                .map(command -> {
                    try {
                        final var name = JsonSupport.string(command, "name");
                        final var config = JsonSupport.array(command, "options").stream()
                                .map(JsonSupport::object)
                                .map(it -> formatter.format(it, k -> JsonSupport.string(it, "cliName")))
                                .sorted()
                                .collect(joining("\n\n"));
                        final var conf = base.resolve(name.replace('/', '-') + ".configuration.adoc");
                        final var description = JsonSupport.string(command, "description");
                        if (name == null || name.isBlank() || description == null || description.isBlank()) {
                            throw new IllegalStateException("Incomplete command metadata: " + command);
                        }
                        Files.writeString(
                                conf,
                                "= " + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "\n" + "\n"
                                        + "Name: `" + name + "`.\n" + "\n" + description + "\n" + "\n"
                                        + "== Configuration\n" + "\n"
                                        + (config.isEmpty() ? "No configuration." : config) + "\n"
                                        + (skipSharedConfig(name) ? "" : sharedConfigDoc),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                        log.info("Created " + conf);
                        final int end = description.indexOf("\n//");
                        return entry(conf, end > 0 ? description.substring(0, end) : description);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                })
                .collect(toList());
    }

    // see io.yupiik.bundlebee.maven.build.MojoGenerator.skipSharedConfig
    private boolean skipSharedConfig(final String name) {
        return "add-alveolus".equals(name)
                || "list-alveoli".equals(name)
                || "list-lint-rules".equals(name)
                || "cipher".equals(name)
                || "decipher".equals(name)
                || "build".equals(name)
                || "cipher-password".equals(name)
                || "create-master-password".equals(name)
                || "new".equals(name)
                || "version".equals(name);
    }

    public CommandConfigurationGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.configuration = configuration;
    }
}
