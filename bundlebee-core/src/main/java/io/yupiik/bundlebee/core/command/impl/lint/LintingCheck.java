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
package io.yupiik.bundlebee.core.command.impl.lint;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

public interface LintingCheck {
    String name();

    String description();

    String remediation();

    boolean accept(LintableDescriptor descriptor);

    Stream<LintError> validate(LintableDescriptor descriptor);

    /**
     * Enables to create validations with dependencies between descriptors, use {@link #accept(LintableDescriptor)} as a visitor.
     *
     * @return validation errors after all descriptors got visited.
     */
    default Stream<ContextualLintError> afterAll() {
        return Stream.empty();
    }

    @Getter
    @RequiredArgsConstructor
    class LintableDescriptor {
        private final String alveolus;
        private final String name;
        private final JsonObject descriptor;

        public boolean isKind(final String kind) {
            try {
                return kind().equals(kind);
            } catch (final RuntimeException re) {
                return false;
            }
        }

        public String kind() {
            return descriptor.getString("kind", "");
        }

        public String name() {
            return ofNullable(descriptor.getJsonObject("metadata"))
                    .map(JsonValue::asJsonObject)
                    .map(o -> o.getString("name", ""))
                    .orElse("");
        }

        public String namespace() {
            return ofNullable(descriptor.getJsonObject("metadata"))
                    .map(JsonValue::asJsonObject)
                    .map(o -> o.getString("namespace", ""))
                    .orElse("");
        }
    }
}
