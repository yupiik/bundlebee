/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NewBundleCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void create(final CommandExecutor executor, @TempDir final Path dir) throws IOException {
        final var dirString = dir.toString().replace('\\', '/');
        assertEquals("" +
                "Created " + dirString + "/bundlebee/kubernetes\n" +
                "Created " + dirString + "/bundlebee/manifest.json\n" +
                "Created " + dirString + "/bundlebee/kubernetes/com.company_foo_my-alveolus.configmap.yaml\n" +
                "Created " + dirString + "/pom.xml\n" +
                "Creation completed, you can go in " + dirString + " and build it with 'bundlebee build'\n" +
                "", executor.wrap(null, Level.INFO, () -> new BundleBee().launch(
                "new", "--dir", dirString, "--group", "com.company", "--artifact", "foo")));
        assertEquals("" +
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "\n" +
                "  <groupId>com.company</groupId>\n" +
                "  <artifactId>foo</artifactId>\n" +
                "  <version>1.0.0</version>\n" +
                "  <name>foo</name>\n" +
                "  <description>Informative pom.xml for foo if you want to use maven to build and deploy this bundle.</description>\n" +
                "\n" +
                "  <build>\n" +
                "    <resources>\n" +
                "      <resource>\n" +
                "        <directory>${project.basedir}</directory>\n" +
                "        <includes>\n" +
                "          <include>bundlebee/**</include>\n" +
                "        </includes>\n" +
                "      </resource>\n" +
                "    </resources>\n" +
                "    <plugins>\n" +
                "      <plugin>\n" +
                "        <groupId>org.apache.maven.plugins</groupId>\n" +
                "        <artifactId>maven-release-plugin</artifactId>\n" +
                "        <version>3.0.0-M1</version>\n" +
                "      </plugin>\n" +
                "    </plugins>\n" +
                "  </build>\n" +
                "\n" +
                "  <!-- override with your custom nexus if you have one -->\n" +
                "  <distributionManagement>\n" +
                "    <snapshotRepository>\n" +
                "      <id>my-nexus</id>\n" +
                "      <url>https://nexus.mycompany.org/content/repositories/snapshots</url>\n" +
                "    </snapshotRepository>\n" +
                "    <repository>\n" +
                "      <id>my-nexus</id>\n" +
                "      <url>https://nexus.mycompany.org/content/repositories/releases</url>\n" +
                "    </repository>\n" +
                "  </distributionManagement>\n" +
                "</project>\n" +
                "", Files.readString(dir.resolve("pom.xml")));
        assertEquals("" +
                "{\n" +
                "  \"alveoli\": [\n" +
                "    {\n" +
                "      \"name\": \"com.company:foo_my-alveolus:1.0.0\",\n" +
                "      \"descriptors\": [\n" +
                "        {\n" +
                "          \"name\": \"com.company_foo_my-alveolus.configmap\"\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}" +
                "", Files.readString(dir.resolve("bundlebee/manifest.json")));
        assertEquals("" +
                "apiVersion: v1\n" +
                "kind: ConfigMap\n" +
                "metadata:\n" +
                "  name: com_company_foo_my-alveolus_configmap\n" +
                "  labels:\n" +
                "    app: my-app\n" +
                "data:\n" +
                "  # set your data there, you can safely drop BUNDLEBEE_SKAFFOLDING variable\n" +
                "  BUNDLEBEE_SKAFFOLDING: true\n" +
                "", Files.readString(dir.resolve("bundlebee/kubernetes/com.company_foo_my-alveolus.configmap.yaml")));
    }
}
