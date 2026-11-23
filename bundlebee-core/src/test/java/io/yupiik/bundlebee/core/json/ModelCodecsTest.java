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
package io.yupiik.bundlebee.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.command.model.sarif.CompleteLocation;
import io.yupiik.bundlebee.core.command.model.sarif.Sarif;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.descriptor.State;
import io.yupiik.bundlebee.core.test.BaseTestModule;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = ModelCodecsTest.TestConfigurationModule.class)
class ModelCodecsTest {
    public static class TestConfigurationModule extends BaseTestModule {
        public TestConfigurationModule() {
            super(Map.of("bundlebee.kube.skipLoadConfig", "true"));
        }
    }

    @Fusion
    private JsonMapper mapper;

    @Test
    void generatedManifestRoundTrip() {
        final var manifest = mapper.fromString(Manifest.class, """
                {
                  "interpolateAlveoli": true,
                  "ignoredLintingRules": [{"name":"rule"}],
                  "references": [{"path":"extra.json"}],
                  "requirements": [{"minBundlebeeVersion":"1.0.0", "forbiddenVersions":["1.1.0"]}],
                  "alveoli": [{
                    "name":"main", "version":"1.0", "chainDependencies":true,
                    "placeholders":{"key":"value"},
                    "dependencies":[{"name":"dep", "location":"group:artifact:1"}],
                    "descriptors":[{
                    "includeIf":{"operator":"ANY", "conditions":[{"type":"SYSTEM_PROPERTY", "key":"enabled", "value":"true", "negate":true}]},
                      "name":"deployment", "type":"kubernetes", "interpolate":false, "await":true,
                      "helm":{"chart":"chart", "releaseName":"release", "resolveDependencies":false},
                      "awaitConditions":[{"operator":"ALL", "command":"apply", "conditions":[{
                        "type":"JSON_POINTER", "pointer":"/status/ready", "operatorType":"EQUALS", "value":"true"
                      }]}]
                    }],
                    "patches":[{"descriptorName":"deployment", "interpolate":true, "patch":[
                      {"op":"add", "path":"/metadata/labels", "value":{"test":"yes", "enabled":true, "number":2}}
                    ]}]
                  }]
                }
                """);
        final var encoded = mapper.toString(manifest);
        final var decoded = mapper.fromString(Manifest.class, encoded);
        assertEquals(manifest, decoded);
        final var alveolus = decoded.alveoli().get(0);
        assertEquals(
                Manifest.ConditionType.SYSTEM_PROPERTY,
                alveolus.descriptors().get(0).includeIf().conditions().get(0).type());
        assertEquals("kubernetes", alveolus.descriptors().get(0).type());
        assertFalse(alveolus.descriptors().get(0).interpolate());
        assertEquals("value", alveolus.placeholders().get("key"));
        assertTrue(encoded.contains("\"operatorType\":\"EQUALS\""));
    }

    @Test
    void defaultsAndUnknownProperties() {
        final var descriptor = mapper.fromString(
                Manifest.Descriptor.class, "{\"name\":\"test\",\"futureField\":{\"nested\":[1,true,null]}}");
        assertEquals("kubernetes", descriptor.type());
        assertFalse(descriptor.await());
        assertFalse(descriptor.hasInterpolateValue());
        assertEquals(
                Manifest.ConditionOperator.ALL,
                mapper.fromString(Manifest.Conditions.class, "{}").operator());
        assertEquals(
                Manifest.ConditionType.ENV,
                mapper.fromString(Manifest.Condition.class, "{}").type());
        assertEquals(1, mapper.fromString(State.class, "{}").version());
        assertTrue(mapper.fromString(State.class, "{}").resources().isEmpty());
    }

    @Test
    void genericArraysUseFusionGenericTrees() {
        final var expected = List.of("first", "second");
        final var json = "[\"first\",\"second\"]";
        assertEquals(expected, mapper.fromString(Object.class, json));
        assertEquals(expected, mapper.read(Object.class, new StringReader(json)));
        assertEquals("[\"value\",true,2,null]", mapper.toString(Arrays.asList("value", true, 2, null)));
    }

    @Test
    void stateRoundTrip() {
        final var state = new State(2, List.of(new State.Resource("/api/v1/namespaces/default/pods/test")));
        assertEquals(state, mapper.fromString(State.class, mapper.toString(state)));
    }

