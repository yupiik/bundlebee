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
package io.yupiik.bundlebee.core.command.impl.lint;

import static java.util.concurrent.CompletableFuture.completedStage;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public interface LintingCheck {
    String name();

    String description();

    String remediation();

    boolean accept(LintableDescriptor descriptor);

    CompletionStage<Stream<LintError>> validate(LintableDescriptor descriptor);

    /**
     * Enables to create validations with dependencies between descriptors, use {@link #accept(LintableDescriptor)} as a visitor.
     *
     * @return validation errors after all descriptors got visited.
     */
    default CompletionStage<Stream<ContextualLintError>> afterAll() {
        return completedStage(Stream.empty());
    }

    class LintableDescriptor {
        private final String alveolus;
        private final String name;
        private final Map<String, Object> descriptor;

        public boolean isKind(final String kind) {
            try {
                return kind().equals(kind);
            } catch (final RuntimeException re) {
                return false;
            }
        }

        public String kind() {
            return (descriptor.get("kind") instanceof String kindValue ? kindValue : "");
        }

        public String name() {
            return descriptor.get("metadata") instanceof Map<?, ?> metadata
                            && metadata.get("name") instanceof String value
                    ? value
                    : "";
        }

        public String namespace() {
            return descriptor.get("metadata") instanceof Map<?, ?> metadata
                            && metadata.get("namespace") instanceof String value
                    ? value
                    : "";
        }

        public String getAlveolus() {
            return this.alveolus;
        }

        public String getName() {
            return this.name;
        }

        public Map<String, Object> getDescriptor() {
            return this.descriptor;
        }

        public LintableDescriptor(final String alveolus, final String name, final Map<String, Object> descriptor) {
            this.alveolus = alveolus;
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}
