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

import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.inject.Inject;
import javax.json.JsonBuilderFactory;

import static javax.json.JsonValue.EMPTY_JSON_OBJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@Cdi
@TestInstance(PER_CLASS)
class ContainerSanitizerTest {
    @Inject
    private ContainerSanitizer sanitizer;

    @Inject
    @BundleBee
    private JsonBuilderFactory json;

    @Test
    void noResources() {
        assertEquals(
                "{\"spec\":{\"containers\":[{}]}}",
                sanitizer.dropCpuResources("pods", json.createObjectBuilder()
                                .add("spec", json.createObjectBuilder()
                                        .add("containers", json.createArrayBuilder()
                                                .add(json.createObjectBuilder())))
                                .build())
                        .toString());
    }

    @Test
    void emptyResources() {
        assertEquals(
                "{\"spec\":{\"containers\":[{\"resources\":{}}]}}",
                sanitizer.dropCpuResources("pods", json.createObjectBuilder()
                                .add("spec", json.createObjectBuilder()
                                        .add("containers", json.createArrayBuilder()
                                                .add(json.createObjectBuilder()
                                                        .add("resources", EMPTY_JSON_OBJECT))))
                                .build())
                        .toString());
    }

    @Test
    void cpuOk() {
        assertEquals(
                "{\"spec\":{\"containers\":[{\"resources\":{\"requests\":{\"cpu\":1}}}]}}",
                sanitizer.dropCpuResources("pods", json.createObjectBuilder()
                                .add("spec", json.createObjectBuilder()
                                        .add("containers", json.createArrayBuilder()
                                                .add(json.createObjectBuilder()
                                                        .add("resources", json.createObjectBuilder()
                                                                .add("requests", json.createObjectBuilder()
                                                                        .add("cpu", 1))))))
                                .build())
                        .toString());
    }

    @Test
    void cpuNull() {
        assertEquals(
                "{\"spec\":{\"containers\":[{\"resources\":{}}]}}",
                sanitizer.dropCpuResources("pods", json.createObjectBuilder()
                                .add("spec", json.createObjectBuilder()
                                        .add("containers", json.createArrayBuilder()
                                                .add(json.createObjectBuilder()
                                                        .add("resources", json.createObjectBuilder()
                                                                .add("requests", json.createObjectBuilder()
                                                                        .addNull("cpu"))))))
                                .build())
                        .toString());
    }

    @Test
    void cpuNullWithMemory() {
        assertEquals(
                "{\"spec\":{\"containers\":[{\"resources\":{\"requests\":{\"memory\":\"512Mi\"}}}]}}",
                sanitizer.dropCpuResources("pods", json.createObjectBuilder()
                                .add("spec", json.createObjectBuilder()
                                        .add("containers", json.createArrayBuilder()
                                                .add(json.createObjectBuilder()
                                                        .add("resources", json.createObjectBuilder()
                                                                .add("requests", json.createObjectBuilder()
                                                                        .addNull("cpu")
                                                                        .add("memory", "512Mi"))))))
                                .build())
                        .toString());
    }

    @Test
    void nullArgsAndCommand() {
        assertEquals(
                "{\"spec\":{\"containers\":[{\"resources\":{\"requests\":{\"memory\":\"512Mi\"}}}]}}",
                sanitizer.dropCpuResources("pods", json.createObjectBuilder()
                                .add("spec", json.createObjectBuilder()
                                        .add("containers", json.createArrayBuilder()
                                                .add(json.createObjectBuilder()
                                                        .addNull("command")
                                                        .addNull("args")
                                                        .add("resources", json.createObjectBuilder()
                                                                .add("requests", json.createObjectBuilder()
                                                                        .addNull("cpu")
                                                                        .add("memory", "512Mi"))))))
                                .build())
                        .toString());
    }
}
