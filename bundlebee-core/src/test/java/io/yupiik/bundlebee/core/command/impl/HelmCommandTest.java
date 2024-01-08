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

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HelmCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void helm(@TempDir final Path helm, final CommandExecutor executor) throws IOException {
        final var desc = Files.writeString(Files.createDirectories(helm).resolve("desc.properties"), "" +
                "some = Some level desc.\n" +
                "some.placeholder1 = Some desc.");

        final var output = helm.resolve("helm");
        executor.wrap(null, INFO, () ->
                new BundleBee().launch("helm",
                        "--alveolus", "ApplyCommandTest.simpleNestedDependencyWithReusingTheTemplate",
                        "--chart-appVersion", "1.2.3",
                        "--chart-version", "1.1.5-SNAPSHOT",
                        "--output", output.toString(),
                        "--placeholderDescriptions", desc.toString()));
        final var outputs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Files.walkFileTree(output, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                outputs.put(output.relativize(file).toString().replace(File.separatorChar, '/'), Files.readString(file));
                return super.visitFile(file, attrs);
            }
        });
        assertEquals(
                Map.of(
                        ".helmignore", "" +
                                ".DS_Store\n" +
                                ".git/\n" +
                                ".gitignore\n" +
                                ".bzr/\n" +
                                ".bzrignore\n" +
                                ".hg/\n" +
                                ".hgignore\n" +
                                ".svn/\n" +
                                "*.swp\n" +
                                "*.bak\n" +
                                "*.tmp\n" +
                                "*.orig\n" +
                                "*~\n" +
                                ".project\n" +
                                ".idea/\n" +
                                "*.tmproj\n" +
                                ".vscode/\n",
                        "Chart.yaml", "" +
                                "apiVersion: v2\n" +
                                "type: application\n" +
                                "name: ApplyCommandTest.simpleNestedDependencyWithReusingTheTemplate\n" +
                                "description: -\n" +
                                "version: \"1.1.5\"\n" +
                                "appVersion: \"1.2.3\"\n",
                        "values.yaml", "" +
                                "ApplyCommandTest:\n" +
                                "  fromTemplate:\n" +
                                "    port: \"9090\"\n" +
                                "\n" +
                                "\n" +
                                "# Some level desc.\n" +
                                "some:\n" +
                                "  # Some desc.\n" +
                                "  placeholder1: \"with defaultvalue\"\n" +
                                "  placeholder2: \"with defaultvalue 2\"\n",
                        "templates/ApplyCommandTest.template.ApplyCommandTest.template.yaml", "" +
                                "#\n" +
                                "# Copyright (c) 2021 - present - Yupiik SAS - https://www.yupiik.com\n" +
                                "# Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                                "# you may not use this file except in compliance\n" +
                                "# with the License.  You may obtain a copy of the License at\n" +
                                "#\n" +
                                "#  http://www.apache.org/licenses/LICENSE-2.0\n" +
                                "#\n" +
                                "# Unless required by applicable law or agreed to in writing,\n" +
                                "# software distributed under the License is distributed on an\n" +
                                "# \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\n" +
                                "# KIND, either express or implied.  See the License for the\n" +
                                "# specific language governing permissions and limitations\n" +
                                "# under the License.\n" +
                                "#\n" +
                                "\n" +
                                "apiVersion: v1\n" +
                                "kind: Service\n" +
                                "metadata:\n" +
                                "  name: {{ .Values.service.name }}\n" +
                                "  labels:\n" +
                                "    app: {{ .Values.service.app }}\n" +
                                "    withdefault1: {{ .Values.some.placeholder1 }}\n" +
                                "    withdefault2: {{ .Values.some.placeholder2 }}\n" +
                                "spec:\n" +
                                "  type: {{ .Values.service.type }}\n" +
                                "  ports:\n" +
                                "    - port: {{ .Values.service.port }}\n" +
                                "      targetPort: {{ .Values.service.port }}\n" +
                                "  selector:\n" +
                                "    app: {{ .Values.service.app }}"
                ),
                outputs);
    }
}
