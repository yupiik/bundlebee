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
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
class ConditionEvaluatorTest {
    private final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon-.skip-cdi", true));
    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    @ParameterizedTest
    @CsvSource({
            "'{\"operator\":\"ALL\",\"conditions\":[]}',true",
            "'{\"operator\":\"ALL\",\"conditions\":[{\"type\":\"ENV\",\"key\":\"TEST_ENV_VAR\",\"value\":\"set\"}]}',true",
            "'{\"operator\":\"ALL\",\"conditions\":[{\"type\":\"ENV\",\"key\":\"TEST_ENV_VAR\",\"value\":\"set2\"}]}',false",
            "'{\"operator\":\"ALL\",\"conditions\":[{\"key\":\"TEST_ENV_VAR\",\"value\":\"set\"}]}',true",
            "'{\"operator\":\"ALL\",\"conditions\":[{\"key\":\"TEST_ENV_VAR\",\"value\":\"set2\"}]}',false",
            "'{\"conditions\":[{\"key\":\"TEST_ENV_VAR\",\"value\":\"set2\",\"negate\":true}]}',true",
            "'{\"conditions\":[]}',true",
            "'{\"operator\":\"ALL\"}',true",
            "'{\"operator\":\"ANY\"}',false",
            "'{}',true",
    })
    void eval(final String cond, final boolean expected) {
        assertEquals(expected, evaluator.test(jsonb.fromJson(cond, Manifest.Conditions.class)), cond);
    }
}
