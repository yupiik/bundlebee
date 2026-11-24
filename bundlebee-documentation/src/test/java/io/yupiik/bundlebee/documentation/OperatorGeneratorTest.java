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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorGeneratorTest {
    @TempDir
    private Path temp;

    @Test
    void generatesOperatorResourcesUsingTheManifestCodec() throws Exception {
        final var generator = new OperatorGenerator(temp);
        generator.run();
        final var generated = temp.resolve("content/_partials/generated");
        final var resources = Files.readString(generated.resolve("operator.allinone.json"));
        final var configuration = Files.readString(generated.resolve("operator.configuration.adoc"));
        assertTrue(resources.contains("CustomResourceDefinition"));
        assertTrue(resources.contains("Deployment"));
        assertFalse(resources.contains("{{"));
        assertTrue(configuration.contains("bundlebee.operator.namespace:: namespace of the operator."));
        assertTrue(configuration.contains("Default value:"));
        generator.run();
        assertEquals(resources, Files.readString(generated.resolve("operator.allinone.json")));
        assertEquals(configuration, Files.readString(generated.resolve("operator.configuration.adoc")));
    }
}
