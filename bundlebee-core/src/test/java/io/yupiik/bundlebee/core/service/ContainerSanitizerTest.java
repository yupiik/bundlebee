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
package io.yupiik.bundlebee.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@FusionSupport
class ContainerSanitizerTest {
    @Fusion
    private ContainerSanitizer sanitizer;

    @Fusion
    private JsonMapper mapper;

    @ParameterizedTest
    @CsvSource({
        "'{}','{}'",
        "'{\"resources\":{}}','{\"resources\":{}}'",
        "'{\"resources\":{\"requests\":{\"cpu\":1}}}','{\"resources\":{\"requests\":{\"cpu\":1}}}'",
        "'{\"resources\":{\"requests\":{\"cpu\":null}}}','{\"resources\":{}}'",
        "'{\"resources\":{\"requests\":{\"cpu\":null,\"memory\":\"512Mi\"}}}','{\"resources\":{\"requests\":{\"memory\":\"512Mi\"}}}'",
        "'{\"args\":null,\"command\":null,\"resources\":{\"requests\":{\"cpu\":null,\"memory\":\"512Mi\"}}}','{\"resources\":{\"requests\":{\"memory\":\"512Mi\"}}}'",
        "'{\"resources\":{\"limits\":{\"cpu\":null},\"other\":null}}','{\"resources\":{\"other\":null}}'",
        "'{\"args\":[],\"command\":[\"run\"]}','{\"args\":[],\"command\":[\"run\"]}'"
    })
    void sanitizesContainersWithoutMutatingInput(final String container, final String expected) {
        final var input = "{\"spec\":{\"containers\":[" + container + "]}}";
        final var source = mapper.fromString(Map.class, input);
        final var result = sanitizer.dropCpuResources("pods", source);
        assertEquals(mapper.fromString(Object.class, "{\"spec\":{\"containers\":[" + expected + "]}}"), result);
        assertEquals(mapper.fromString(Object.class, input), source);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "true",
                "1",
                "\"invalid\"",
                "{}",
                "[null,1,\"invalid\"]",
                "[{\"resources\":true}]",
                "[{\"resources\":{\"requests\":null,\"limits\":42}}]"
            })
    void toleratesMalformedContainers(final String containers) {
        final var source = mapper.fromString(Map.class, "{\"spec\":{\"containers\":" + containers + "}}");
        assertEquals(source, sanitizer.dropCpuResources("pods", source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deployments", "daemonsets", "jobs", "cronjobs"})
    void sanitizesPodTemplatesAndInitContainers(final String kind) {
        final var spec =
                "{\"containers\":[{\"resources\":{\"limits\":{\"cpu\":null}}}],\"initContainers\":[{\"args\":null}]}";
        final var expectedSpec = "{\"containers\":[{\"resources\":{}}],\"initContainers\":[{}]}";
        final var template = "{\"spec\":{\"template\":{\"spec\":" + spec + "}}}";
        final var expectedTemplate = "{\"spec\":{\"template\":{\"spec\":" + expectedSpec + "}}}";
        final var input = "cronjobs".equals(kind) ? "{\"spec\":{\"jobTemplate\":" + template + "}}" : template;
        final var expected =
                "cronjobs".equals(kind) ? "{\"spec\":{\"jobTemplate\":" + expectedTemplate + "}}" : expectedTemplate;
        assertEquals(
                mapper.fromString(Object.class, expected),
                sanitizer.dropCpuResources(kind, mapper.fromString(Map.class, input)));
    }

    @Test
    void leavesUnsupportedKindsAndMissingPathsUnchanged() {
        final var source = Map.<String, Object>of("data", Map.of("key", "value"));
        assertEquals(source, sanitizer.dropCpuResources("configmaps", source));
        assertEquals(source, sanitizer.dropCpuResources("pods", source));
    }
}
