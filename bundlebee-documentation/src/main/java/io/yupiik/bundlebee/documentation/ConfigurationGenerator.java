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

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.logging.Logger;

public class ConfigurationGenerator implements Runnable {
    private final Logger log = Logger.getLogger(ConfigurationGenerator.class.getName());
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        final var module = configuration.get("module");
        final var exclude = configuration.get("exclude");
        try (final var container = ConfiguringContainer.of().start();
                final var extractor = container.lookup(ParameterExtractor.class)) {
            final String lines = generate(exclude, extractor.instance().metadata());
            final var output = sourceBase
                    .resolve("content/_partials/generated/documentation")
                    .resolve(module.replace(".*", "") + ".adoc");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(
                    output, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + output);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generate(final String exclude, final Map<String, Object> metadata) {
        final var formatter = new DocEntryFormatter();
        final var result = // by key name to ensure it is deterministic
                JsonSupport.array(metadata, "globalOptions").stream()
                        .map(JsonSupport::object)
                        .filter(it -> exclude == null
                                || !JsonSupport.string(it, "name").startsWith(exclude))
                        .map(it -> formatter.format(it, identity()))
                        .sorted()
                        .collect(joining("\n\n"));
        if (result.isBlank()) {
            throw new IllegalStateException("No configuration metadata after exclusion: " + exclude);
        }
        return result;
    }

    public ConfigurationGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.configuration = configuration;
    }
}
