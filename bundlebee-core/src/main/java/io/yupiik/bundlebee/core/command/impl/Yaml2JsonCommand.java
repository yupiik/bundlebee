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

import static java.util.concurrent.CompletableFuture.completedStage;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "yaml2json",
        description = "Convert yaml files of a directory to json. "
                + "This command is useful to transform Kubernetes yaml files to json to write Bundlebee descriptors.")
public class Yaml2JsonCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(Yaml2JsonCommand.class.getName());

    public Yaml2JsonCommand(final Options options, Yaml2JsonConverter yaml2json, JsonMapper jsonb) {
        this.yaml2json = yaml2json;
        this.jsonb = jsonb;
        this.input = options.input();
        this.output = options.output();
        this.logDescriptorOnParsingError = options.logDescriptorOnParsingError();
    }

    private final Yaml2JsonConverter yaml2json;
    private final JsonMapper jsonb;
    private final String input;
    private final String output;
    private final boolean logDescriptorOnParsingError;

    @Override
    public String name() {
        return "yaml2json";
    }

    @Override
    public String description() {
        return "Convert yaml files of a directory to json. "
                + "This command is useful to transform Kubernetes yaml files to json to write Bundlebee descriptors.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var sources = new HashMap<Path, String>();
        final var sourceDir = Path.of(this.input);
        final var targetDir = Path.of(this.output);
        try {
            Files.walkFileTree(sourceDir.toAbsolutePath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final var sourceFilename = file.getFileName().toString();
                    if (sourceFilename.endsWith(".yaml")) {
                        log.info(() -> "Converting \'" + sourceFilename + "\'");
                        final var targetContent = jsonb.toString(yaml2json.convert(
                                Object.class, Files.readString(file).trim()));
                        final var targetFilename =
                                sourceFilename.substring(0, sourceFilename.length() - ".yaml".length()) + ".json";
                        final var targetPath = targetDir.resolve(Optional.ofNullable(sourceDir
                                        .toAbsolutePath()
                                        .relativize(file)
                                        .getParent())
                                .orElse(Path.of("")));
                        sources.put(targetPath.resolve(targetFilename), targetContent);
                    }
                    return super.visitFile(file, attrs);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        log.info(() -> "Found " + sources.size() + " files to convert");
        return completedStage(sources.keySet().stream()
                .map(key -> write(key, sources.get(key)))
                .collect(Collectors.toList()));
    }

    private Path write(Path file, String content) {
        try {
            if (file.getParent() != null && Files.notExists(file.getParent())) {
                try {
                    Files.createDirectories(file.getParent());
                } catch (final IOException e) {
                    throw new IllegalStateException("Unable to create parent directories: \'" + file + "\'", e);
                }
            }
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create file", e);
        }
        return file;
    }

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "logDescriptorOnParsingError":
                return Stream.of("false", "true");
            default:
                return Stream.empty();
        }
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.yaml2json.input",
                    defaultValue = "\"/dev/null\"",
                    documentation = "The directory path to read yaml files to convert.")
            String input,

            @Property(
                    value = "bundlebee.yaml2json.output",
                    defaultValue = "\"/dev/null\"",
                    documentation = "The directory path to write the generated json files.")
            String output,

            @Property(
                    value = "bundlebee.yaml2json.logDescriptorOnParsingError",
                    defaultValue = "true",
                    documentation = "Should YAML/JSON be logged when it can't be parsed.")
            boolean logDescriptorOnParsingError) {}
}
