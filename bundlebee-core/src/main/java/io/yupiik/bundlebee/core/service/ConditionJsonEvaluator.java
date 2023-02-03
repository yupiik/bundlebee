/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.bundlebee.core.qualifier.BundleBee;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.spi.JsonProvider;
import java.util.Objects;
import java.util.logging.Logger;

@ApplicationScoped
public class ConditionJsonEvaluator {
    private final Logger logger = Logger.getLogger(getClass().getName());

    @Inject
    @BundleBee
    private JsonProvider jsonProvider;

    public boolean evaluate(final Manifest.AwaitCondition condition, final JsonObject body) {
        switch (condition.getType()) {
            case JSON_POINTER: { // todo: extend JSON Pointer spec to enable to traverse arrays (any item)?
                final var conditionPointer = condition.getPointer();
                final var pointer = jsonProvider.createPointer(conditionPointer);
                final var json = pointer.getValue(body);
                final var evaluated = stringify(json);
                final var result = evaluate(condition.getOperatorType(), condition.getValue(), evaluated);
                logger.finest(() -> condition + "=" + result + " from JSON=" + body + " and evaluation=" + json);
                return result;
            }
            case STATUS_CONDITION:
                final var result = jsonProvider.createPointer("/status/conditions").getValue(body).asJsonArray().stream()
                        .map(JsonValue::asJsonObject)
                        .anyMatch(it -> Objects.equals(it.getString("type"), condition.getConditionType()) &&
                                Objects.equals(stringify(it.get("status")), condition.getValue()));
                logger.finest(() -> condition + "=" + result + " from JSON=" + body);
                return result;
            default:
                throw new IllegalArgumentException("Unsupported type: " + condition);
        }
    }

    private String stringify(final JsonValue json) {
        return json == null ? null : json.getValueType() == JsonValue.ValueType.STRING ?
                JsonString.class.cast(json).getString() :
                String.valueOf(json);
    }

    private boolean evaluate(final Manifest.JsonPointerOperator type, final String value, final String evaluated) {
        switch (type) {
            case EQUALS:
                return Objects.equals(value, evaluated);
            case NOT_EQUALS:
                return !Objects.equals(value, evaluated);
            case EQUALS_IGNORE_CASE:
                return evaluated.equalsIgnoreCase(value);
            case NOT_EQUALS_IGNORE_CASE:
                return !evaluated.equalsIgnoreCase(value);
            case CONTAINS:
                return evaluated.contains(value);
            case EXISTS:
                return true;
            default:
                throw new IllegalArgumentException("Unsupported comparison type: " + type);
        }
    }
}