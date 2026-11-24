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

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

@FusionSupport
class Yaml2JsonConverterTest {

    @Fusion
    private Yaml2JsonConverter converter;

    @Test
    void convert() {
        final var yaml = readYaml();
        final var loaded = converter.convert(Map.class, yaml);
        assertEquals("minikube", new GenericJsonPointer("/current-context").apply(loaded));
        assertEquals("https://192.168.49.2:8443", new GenericJsonPointer("/clusters/0/cluster/server").apply(loaded));
        assertEquals(
                "minikube.sigs.k8s.io",
                new GenericJsonPointer("/clusters/0/cluster/extensions/0/extension/provider").apply(loaded));
        assertEquals("default", new GenericJsonPointer("/contexts/0/context/namespace").apply(loaded));
        assertEquals(
                "minikube/profiles/minikube/client.key",
                new GenericJsonPointer("/users/0/user/client-key").apply(loaded));
    }

    @Test
    void convertTypedManifest() {
        final var loaded = converter.convert(
                Manifest.class, "alveoli:\n  - name: sample\n    descriptors:\n      - name: deployment\n");
        assertEquals("sample", loaded.alveoli().get(0).name());
        assertEquals("kubernetes", loaded.alveoli().get(0).descriptors().get(0).type());
    }

    @Test
    void convertMultipleDocuments() {
        final var loaded = converter.convert(Object.class, "name: first\n---\nname: second\n");
        assertEquals(List.of(Map.of("name", "first"), Map.of("name", "second")), loaded);
    }

    @Test
    void convertHeterogeneousSequence() {
        final var loaded = converter.convert(Object.class, "- text\n- 2\n- true\n- null\n");
        assertEquals(Arrays.asList("text", new BigDecimal("2"), true, null), loaded);
    }

    private String readYaml() {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("kubeconfig.minikube.sample")),
                StandardCharsets.UTF_8))) {
            return reader.lines().collect(joining("\n"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
