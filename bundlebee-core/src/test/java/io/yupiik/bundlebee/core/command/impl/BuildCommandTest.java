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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static java.util.Collections.list;
import static java.util.logging.Level.INFO;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void buildAndInstall(final CommandExecutor executor, @TempDir final Path dir) throws IOException {
        // create a bundle
        new BundleBee().launch("new", "--dir", dir.toString(), "--group", "com.company", "--artifact", "foo");

        final var dirString = dir.toString().replace('\\', '/');
        assertEquals("" +
                "Including bundlebee/manifest.json\n" +
                "Including bundlebee/kubernetes/com.company_foo_my-alveolus.configmap.yaml\n" +
                "Built " + dirString + "/target/foo-1.0.0.jar\n" +
                "Installed " + dirString + "/m2/repository/com/company/foo/1.0.0/foo-1.0.0.jar\n" +
                "Project successfully built.\n" +
                "", executor.wrap(null, INFO, () -> new BundleBee().launch(
                "build", "--dir", dirString, "--bundlebee-maven-cache", dir.resolve("m2/repository").toString())));

        try (final JarFile file = new JarFile(dir.resolve("m2/repository/com/company/foo/1.0.0/foo-1.0.0.jar").toFile())) {
            assertEquals(
                    List.of("META-INF/MANIFEST.MF", "bundlebee/kubernetes/com.company_foo_my-alveolus.configmap.yaml", "bundlebee/manifest.json"),
                    list(file.entries()).stream().filter(it -> !it.isDirectory()).map(JarEntry::getName).sorted().collect(toList()));
        }
    }
}
