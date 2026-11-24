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

import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@DefaultScoped
public class MismatchingSelector extends CheckValue {

    public MismatchingSelector() {
        super(
                Set.of("DeploymentConfig", "Deployment"),
                Map.of("DeploymentConfig", "/spec", "Deployment", "/spec"),
                false);
    }

    @Override
    public String name() {
        return "mismatching-selector";
    }

    @Override
    public String description() {
        return "Indicates when deployment selectors fail to match the pod template labels.";
    }

    @Override
    public String remediation() {
        return "Confirm that your deployment selector correctly matches the labels in its pod template.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final Object value) {
        if (!(value instanceof Map<?, ?> root)) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/selector found"));
        }
        var selector = (root.get("selector") instanceof Map<?, ?> selectorValue ? selectorValue : null);
        if (selector == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/selector found"));
        }
        if ("Deployment".equals(descriptor.kind())) {
            // DeploymentConfig does not have this additional layer
            selector = (selector.get("matchLabels") instanceof Map<?, ?> matchLabelsValue ? matchLabelsValue : null);
            if (selector == null) {
                return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/selector/matchLabels found"));
            }
        }
        if (!(root.get("template") instanceof Map<?, ?> template)) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/template found"));
        }
        if (!(template.get("metadata") instanceof Map<?, ?> metadata)) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/template/metadata found"));
        }
        if (!(metadata.get("labels") instanceof Map<?, ?> labels)) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/template/metadata/labels found"));
        }
        final var missing = selector.entrySet().stream()
                .filter(it -> it.getValue() instanceof String)
                .filter(it -> !Objects.equals(it.getValue(), labels.get(it.getKey())))
                .collect(toMap(Map.Entry::getKey, it -> ((String) it.getValue())));
        if (missing.isEmpty()) {
            return Stream.empty();
        }
        return Stream.of(new LintError(
                LintError.LintLevel.ERROR,
                "Selector does not match pod template, missing expected labels: " + missing));
    }
}
