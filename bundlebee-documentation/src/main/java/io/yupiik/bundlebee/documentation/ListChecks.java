/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.command.impl.lint.LintingCheck;
import io.yupiik.bundlebee.core.command.impl.lint.builtin.NoLatestImage;
import org.apache.webbeans.service.DefaultLoaderService;
import org.apache.webbeans.spi.LoaderService;

import javax.enterprise.inject.se.SeContainerInitializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class ListChecks implements Runnable {
    private final Path sourceBase;

    public ListChecks(final Path sourceBase) {
        this.sourceBase = sourceBase;
    }

    @Override
    public void run() {
        // lazy way to load them all using a fake and limited container
        try (final var container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addProperty(LoaderService.class.getName(), new DefaultLoaderService() {
                    @Override
                    public <T> List<T> load(final Class<T> aClass, final ClassLoader classLoader) {
                        return List.of();
                    }
                })
                .addPackages(NoLatestImage.class)
                .initialize()) {
            final var checks = container.select(LintingCheck.class).stream().collect(toList());
            final var output = Files.createDirectories(sourceBase.resolve("content/_partials/generated/documentation")).resolve("lint.checks.adoc");
            Files.writeString(output, "= Linting Checks\n" +
                    "\n" +
                    "Here is the list of available checks by defaults.\n" +
                    "\n" +
                    checks.stream()
                            .map(c -> "== " + c.name() + "\n" +
                                    "\n" +
                                    "Name: *" + c.name() + "*.\n" +
                                    "\n" +
                                    c.description() + "\n" +
                                    "\n" +
                                    "=== Remediation\n" +
                                    "\n" +
                                    c.remediation() + "\n" +
                                    "\n")
                            .sorted()
                            .collect(joining("\n", "", "\n")));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
