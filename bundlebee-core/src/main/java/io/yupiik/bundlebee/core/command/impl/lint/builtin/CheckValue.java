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

import javax.json.Json;
import javax.json.JsonPointer;
import javax.json.JsonValue;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.ERROR;
import static java.util.stream.Collectors.toMap;

public abstract class CheckValue extends CheckByKind {
    private final Map<String, JsonPointer> pointers;

    protected CheckValue(final Set<String> supportedKinds, final Map<String, String> jsonPointers) {
        super(supportedKinds);
        this.pointers = jsonPointers.entrySet().stream().collect(toMap(Map.Entry::getKey, e -> Json.createPointer(e.getValue())));
    }

    @Override
    public Stream<LintError> validate(final LintableDescriptor descriptor) {
        final var ptr = pointers.get(descriptor.getDescriptor().getString("kind", ""));
        try {
            return doValidate(descriptor, ptr.getValue(descriptor.getDescriptor()));
        } catch (final RuntimeException re) {
            return Stream.of(new LintError(ERROR, "No '" + ptr.toString() + "' in '" + descriptor.getName() + "'"));
        }
    }

    protected abstract Stream<LintError> doValidate(LintableDescriptor descriptor, JsonValue value);
}
