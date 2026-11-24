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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlveoliConfigurationGeneratorTest {
    @TempDir
    private Path temp;

    @Test
    void documentsGenericJsonAndYamlTrees() throws Exception {
        final var project = temp.resolve("project");
        final var site = project.resolve("docs/src/main/minisite");
        final var recipes = project.resolve("alveolus");
        final var recipe = recipes.resolve("sample");
        final var bundle = recipe.resolve("target/classes/bundlebee");
        Files.createDirectories(bundle.resolve("kubernetes"));
        Files.createDirectories(site);
        Files.writeString(recipes.resolve("pom.xml"), "<project/>");
        Files.writeString(recipe.resolve("pom.xml"), "<project/>");
        Files.writeString(bundle.resolve("manifest.json"), """
                {"ignored":"extra property","alveoli":[{"name":"sample:1.0.0","//":" Sample description. ",
                  "placeholdersDoc":[{"name":"sample.setting","description":"Setting description."}],
                  "dependencies":[{"name":"other","location":"example:other:1.0.0"}],"descriptors":[
                  {"name":"sample-configmap"},{"name":"sample-service"}]}]}
                """);
        Files.writeString(bundle.resolve("kubernetes/sample-configmap.yaml"), """
                kind: ConfigMap
                metadata:
                  name: sample
                data:
                  setting: '{{sample.setting:-some text}}'
                """);
        Files.writeString(bundle.resolve("kubernetes/sample-service.yaml"), """
                kind: Service
                metadata:
                  name: sample
                spec:
                  type: NodePort
                  ports:
                    - port: 8080
                      nodePort: 30080
                """);
        final var generator = new AlveoliConfigurationGenerator(site, Map.of("version", "2.0.0-SNAPSHOT"));
        generator.run();
        final var file = site.resolve("content/alveoli/sample-sample.adoc");
        final var first = Files.readString(file);
        assertTrue(first.contains("some text"));
        assertTrue(first.contains("Sample description.\n"));
        assertTrue(first.contains("Setting description."));
        assertTrue(first.contains("Default value: `some text`."));
        assertTrue(first.contains("* `other` from `example:other:1.0.0` dependency"));
        assertTrue(first.contains("Node Port: 30080"));
        assertTrue(first.contains("2.0.0-SNAPSHOT"));
        assertTrue(Files.readString(site.resolve("content/alveoli.adoc")).contains("sample-sample.adoc"));
        generator.run();
        assertEquals(first, Files.readString(file));
    }
}
