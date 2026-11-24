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

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CompletionGeneratorCommandTest {

    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @ParameterizedTest
    @CsvSource({
        "'bundlebee ',8,'bundlebee'",
        "'bundlebee a',11,'add-alveolus\napply'",
        "'bundlebee ad',12,'add-alveolus'",
        "'bundlebee ad',11,'add-alveolus\napply'",
        "'bundlebee add-alveolus ',23,'--alveolus\n--image\n--manifest\n--overwrite\n--type'",
        "'bundlebee add-alveolus --t',26,'--type'",
        "'bundlebee add-alveolus --to',27,''",
        "'bundlebee add-alveolus --type',30,''",
        "'bundlebee add-alveolus --type ',30,'web'",
        "'bundlebee add-alveolus --type foo --',36,'--alveolus\n--image\n--manifest\n--overwrite'"
    })
    void complete(final String line, final int point, final String expected, final CommandExecutor executor) {
        assertEquals(
                expected,
                executor.wrap(
                                null,
                                INFO,
                                () -> new BundleBee()
                                        .launch(
                                                "completion",
                                                "--comp-line",
                                                line,
                                                "--comp-point",
                                                String.valueOf(point),
                                                "--bundlebee-completion-useLogger",
                                                "true"))
                        .trim());
    }

    @Test
    void sharedOptionSpellingsAndAliases(final CommandExecutor executor) {
        final var line = "bundlebee process --";
        final var proposals = executor.wrap(
                null,
                INFO,
                () -> new BundleBee()
                        .launch(
                                "completion",
                                "--comp.line",
                                line,
                                "--comp.point",
                                Integer.toString(line.length()),
                                "--useLogger",
                                "true"));
        assertTrue(proposals.contains("--bundlebee.kube.dryRun\n"), proposals);
        assertTrue(proposals.contains("--bundlebee.kube.tokenFile\n"), proposals);
        assertTrue(proposals.contains("--bundlebee.kube.certificates\n"), proposals);
        assertTrue(proposals.contains("--injectTimestamp\n"), proposals);
        assertFalse(proposals.contains("--bundlebee-kube-"), proposals);
        for (final var spelling : new String[] {"--bundlebee.kube.dryRun", "--bundlebee-kube-dryRun"}) {
            final var valueLine = "bundlebee process " + spelling + " ";
            assertEquals(
                    "true\nfalse",
                    executor.wrap(
                                    null,
                                    INFO,
                                    () -> new BundleBee()
                                            .launch(
                                                    "completion",
                                                    "--comp.line",
                                                    valueLine,
                                                    "--comp.point",
                                                    Integer.toString(valueLine.length()),
                                                    "--useLogger",
                                                    "true"))
                            .strip());
            final var usedLine = "bundlebee process " + spelling + " true --";
            final var remaining = executor.wrap(
                    null,
                    INFO,
                    () -> new BundleBee()
                            .launch(
                                    "completion",
                                    "--comp.line",
                                    usedLine,
                                    "--comp.point",
                                    Integer.toString(usedLine.length()),
                                    "--useLogger",
                                    "true"));
            assertFalse(remaining.contains("--bundlebee.kube.dryRun\n"), remaining);
        }
    }
}
