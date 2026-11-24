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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataGeneratorsTest {
    @TempDir
    private Path temp;

    @Test
    void generatesDeterministicNonEmptyReferencesWithoutConstructingCommands() throws Exception {
        final var site = temp.resolve("site");
        final var commands = new CommandConfigurationGenerator(site, Map.of());
        final var configuration = new ConfigurationGenerator(site, Map.of("module", "bundlebee-core"));
        commands.run();
        configuration.run();
        final var index = site.resolve("content/commands.adoc");
        final var first = Files.readString(index);
        assertTrue(first.indexOf("apply.configuration.adoc") < first.indexOf("version.configuration.adoc"));
        assertTrue(first.contains("completion.configuration.adoc"));
        final var options = Files.readString(site.resolve("content/commands/apply.configuration.adoc"));
        assertTrue(options.contains("--bundlebee-apply-alveolus"));
        assertTrue(options.indexOf("--bundlebee-apply-alveolus") < options.indexOf("--bundlebee-apply-manifest"));
        assertTrue(options.contains("Inherited Global Configuration"));
        final var globals =
                Files.readString(site.resolve("content/_partials/generated/documentation/bundlebee-core.adoc"));
        assertTrue(globals.contains("bundlebee."));
        assertTrue(globals.contains("BUNDLEBEE_"));
        assertTrue(globals.contains("bundlebee.kube.tokenFile"));
        assertTrue(globals.contains("bundlebee.kube.certificates"));
        assertTrue(globals.contains("bundlebee.kube.kubeconfig"));
        assertTrue(globals.contains("bundlebee.kube.fieldValidation"));
        assertFalse(globals.contains("bundlebee.kube.deployment."));
        assertEquals(
                globals.indexOf("`bundlebee.kube.kubeconfig`"), globals.lastIndexOf("`bundlebee.kube.kubeconfig`"));
        commands.run();
        configuration.run();
        assertEquals(first, Files.readString(index));
        assertEquals(options, Files.readString(site.resolve("content/commands/apply.configuration.adoc")));
        assertEquals(
                globals,
                Files.readString(site.resolve("content/_partials/generated/documentation/bundlebee-core.adoc")));
    }
}
