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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.yupiik.bundlebee.core.test.BaseTestModule;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.diff.GenericJsonDiff;
import io.yupiik.fusion.json.patch.GenericJsonPatch;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = CommandJsonModelsTest.TestConfigurationModule.class)
class CommandJsonModelsTest {
    public static class TestConfigurationModule extends BaseTestModule {
        public TestConfigurationModule() {
            super(Map.of("bundlebee.kube.skipLoadConfig", "true"));
        }
    }

    @Fusion
    private JsonMapper mapper;

    @Test
    void stateSecretIgnoresKubernetesEnvelopeFields() {
        final var secret = mapper.fromString(
                ApplyCommand.StateSecret.class,
                "{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"metadata\":{\"name\":\"state\"},\"data\":{\"state\":\"encoded\"}}");
        assertEquals("encoded", secret.data().get("state"));
    }

    @Test
    void nexusStatusUsesGeneratedRecords() {
        final var status = mapper.fromString(
                DeployCommand.NexusStatus.class, "{\"data\":{\"version\":\"2.14\",\"other\":true},\"other\":[]}");
        assertEquals("2.14", status.data().version());
    }

    @Test
    void alveoliUseItemsEnvelope() {
        final var items = new ListAlveoliCommand.Items(List.of(new ListAlveoliCommand.Item("one")));
        assertEquals("{\"items\":[{\"name\":\"one\"}]}", mapper.toString(items));
        assertEquals(items, mapper.fromString(ListAlveoliCommand.Items.class, mapper.toString(items)));
    }

    @Test
    void placeholderOutputPreservesFieldsAndEscaping() {
        final var item =
                new PlaceholderExtractorCommand.PlaceholderItem("key", "line\nquoted \"text\"", null, true, List.of());
        final var encoded = mapper.toString(item);
        assertFalse(encoded.contains("\"defaultValue\""));
        assertEquals(item, mapper.fromString(PlaceholderExtractorCommand.PlaceholderItem.class, encoded));
        final var argo = new PlaceholderExtractorCommand.ArgoParameter("key", "key", true, null, null);
        assertEquals(argo, mapper.fromString(PlaceholderExtractorCommand.ArgoParameter.class, mapper.toString(argo)));
    }

    @Test
    void diffSerializesFusionPatchOperationsWithoutConvertingThemToMaps() {
        final var actual = Map.<String, Object>of("metadata", Map.of("name", "before"));
        final var expected = Map.<String, Object>of("metadata", Map.of("name", "after"));
        final var patch = new GenericJsonDiff(actual, expected).toPatch();
        assertEquals(expected, new GenericJsonPatch(patch).apply(actual));
        final var result = new DiffCommand.ResourceDiff(
                "resource",
                "file:/descriptor.json",
                "https://cluster/resource",
                expected,
                actual,
                new DiffCommand.CleanDiff(Map.of("clean", patch)));
        final var json = mapper.fromString(Map.class, mapper.toString(result));
        final var diff = (Map<?, ?>) json.get("diff");
        final var operations = (List<?>) diff.get("clean");
        assertEquals(Map.of("op", "replace", "path", "/metadata/name", "value", "after"), operations.get(0));
    }
}
