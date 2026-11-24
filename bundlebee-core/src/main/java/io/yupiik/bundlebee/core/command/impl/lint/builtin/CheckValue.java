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
package io.yupiik.bundlebee.core.command.impl.lint.builtin;

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.ERROR;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

public abstract class CheckValue extends CheckByKind {
    protected final Map<String, Ptr> pointers;
    protected final Map<String, Condition> conditions;
    protected final boolean ignoreIfMissing;

    protected CheckValue(
            final Set<String> supportedKinds, final Map<String, String> jsonPointers, final boolean ignoreIfMissing) {
        this(supportedKinds, jsonPointers, null, ignoreIfMissing);
    }

    protected CheckValue(
            final Set<String> supportedKinds,
            final Map<String, String> jsonPointers,
            final Map<String, Condition> conditions) {
        this(supportedKinds, jsonPointers, conditions, false);
    }

    protected CheckValue(
            final Set<String> supportedKinds,
            final Map<String, String> jsonPointers,
            final Map<String, Condition> conditions,
            final boolean ignoreIfMissing) {
        super(supportedKinds);
        this.pointers = jsonPointers.entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> new Ptr(e.getValue(), new GenericJsonPointer(e.getValue()))));
        this.conditions = conditions;
        this.ignoreIfMissing = ignoreIfMissing;
        if (this.conditions != null) {
            this.conditions.values().stream()
                    .filter(it -> it.pointer != null)
                    .forEach(v -> v.jsonPointer = new GenericJsonPointer(v.pointer));
        }
    }

    @Override
    public Stream<LintError> validateSync(final LintableDescriptor descriptor) {
        final var kind = descriptor.kind();
        final var ptr = pointers.get(kind);
        try {
            final var value = getValue(descriptor, ptr);
            if (value == null) {
                // ignore if missing
                return Stream.empty();
            }
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
            return Stream.of(new LintError(ERROR, "No \'" + ptr.ptr + "\' in \'" + descriptor.getName() + "\'"));
        }
    }

    private Object getValue(final LintableDescriptor descriptor, final Ptr ptr) {
        try {
            return ptr.pointer.apply(descriptor.getDescriptor());
        } catch (final RuntimeException re) {
            if (ignoreIfMissing) {
                return null;
            }
            throw re;
        }
    }

    private boolean shouldSkip(final String kind, final LintableDescriptor descriptor) {
        final var condition = conditions == null ? null : conditions.get(kind);
        try {
            return condition != null
                    && !condition.tester.test(
                            condition.jsonPointer == null
                                    ? null
                                    : condition.jsonPointer.apply(descriptor.getDescriptor()));
        } catch (final RuntimeException re) {
            return !condition.tester.test(null);
        }
    }

    protected boolean ignoreError(final LintableDescriptor descriptor) {
        return false;
    }

    protected abstract Stream<LintError> doValidate(LintableDescriptor descriptor, Object value);

    protected static class Ptr {
        private final String ptr;
        private final GenericJsonPointer pointer;

        public Ptr(final String ptr, final GenericJsonPointer pointer) {
            this.ptr = ptr;
            this.pointer = pointer;
        }
    }

    protected static class Condition {
        public static final Condition IGNORE_IF_MISSING = new Condition("/", v -> v != null);
        // value to test and pass to tester, if null, tester will get null as input
        private final String pointer;
        // returns true if should fail on missing pointer value
        private final Predicate<Object> tester;
        // internal
        private GenericJsonPointer jsonPointer;

        public Condition(final String pointer, final Predicate<Object> tester) {
            this.pointer = pointer;
            this.tester = tester;
        }
    }
}
