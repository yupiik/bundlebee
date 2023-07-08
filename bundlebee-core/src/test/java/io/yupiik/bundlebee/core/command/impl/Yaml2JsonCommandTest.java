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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Yaml2JsonCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void yaml2jsonCommand(final CommandExecutor executor, @TempDir Path work) {

        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch("yaml2json",
                "--bundlebee.yaml2json.input", "src/test/resources/bundlebee",
                "--bundlebee.yaml2json.output", work.toAbsolutePath().toString()));
        assertAll(
                () -> assertTrue(logs.contains("Found 5 files to convert")),
                () -> assertTrue(Files.exists(work.resolve("kubernetes/ApplyCommandTest.d0.json"))),
                () -> assertTrue(Files.exists(work.resolve("kubernetes/ApplyCommandTest.d1.json"))),
                () -> assertTrue(Files.exists(work.resolve("kubernetes/ApplyCommandTest.d2.json"))),
                () -> assertTrue(Files.exists(work.resolve("kubernetes/ApplyCommandTest.d3.json"))),
                () -> assertTrue(Files.exists(work.resolve("kubernetes/ApplyCommandTest.template.json"))),
                () -> assertEquals(
                        "{\"apiVersion\":\"v1\",\"kind\":\"Service\",\"metadata\":{\"name\":\"s0\",\"labels\":{\"app\":\"s-test\"}},\"spec\":{\"type\":\"NodePort\",\"ports\":[{\"port\":1234,\"targetPort\":1234}],\"selector\":{\"app\":\"s-test\"}}}",
                        Files.readString(work.resolve("kubernetes/ApplyCommandTest.d0.json"))),
                () -> assertEquals(
                        "{\"apiVersion\":\"v1\",\"kind\":\"Service\",\"metadata\":{\"name\":{},\"labels\":{\"app\":{},\"withdefault1\":{},\"withdefault2\":{}}},\"spec\":{\"type\":{},\"ports\":[{\"port\":{},\"targetPort\":{}}],\"selector\":{\"app\":{}}}}",
                        Files.readString(work.resolve("kubernetes/ApplyCommandTest.template.json")))
        );
    }
}
