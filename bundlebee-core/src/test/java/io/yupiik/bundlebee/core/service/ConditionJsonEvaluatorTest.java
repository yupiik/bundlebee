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
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@FusionSupport
@TestInstance(PER_CLASS)
class ConditionJsonEvaluatorTest {

    @Fusion
    private ConditionJsonEvaluator evaluator;

    @Fusion
    private JsonMapper jsonb;

    @Test
    void comparesStructuredValuesAsJson() {
        final var objectCondition = new Manifest.AwaitCondition(
                Manifest.AwaitConditionType.JSON_POINTER, "/value", null, null, "{\"key\":\"value\"}", null);
        assertEquals(true, evaluator.evaluate(objectCondition, Map.of("value", Map.of("key", "value"))));
        final var arrayCondition = new Manifest.AwaitCondition(
                Manifest.AwaitConditionType.JSON_POINTER, "/value", null, null, "[\"value\",true]", null);
        assertEquals(true, evaluator.evaluate(arrayCondition, Map.of("value", List.of("value", true))));
    }

    @ParameterizedTest
    @CsvSource({
        "'{\"type\":\"JSON_POINTER\",\"pointer\":\"/v\",\"value\":1}','{\"v\":1}',true",
        "'{\"type\":\"JSON_POINTER\",\"pointer\":\"/v\",\"value\":\"foo\"}','{\"v\":\"foo\"}',true",
        "'{\"type\":\"JSON_POINTER\",\"pointer\":\"/v\",\"value\":\"foo\"}','{\"v\":\"bar\"}',false",
        "'{\"type\":\"JSON_POINTER\",\"pointer\":\"/v\",\"value\":\"foo\"}','{}',false",
        "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":true}]}}',true",
        "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Foo\",\"status\":true},{\"type\":\"Ready\",\"status\":true}]}}',true",
        "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Foo\",\"status\":true},{\"type\":\"Ready\",\"status\":false}]}}',false",
        "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Foo\",\"status\":true}]}}',false",
        "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[]}}',false",
        "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{}}',false",
        "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{}',false"
    })
    void eval(final String cond, final String input, final boolean expected) {
        assertEquals(
                expected,
                evaluator.evaluate(
                        jsonb.fromString(Manifest.AwaitCondition.class, cond), jsonb.fromString(Map.class, input)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "true",
                "42",
                "\"invalid\"",
                "{}",
                "[null,true,42,\"invalid\",{\"type\":42,\"status\":true}]"
            })
    void malformedStatusConditionsDoNotMatch(final String conditions) {
        final var condition = new Manifest.AwaitCondition(
                Manifest.AwaitConditionType.STATUS_CONDITION, null, null, "Ready", "true", null);
        assertEquals(
                false,
                evaluator.evaluate(
                        condition, jsonb.fromString(Map.class, "{\"status\":{\"conditions\":" + conditions + "}}")));
        assertEquals(
                true,
                evaluator.evaluate(
                        condition,
                        jsonb.fromString(
                                Map.class,
                                "{\"status\":{\"conditions\":[" + conditions
                                        + ",{\"type\":\"Ready\",\"status\":true}]}}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EQUALS_IGNORE_CASE", "NOT_EQUALS_IGNORE_CASE", "CONTAINS"})
    void treatsNullPointerTargetsAsMissingWithoutThrowing(final String operator) {
        final var condition = jsonb.fromString(
                Manifest.AwaitCondition.class,
                "{\"type\":\"JSON_POINTER\",\"pointer\":\"/value\",\"operatorType\":\"" + operator
                        + "\",\"value\":\"ready\"}");
        assertEquals(false, evaluator.evaluate(condition, jsonb.fromString(Map.class, "{\"value\":null}")));
    }
}
