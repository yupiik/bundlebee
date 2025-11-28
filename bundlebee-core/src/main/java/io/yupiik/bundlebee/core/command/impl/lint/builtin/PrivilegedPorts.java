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
import javax.json.JsonValue;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class PrivilegedPorts extends CheckValue {
    public PrivilegedPorts() {
        super(
                Set.of("DeploymentConfig", "Deployment"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/containers",
                        "Deployment", "/spec/template/spec/containers"),
                true);
    }

    @Override
    public String name() {
        return "privileged-ports";
    }

    @Override
    public String description() {
        return "Alert on deployments with privileged ports mapped in containers";
    }

    @Override
    public String remediation() {
        return "Ensure privileged ports [0, 1024] are not mapped within containers.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return value.asJsonArray().stream()
                .map(JsonValue::asJsonObject)
                .map(it -> it.getJsonArray("ports"))
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(JsonValue::asJsonObject)
                .map(it -> it.getInt("containerPort", 10_000))
                .filter(it -> it >= 0 && it <= 1024)
                .distinct()
                .sorted()
                .map(it -> new LintError(LintError.LintLevel.ERROR, "priviledged port used by container: " + it));
    }
}
