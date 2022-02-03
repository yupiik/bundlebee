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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddAlveolusCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void addService(final CommandExecutor executor, @TempDir final Path dir) throws IOException {
        // create a bundle
        final var dirString = dir.toString().replace('\\', '/');
        new BundleBee().launch("new", "--dir", dirString, "--group", "com.company", "--artifact", "foo");

        // now add a web alveolus
        final var logs = executor.wrap(null, INFO, () -> new BundleBee()
                .launch("add-alveolus",
                        "--manifest", dir.resolve("bundlebee/manifest.json").toString(),
                        "--alveolus", "test",
                        "--image", "deploy"));
        assertEquals("" +
                "Created " + dirString + "/bundlebee/kubernetes/test.configmap.yaml\n" +
                "Created " + dirString + "/bundlebee/kubernetes/test.deployment.yaml\n" +
                "Created " + dirString + "/bundlebee/kubernetes/test.service.yaml\n" +
                "Added alveolus 'test' to '" + dirString + "/bundlebee/manifest.json'\n" +
                "", logs);
        assertEquals("" +
                "apiVersion: v1\n" +
                "kind: ConfigMap\n" +
                "metadata:\n" +
                "  name: test-config\n" +
                "  labels:\n" +
                "    app: test\n" +
                "data:\n" +
                "  # you can drop this variable, it is here for demo purposes\n" +
                "  APP: test\n" +
                "", Files.readString(dir.resolve("bundlebee/kubernetes/test.configmap.yaml"), StandardCharsets.UTF_8));
        final var deployment = Files.readString(dir.resolve("bundlebee/kubernetes/test.deployment.yaml"), StandardCharsets.UTF_8);
        assertTrue(deployment.contains("" +
                "          image: deploy:latest\n" +
                "          imagePullPolicy: IfNotPresent\n" +
                ""), deployment);
    }
}
