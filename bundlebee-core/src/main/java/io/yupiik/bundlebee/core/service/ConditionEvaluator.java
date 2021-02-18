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

import javax.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

@ApplicationScoped
public class ConditionEvaluator implements Predicate<Manifest.Conditions> {
    @Override
    public boolean test(final Manifest.Conditions conditions) {
        return conditions == null ||
                toOperator(conditions.getOperator(), ofNullable(conditions.getConditions())
                        .stream()
                        .flatMap(Collection::stream)
                        .map(this::evaluate));
    }

    private boolean evaluate(final Manifest.Condition condition) {
        final var evaluationResult = condition.getKey() == null || condition.getKey().isBlank() ||
                Objects.equals(toValue(condition.getValue()), read(condition.getType(), condition.getKey()));
        return condition.isNegate() != evaluationResult;
    }

    private String read(final Manifest.ConditionType type, final String key) {
        return Manifest.ConditionType.SYSTEM_PROPERTY == type ?
                System.getProperty(key, "") :
                ofNullable(System.getenv(key)).orElse("");
    }

    private String toValue(final String value) {
        return value == null ? "true" : value;
    }

    private boolean toOperator(final Manifest.ConditionOperator operator, final Stream<Boolean> stream) {
        return operator == Manifest.ConditionOperator.ANY ? stream.anyMatch(b -> b) : stream.allMatch(b -> b);
    }
}
