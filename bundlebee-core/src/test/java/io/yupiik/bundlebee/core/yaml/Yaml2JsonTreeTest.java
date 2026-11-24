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
package io.yupiik.bundlebee.core.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.model.APIResourceList;
import io.yupiik.bundlebee.core.test.BaseTestModule;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.composer.ComposerException;

@FusionSupport(modules = Yaml2JsonTreeTest.TestConfigurationModule.class)
class Yaml2JsonTreeTest {
    public static class TestConfigurationModule extends BaseTestModule {
        public TestConfigurationModule() {
            super(Map.of("bundlebee.kube.skipLoadConfig", "true"));
        }
    }

    @Fusion
    private JsonMapper mapper;

    @Fusion
    private Yaml2JsonConverter converter;

    @Fusion
    private RuntimeContainer container;

    @Test
    void yamlLookupsAreIndependent() {
        try (final var first = container.lookup(Yaml.class);
                final var second = container.lookup(Yaml.class)) {
            assertNotSame(first.instance(), second.instance());
            assertEquals(Map.of("first", 1), first.instance().load("first: 1"));
            assertEquals(Map.of("second", 2), second.instance().load("second: 2"));
        }
    }

    @Test
    void timestampsUseLegacyUtcStrings() {
        // Baseline JSON-B uses ISO_DATE_TIME in the named UTC zone, not the JVM default zone.
        final var timestamps = Map.of(
                "2024-01-01", "2024-01-01T00:00:00Z[UTC]",
                "2001-12-15T02:59:43.123Z", "2001-12-15T02:59:43.123Z[UTC]",
                "2001-12-14t21:59:43.120-05:00", "2001-12-15T02:59:43.12Z[UTC]",
                "2001-12-15 02:59:43", "2001-12-15T02:59:43Z[UTC]",
                "2024-07-01T00:00:00Z", "2024-07-01T00:00:00Z[UTC]");
        timestamps.forEach((yaml, expected) -> {
            assertEquals(expected, converter.convert(Object.class, yaml));
            final var loaded = converter.convert(Map.class, "value: " + yaml + "\n");
            assertEquals(Map.of("value", expected), loaded);
            assertEquals("{\"value\":\"" + expected + "\"}", mapper.toString(loaded));
        });
    }

    @Test
    void binaryValuesUseLegacySignedNumberArrays() {
        final var signed = List.of(0, 127, -128, -1);
        assertEquals(signed, converter.convert(Object.class, "!!binary AH+A/w=="));
        assertEquals(List.of(), converter.convert(Object.class, "!!binary ''"));
        final var loaded = converter.convert(Map.class, "value: !!binary SGVsbG8=\n");
        assertEquals("{\"value\":[72,101,108,108,111]}", mapper.toString(loaded));
        assertEquals(
                List.of(Map.of("data", signed)),
                converter.convert(List.class, "- data: !!binary |\n    AH+A\n    /w==\n"));
        final var documents =
                converter.convert(Object.class, "value: 2024-01-01\n---\n- !!binary AH+A/w==\n- 2024-01-01\n");
        assertEquals(
                List.of(Map.of("value", "2024-01-01T00:00:00Z[UTC]"), List.of(signed, "2024-01-01T00:00:00Z[UTC]")),
                documents);
        assertEquals(
                "[{\"value\":\"2024-01-01T00:00:00Z[UTC]\"},[[0,127,-128,-1],\"2024-01-01T00:00:00Z[UTC]\"]]",
                mapper.toString(documents));
    }

    @Test
    void timestampsAndBinaryValuesCanBeConvertedToGeneratedModels() {
        final var loaded = converter.convert(
                Manifest.AwaitCondition.class,
                "conditionType: 2001-12-14t21:59:43.120-05:00\nvalue:\n  data: !!binary AH+A/w==\n  created: 2024-01-01\n");
        assertEquals("2001-12-15T02:59:43.12Z[UTC]", loaded.conditionType());
        assertEquals(
                Map.of(
                        "data",
                        List.of(BigDecimal.ZERO, new BigDecimal("127"), new BigDecimal("-128"), new BigDecimal("-1")),
                        "created",
                        "2024-01-01T00:00:00Z[UTC]"),
                loaded.value());
    }

