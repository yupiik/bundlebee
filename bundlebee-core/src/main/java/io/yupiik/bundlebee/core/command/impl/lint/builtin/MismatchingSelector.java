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

import io.yupiik.bundlebee.core.command.impl.lint.LintError;

import javax.enterprise.context.Dependent;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

@Dependent
public class MismatchingSelector extends CheckValue {
    public MismatchingSelector() {
        super(

                Set.of("DeploymentConfig", "Deployment"),
                Map.of(
                        "DeploymentConfig", "/spec",
                        "Deployment", "/spec"),
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
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        final var root = value.asJsonObject();

        var selector = root.getJsonObject("selector");
        if (selector == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/selector found"));
        }
        if ("Deployment".equals(descriptor.kind())) { // DeploymentConfig does not have this additional layer
            selector = selector.getJsonObject("matchLabels");
            if (selector == null) {
                return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/selector/matchLabels found"));
            }
        }

        final var template = root.getJsonObject("template");
        if (template == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/template found"));
        }

        final var metadata = template.getJsonObject("metadata");
        if (metadata == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/template/metadata found"));
        }

        final var labels = metadata.getJsonObject("labels");
        if (labels == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No /spec/template/metadata/labels found"));
        }

        final var missing = selector.entrySet().stream()
                .filter(it -> it.getValue().getValueType() == JsonValue.ValueType.STRING)
                .filter(it -> !Objects.equals(it.getValue(), labels.get(it.getKey())))
                .collect(toMap(Map.Entry::getKey, it -> ((JsonString) it.getValue()).getString()));
        if (missing.isEmpty()) {
            return Stream.empty();
        }
        return Stream.of(new LintError(LintError.LintLevel.ERROR, "Selector does not match pod template, missing expected labels: " + missing));
    }
}
