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

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

@ApplicationScoped
public class ConditionJsonEvaluator {

    public ConditionJsonEvaluator(final JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    private final JsonMapper jsonMapper;

    private final Logger logger = Logger.getLogger(getClass().getName());

    public boolean evaluate(final Manifest.AwaitCondition condition, final Map<String, Object> body) {
        try {
            switch (condition.type()) {
                case JSON_POINTER: {
                    // todo: extend JSON Pointer spec to enable to traverse arrays (any item)?
                    final var conditionPointer = condition.pointer();
                    final var pointer = new GenericJsonPointer(conditionPointer);
                    final var json = pointer.apply(body);
                    final var evaluated = stringify(json);
                    final var result = evaluate(condition.operatorType(), stringify(condition.value()), evaluated);
                    logger.finest(() -> condition + "=" + result + " from JSON=" + body + " and evaluation=" + json);
                    return result;
                }
                case STATUS_CONDITION:
                    final var conditions = new GenericJsonPointer("/status/conditions").apply(body);
                    final var result = conditions instanceof List<?> values
                            && values.stream()
                                    .filter(it -> it instanceof Map<?, ?>)
                                    .map(it -> (Map<?, ?>) it)
                                    .anyMatch(it -> it.get("type") instanceof String type
                                            && Objects.equals(type, condition.conditionType())
                                            && Objects.equals(
                                                    stringify(it.get("status")), stringify(condition.value())));
                    logger.finest(() -> condition + "=" + result + " from JSON=" + body);
                    return result;
                default:
                    throw new IllegalArgumentException("Unsupported type: " + condition);
            }
        } catch (final IllegalStateException | IllegalArgumentException e) {
            return condition.type() == Manifest.AwaitConditionType.JSON_POINTER
                    && condition.operatorType() == Manifest.JsonPointerOperator.MISSING;
        }
    }

    private String stringify(final Object json) {
        return json == null ? null : json instanceof String value ? value : jsonMapper.toString(json);
    }

    private boolean evaluate(final Manifest.JsonPointerOperator type, final String value, final String evaluated) {
        switch (type) {
            case EQUALS:
                return Objects.equals(value, evaluated);
            case NOT_EQUALS:
                return !Objects.equals(value, evaluated);
            case EQUALS_IGNORE_CASE:
                return evaluated != null && evaluated.equalsIgnoreCase(value);
            case NOT_EQUALS_IGNORE_CASE:
                return evaluated == null || !evaluated.equalsIgnoreCase(value);
            case CONTAINS:
                return evaluated != null && value != null && evaluated.contains(value);
            case EXISTS:
                return true;
            case MISSING:
                return false;
            default:
                throw new IllegalArgumentException("Unsupported comparison type: " + type);
        }
    }
}
