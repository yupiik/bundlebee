/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.json.JsonProducer;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.inject.Inject;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@Cdi(classes = {JsonProducer.class, ConditionJsonEvaluator.class})
@TestInstance(PER_CLASS)
class ConditionJsonEvaluatorTest {
    @Inject
    private ConditionJsonEvaluator evaluator;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @ParameterizedTest
    @CsvSource({
            "'{\"type\":\"JSON_POINTER\",\"pointer\":\"/v\",\"value\":\"foo\"}','{\"v\":\"foo\"}',true",
            "'{\"type\":\"JSON_POINTER\",\"pointer\":\"/v\",\"value\":\"foo\"}','{\"v\":\"bar\"}',false",
            "'{\"type\":\"JSON_POINTER\",\"pointer\":\"/v\",\"value\":\"foo\"}','{}',false",
            "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":true}]}}',true",
            "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Foo\",\"status\":true},{\"type\":\"Ready\",\"status\":true}]}}',true",
            "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Foo\",\"status\":true},{\"type\":\"Ready\",\"status\":false}]}}',false",
            "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[{\"type\":\"Foo\",\"status\":true}]}}',false",
            "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{\"conditions\":[]}}',false",
            "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{\"status\":{}}',false",
            "'{\"type\":\"STATUS_CONDITION\",\"conditionType\":\"Ready\",\"value\":\"true\"}','{}',false",
    })
    void eval(final String cond, final String input, final boolean expected) {
        try {
            assertEquals(expected, evaluator.evaluate(jsonb.fromJson(cond, Manifest.AwaitCondition.class), jsonb.fromJson(input, JsonObject.class)));
        } catch (final JsonException je) {
            assertFalse(expected);
        }
    }
}
