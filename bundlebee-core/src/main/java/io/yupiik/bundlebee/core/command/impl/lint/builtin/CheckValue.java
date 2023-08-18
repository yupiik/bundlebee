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
package io.yupiik.bundlebee.core.command.impl.lint.builtin;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import lombok.RequiredArgsConstructor;

import javax.json.JsonPointer;
import javax.json.JsonValue;
import javax.json.spi.JsonProvider;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.ERROR;
import static java.util.stream.Collectors.toMap;

public abstract class CheckValue extends CheckByKind {
    private static final JsonProvider PROVIDER = JsonProvider.provider();

    private final Map<String, Ptr> pointers;
    private final Map<String, Condition> conditions;

    protected CheckValue(final Set<String> supportedKinds, final Map<String, String> jsonPointers, final Map<String, Condition> conditions) {
        super(supportedKinds);
        this.pointers = jsonPointers.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> new Ptr(e.getValue(), PROVIDER.createPointer(e.getValue()))));
        this.conditions = conditions;
        if (this.conditions != null) {
            this.conditions.values().stream()
                    .filter(it -> it.pointer != null)
                    .forEach(v -> v.jsonPointer = PROVIDER.createPointer(v.pointer));
        }
    }

    @Override
    public Stream<LintError> validate(final LintableDescriptor descriptor) {
        final var kind = descriptor.getDescriptor().getString("kind", "");
        final var ptr = pointers.get(kind);
        try {
            final var value = ptr.pointer.getValue(descriptor.getDescriptor());
            if (conditions == null) {
                return doValidate(descriptor, value);
            }

            if (shouldSkip(kind, descriptor)) {
                return Stream.empty();
            }

            return doValidate(descriptor, value);
        } catch (final RuntimeException re) {
            if (shouldSkip(kind, descriptor)) {
                return Stream.empty();
            }

            if (ignoreError(descriptor)) {
                return Stream.empty();
            }

            return Stream.of(new LintError(ERROR, "No '" + ptr.ptr + "' in '" + descriptor.getName() + "'"));
        }
    }

    private boolean shouldSkip(final String kind, final LintableDescriptor descriptor) {
        final var condition = conditions.get(kind);
        try {
            return condition != null && !condition.tester.test(
                    condition.jsonPointer == null ? null : condition.jsonPointer.getValue(descriptor.getDescriptor()));
        } catch (final RuntimeException re) {
            return !condition.tester.test(JsonValue.NULL);
        }
    }

    protected boolean ignoreError(final LintableDescriptor descriptor) {
        return false;
    }

    protected abstract Stream<LintError> doValidate(LintableDescriptor descriptor, JsonValue value);

    @RequiredArgsConstructor
    private static class Ptr {
        private final String ptr;
        private final JsonPointer pointer;
    }

    @RequiredArgsConstructor
    protected static class Condition {
        private final String pointer;
        private final Predicate<JsonValue> tester;

        // internal
        private JsonPointer jsonPointer;
    }
}
