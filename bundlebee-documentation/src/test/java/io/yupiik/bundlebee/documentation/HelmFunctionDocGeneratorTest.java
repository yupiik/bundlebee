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

import io.yupiik.bundlebee.helm.fn.IndentFunc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HelmFunctionDocGeneratorTest {
    @TempDir
    private Path temp;

    @Test
    void generate() throws Exception {
        final var srcBase = temp.resolve("src/main/minisite");
        final var currentDir = new java.io.File("").getAbsoluteFile().toPath();
        final var helmModule = currentDir.resolve("../bundlebee-helm").normalize();
        if (!java.nio.file.Files.isDirectory(helmModule)) {
            helmModule.normalize(); // not needed actually
        }
        new HelmFunctionDocGenerator(srcBase, helmModule) {
        }.run();
        final var output = srcBase.resolve("content/_partials/generated/documentation/helm.functions.adoc");
        assertTrue(Files.exists(output), "Generated file should exist");
        final var content = Files.readString(output);
        assertTrue(content.contains("=== String"), "Should contain String category");
        assertTrue(content.contains("=== Crypto"), "Should contain Crypto category");
        assertTrue(content.length() > 500, "Generated content should be non-trivial");
    }
}
