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
import java.util.Collection;
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class WildcardUsedInRules extends CheckByKind {
    public WildcardUsedInRules() {
        super(Set.of("Role", "ClusterRole"));
    }

    @Override
    public String name() {
        return "wildcard-in-rules";
    }

    @Override
    public String description() {
        return "Indicate when a wildcard is used in Role or ClusterRole rules.\n" +
                "CIS Benchmark 5.1.3 Use of wildcards is not optimal from a security perspective as it may allow for inadvertent access to be granted when new resources are added to the Kubernetes API either as CRDs or in later versions of the product.";
    }

    @Override
    public String remediation() {
        return "Where possible replace any use of wildcards in clusterroles and roles with specific objects or actions.";
    }

    @Override
    public Stream<LintError> validateSync(final LintableDescriptor descriptor) {
        return Stream.ofNullable(descriptor.getDescriptor().getJsonArray("rules"))
                .flatMap(Collection::stream)
                .map(JsonValue::asJsonObject)
                .filter(it -> it.containsKey("verbs") && it.getJsonArray("verbs").stream()
                        .anyMatch(v -> v.getValueType() == JsonValue.ValueType.STRING && "*".equals(((JsonString) v).getString())))
                .map(it -> new LintError(LintError.LintLevel.ERROR, "Wildcard verb used in rule " + it));
    }
}