    @Test
    void sparseAndNullModelsUseConstructorDefaults() {
        for (final var json : List.of("{}", "{\"type\":null,\"interpolate\":null,\"awaitConditions\":null}")) {
            final var descriptor = mapper.fromString(Manifest.Descriptor.class, json);
            assertEquals("kubernetes", descriptor.type());
            assertNull(descriptor.interpolate());
            assertNull(descriptor.awaitConditions());
            assertFalse(descriptor.await());
            assertFalse(descriptor.awaitOnDelete());
            assertEquals(descriptor, mapper.fromString(Manifest.Descriptor.class, mapper.toString(descriptor)));
        }
        final var condition =
                mapper.fromString(Manifest.AwaitCondition.class, "{\"pointer\":null,\"operatorType\":null}");
        assertEquals("/", condition.pointer());
        assertEquals(Manifest.JsonPointerOperator.EQUALS, condition.operatorType());
        assertEquals(
                Manifest.ConditionOperator.ALL,
                mapper.fromString(Manifest.AwaitConditions.class, "{}").operator());
        assertTrue(mapper.fromString(Manifest.HelmConfig.class, "{\"resolveDependencies\":null}")
                .resolveDependencies());
        assertFalse(mapper.fromString(Manifest.HelmConfig.class, "{\"resolveDependencies\":false}")
                .resolveDependencies());
        final var state = mapper.fromString(State.class, "{\"version\":null,\"resources\":null}");
        assertEquals(1, state.version());
        assertEquals(List.of(), state.resources());
        assertEquals(0, mapper.fromString(State.class, "{\"version\":0}").version());
    }

    @Test
    void awaitConditionPreservesDynamicValueTypes() {
        for (final var json : List.of("1", "1.25", "\"ready\"", "true", "null", "{\"key\":1}", "[1,\"ready\",null]")) {
            final var condition = mapper.fromString(Manifest.AwaitCondition.class, "{\"value\":" + json + "}");
            assertEquals(mapper.fromString(Object.class, json), condition.value());
            assertEquals(condition, mapper.fromString(Manifest.AwaitCondition.class, mapper.toString(condition)));
        }
        assertInstanceOf(
                BigDecimal.class,
                mapper.fromString(Manifest.AwaitCondition.class, "{\"value\":1}")
                        .value());
    }

    @Test
    void topLevelNullModels() {
        assertNull(mapper.fromString(Manifest.class, "null"));
        assertNull(mapper.fromString(Sarif.class, "null"));
        assertNull(mapper.fromString(State.class, "null"));
    }

    @Test
    void sarifUsesDeclaredWireNamesAndOrder() {
        final var json =
                "{\"version\":\"2.1.0\",\"$schema\":\"https://schemastore.azurewebsites.net/schemas/json/sarif-2.1.0-rtm.5.json\",\"runs\":["
                        + "{\"tool\":{\"driver\":{\"name\":\"BundleBee\",\"informationUri\":\"https://www.yupiik.io/bundlebee/\",\"rules\":["
                        + "{\"id\":\"no-latest\",\"shortDescription\":{\"text\":\"Avoid latest\"},\"helpUri\":\"https://example.org/rule\",\"properties\":{\"severity\":\"warning\"}}]}},"
                        + "\"artifacts\":[{\"location\":{\"uri\":\"deployment.yaml\"}}],\"results\":[{\"level\":\"warning\",\"text\":{\"text\":\"Pin image version\"},"
                        + "\"ruleId\":\"no-latest\",\"ruleIndex\":0,\"locations\":[{\"physicalLocation\":{\"artifactLocation\":\"deployment.yaml\",\"index\":0}}]}]}]}";
        final var sarif = mapper.fromString(Sarif.class, json);
        assertEquals("2.1.0", sarif.version());
        assertEquals(
                "no-latest", sarif.runs().get(0).tool().driver().rules().get(0).id());
        assertEquals(
                "Pin image version", sarif.runs().get(0).results().get(0).text().text());
        assertEquals(json, mapper.toString(sarif));
        assertEquals(sarif, mapper.fromString(Sarif.class, mapper.toString(sarif)));
        final var artifactLocation = new CompleteLocation.ArtifactLocation("deployment.yaml", 0);
        assertEquals(
                artifactLocation,
                mapper.fromString(CompleteLocation.ArtifactLocation.class, mapper.toString(artifactLocation)));
    }

    @Test
    void rebuildingDoesNotMutateOriginalModels() {
        final var original = mapper.fromString(
                Manifest.class, "{\"alveoli\":[{\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]}");
        final var alveolus = original.alveoli().get(0);
        final var descriptor = alveolus.descriptors().get(0);
        final var updated =
                original.withAlveoli(List.of(alveolus.withDescriptors(List.of(descriptor.withLocation("other")))));
        assertNull(descriptor.location());
        assertEquals("other", updated.alveoli().get(0).descriptors().get(0).location());
        assertThrows(
                UnsupportedOperationException.class, () -> original.alveoli().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> alveolus.descriptors().clear());
    }
}