    @Test
    void genericSequencesPreserveMixedValuesAndNulls() {
        final var expected = Arrays.asList("text", new BigDecimal("2"), true, null, Map.of("key", "value"));
        assertEquals(expected, converter.convert(Object.class, "- text\n- 2\n- true\n- null\n- key: value\n"));
        assertEquals(expected, converter.convert(List.class, "- text\n- 2\n- true\n- null\n- key: value\n"));
        final var map = converter.convert(Map.class, "items: [text, 2, true, null]\nnullable: null\n");
        assertFalse(map.containsKey("nullable"));
        assertEquals(Map.of("nested", Map.of()), converter.convert(Map.class, "nested:\n  absent: null\n"));
        assertEquals(expected.subList(0, 4), map.get("items"));
        assertNull(converter.convert(Object.class, ""));
    }

    @Test
    void multipleDocumentsCanContainMixedValues() {
        final var yaml = "name: first\n---\n- text\n- 2\n- null\n---\nnull\n";
        final var expected =
                Arrays.asList(Map.of("name", "first"), Arrays.asList("text", new BigDecimal("2"), null), null);
        assertEquals(expected, converter.convert(Object.class, yaml));
        assertEquals(expected, converter.convert(List.class, yaml));
        assertThrows(ComposerException.class, () -> converter.convert(Map.class, yaml));
    }

    @Test
    void knownModelsUseGeneratedFusionCodecs() {
        final var loaded = converter.convert(
                APIResourceList.class,
                "kind: APIResourceList\napiVersion: v1\nresources:\n- kind: ConfigMap\n  name: configmaps\n  namespaced: true\n  verbs: [get, list]\n  unknown: ignored\n");
        assertEquals("APIResourceList", loaded.kind());
        assertEquals("configmaps", loaded.resources().get(0).name());
        assertTrue(loaded.resources().get(0).namespaced());
        assertEquals(List.of("get", "list"), loaded.resources().get(0).verbs());
    }

    @Test
    void sharedConverterParsesConcurrentDocuments() throws Exception {
        final var workers = 8;
        final var barrier = new CyclicBarrier(workers);
        final var executor = Executors.newFixedThreadPool(workers);
        final var tasks = new ArrayList<Future<?>>();
        try {
            for (var worker = 0; worker < workers; worker++) {
                final var workerId = worker;
                tasks.add(executor.submit(() -> {
                    // Start all workers together without sleeps, including the lazy loadAll path.
                    barrier.await();
                    for (var iteration = 0; iteration < 40; iteration++) {
                        final var id = workerId + "-" + iteration;
                        final var map = Map.of(
                                "id",
                                id,
                                "created",
                                "2024-01-01T00:00:00Z[UTC]",
                                "data",
                                List.of(0, 127, -128, -1),
                                "nested",
                                Map.of(),
                                "values",
                                Arrays.asList("text", new BigDecimal("2"), true, null));
                        final var yaml = "id: '" + id + "'\ncreated: 2024-01-01\ndata: !!binary AH+A/w==\n"
                                + "nested: {absent: null}\nvalues: [text, 2, true, null]\nabsent: null\n";
                        assertEquals(map, converter.convert(Map.class, yaml));
                        assertEquals(
                                Arrays.asList(id, map, null),
                                converter.convert(List.class, "- '" + id + "'\n-\n" + yaml.indent(2) + "- null\n"));
                        final var documents = Arrays.asList(map, Arrays.asList(id, null), null);
                        final var multiYaml = yaml + "---\n- '" + id + "'\n- null\n---\nnull\n";
                        assertEquals(documents, converter.convert(Object.class, multiYaml));
                        assertEquals(documents, converter.convert(List.class, multiYaml));
                    }
                    return null;
                }));
            }
            for (final var task : tasks) {
                task.get();
            }
        } finally {
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
        }
    }
}
