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

import static java.util.stream.Collectors.joining;

import io.yupiik.bundlebee.core.command.impl.lint.LintingCheck;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ListChecks implements Runnable {
    private final Path sourceBase;

    public ListChecks(final Path sourceBase) {
        this.sourceBase = sourceBase;
    }

    @Override
    public void run() {
        try (final var container = ConfiguringContainer.of().start();
                final var instances = container.lookups(LintingCheck.class)) {
            final var checks = instances.instance();
            if (checks.isEmpty()) {
                throw new IllegalStateException("No lint checks registered; check Fusion generated modules");
            }
            final var output = Files.createDirectories(sourceBase.resolve("content/_partials/generated/documentation"))
                    .resolve("lint.checks.adoc");
            Files.writeString(
                    output,
                    "= Linting Checks\n" + "\n"
                            + "Here is the list of available checks by defaults.\n"
                            + "\n"
                            + checks.stream()
                                    .map(c -> "== " + c.name() + "\n" + "\n"
                                            + "Name: *"
                                            + c.name() + "*.\n" + "\n"
                                            + c.description()
                                            + "\n" + "\n"
                                            + "=== Remediation\n"
                                            + "\n"
                                            + c.remediation()
                                            + "\n" + "\n")
                                    .sorted()
                                    .collect(joining("\n", "", "\n")));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
