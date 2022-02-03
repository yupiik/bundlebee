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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void processNoOutput(final CommandExecutor executor) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee()
                .launch("process",
                        "--alveolus", "ApplyCommandTest.apply",
                        "--kubeconfig", "explicit",
                        "--bundlebee.process.injectTimestamp", "false"));
        assertEquals("" +
                "Processing 'ApplyCommandTest.apply'\n" +
                "ApplyCommandTest.d1:\n" +
                "{\n" +
                "  \"apiVersion\":\"v1\",\n" +
                "  \"kind\":\"Service\",\n" +
                "  \"metadata\":{\n" +
                "    \"labels\":{\n" +
                "      \"app\":\"s-test\",\n" +
                "      \"bundlebee.root.alveolus.name\":\"ApplyCommandTest.apply\",\n" +
                "      \"bundlebee.root.alveolus.version\":\"unknown\"\n" +
                "    },\n" +
                "    \"name\":\"s\"\n" +
                "  },\n" +
                "  \"spec\":{\n" +
                "    \"type\":\"NodePort\",\n" +
                "    \"ports\":[\n" +
                "      {\n" +
                "        \"port\":1234,\n" +
                "        \"targetPort\":1234\n" +
                "      }\n" +
                "    ],\n" +
                "    \"selector\":{\n" +
                "      \"app\":\"s-test\"\n" +
                "    }\n" +
                "  }\n" +
                "}\n" +
                "", clean(logs));
    }

    @Test
    void processOutput(final CommandExecutor executor, @TempDir final Path output) throws IOException {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee()
                .launch("process",
                        "--alveolus", "ApplyCommandTest.apply",
                        "--kubeconfig", "explicit",
                        "--bundlebee.process.output", output.toString(),
                        "--bundlebee.process.injectTimestamp", "false"));
        final var dump = output.resolve("ApplyCommandTest.d1");
        assertEquals("" +
                "Processing 'ApplyCommandTest.apply'\n" +
                "Dumping '" + dump + "'\n" +
                "", logs);
        assertEquals("" +
                "{\n" +
                "  \"apiVersion\":\"v1\",\n" +
                "  \"kind\":\"Service\",\n" +
                "  \"metadata\":{\n" +
                "    \"labels\":{\n" +
                "      \"app\":\"s-test\",\n" +
                "      \"bundlebee.root.alveolus.name\":\"ApplyCommandTest.apply\",\n" +
                "      \"bundlebee.root.alveolus.version\":\"unknown\"\n" +
                "    },\n" +
                "    \"name\":\"s\"\n" +
                "  },\n" +
                "  \"spec\":{\n" +
                "    \"type\":\"NodePort\",\n" +
                "    \"ports\":[\n" +
                "      {\n" +
                "        \"port\":1234,\n" +
                "        \"targetPort\":1234\n" +
                "      }\n" +
                "    ],\n" +
                "    \"selector\":{\n" +
                "      \"app\":\"s-test\"\n" +
                "    }\n" +
                "  }\n" +
                "}" +
                "", clean(Files.readString(dump)));
    }

    private String clean(final String readString) {
        return readString.replace("null\n", "");
    }
}
