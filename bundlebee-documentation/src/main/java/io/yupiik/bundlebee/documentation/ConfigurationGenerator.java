/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.xbean.finder.IAnnotationFinder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

@Log
@RequiredArgsConstructor
public class ConfigurationGenerator implements Runnable {
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        final var module = configuration.get("module");
        final var exclude = configuration.get("exclude");
        try {
            final String lines = generate(exclude, new FinderFactory(configuration).finder());
            final var output = sourceBase
                    .resolve("content/_partials/generated/documentation")
                    .resolve(module.replace(".*", "") + ".adoc");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(
                    output,
                    lines.isEmpty() ? "No configuration yet." : lines,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + output);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generate(final String exclude, final IAnnotationFinder finder) {
        final var formatter = new DocEntryFormatter();
        return finder.findAnnotatedFields(ConfigProperty.class).stream()
                .filter(it -> exclude == null || !it.getDeclaringClass().getName().startsWith(exclude))
                .map(it -> formatter.format(it, identity()))
                .sorted() // by key name to ensure it is deterministic
                .collect(joining("\n\n"));
    }
}
