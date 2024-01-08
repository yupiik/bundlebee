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
import javax.json.JsonNumber;
import javax.json.JsonValue;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class SetAntiAffinity extends CheckValue {
    public SetAntiAffinity() {
        super(
                Set.of("Deployment"),
                Map.of("Deployment", "/spec/template/spec/affinity/podAntiAffinity"),
                Map.of("Deployment", new Condition("/spec/replicas", v -> v.getValueType() == JsonValue.ValueType.NUMBER && ((JsonNumber) v).intValue() > 1)));
    }

    @Override
    public String name() {
        return "missing-anti-affinity";
    }

    @Override
    public String description() {
        return "When replicas > 1 setting an anti-affinity enables to distribute the load accross machines setting 'topologyKey: \"kubernetes.io/hostname\"', see https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/#inter-pod-affinity-and-anti-affinity.";
    }

    @Override
    public String remediation() {
        return "Add podAntiAffinity in your deployment descriptor.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        // todo: validate topologyKey: "kubernetes.io/hostname" ? for now we assume that if it is set user knows what he does
        return value.getValueType() != JsonValue.ValueType.NULL ?
                Stream.empty() :
                Stream.of(new LintError(LintError.LintLevel.WARNING, "Missing pod anti affinity."));
    }
}
