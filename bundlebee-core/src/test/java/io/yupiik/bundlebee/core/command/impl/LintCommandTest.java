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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LintCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void lint(final CommandExecutor executor) { // no error on a service as of today
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(
                "lint", "--alveolus", "ApplyCommandTest.withdep"));
        assertEquals("No linting error.\n", logs);
    }

    @Test
    void podWrongResources(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: v1\n" +
                "kind: Pod\n" +
                "metadata:\n" +
                "  name: my-pod\n" +
                "  namespace: default\n" +
                "spec:\n" +
                "  containers:\n" +
                "  - name: my-pod-ctr\n" +
                "    image: polinux/stress\n" +
                "    resources:\n" +
                "      requests:\n" +
                "        memory: \"10Gi\"\n");
        assertOutput(executor, lintCommand, "" +
                "There are linting errors:\n" +
                "[test][desc.yaml] No cpu resource in container requests resources\n" +
                "[test][desc.yaml] No limits element in container resources\n" +
                "[test][desc.yaml] No limits element in container resources\n");
    }

    @Test
    void podResourcesOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: v1\n" +
                "kind: Pod\n" +
                "metadata:\n" +
                "  name: my-pod\n" +
                "  namespace: default\n" +
                "spec:\n" +
                "  containers:\n" +
                "  - name: my-pod-ctr\n" +
                "    image: polinux/stress\n" +
                "    resources:\n" +
                "      requests:\n" +
                "        memory: \"10Gi\"\n" +
                "        cpu: 1\n" +
                "      limits:\n" +
                "        memory: \"10Gi\"\n" +
                "        cpu: 1\n");
        assertOutput(executor, lintCommand, "No linting error.\n");
    }

    private void assertOutput(final CommandExecutor executor, final String[] lintCommand, final String expected) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(lintCommand));
        assertEquals(expected, logs);
    }

    private String[] writeAlveolus(final Path work, final String descriptor) throws IOException {
        final var k8s = Files.createDirectories(work.resolve("bundlebee/kubernetes"));
        Files.writeString(k8s.resolve("desc.yaml"), descriptor);
        final var manifest = Files.writeString(k8s.getParent().resolve("manifest.json"), "" +
                "{\n" +
                "  \"alveoli\": [\n" +
                "    {\n" +
                "      \"name\": \"test\",\n" +
                "      \"descriptors\": [{\"name\": \"desc.yaml\"}]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        return new String[]{"lint", "--failLevel", "OFF", "--alveolus", "test", "--manifest", manifest.toString()};
    }
}
