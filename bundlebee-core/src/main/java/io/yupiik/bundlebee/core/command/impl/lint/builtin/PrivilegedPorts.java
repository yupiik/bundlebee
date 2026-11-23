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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@DefaultScoped
public class PrivilegedPorts extends CheckValue {

    public PrivilegedPorts() {
        super(
                Set.of("DeploymentConfig", "Deployment"),
                Map.of(
                        "DeploymentConfig",
                        "/spec/template/spec/containers",
                        "Deployment",
                        "/spec/template/spec/containers"),
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
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final Object value) {
        return (value instanceof List<?> values ? values : List.of())
                .stream()
                        .filter(Map.class::isInstance)
                        .<Map<?, ?>>map(it -> (Map<?, ?>) it)
                        .map(it -> (it.get("ports") instanceof List<?> portsValue ? portsValue : List.of()))
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .filter(Map.class::isInstance)
                        .<Map<?, ?>>map(it -> (Map<?, ?>) it)
                        .map(it -> (it.get("containerPort") instanceof Number containerPortValue
                                ? containerPortValue.intValue()
                                : 10_000))
                        .filter(it -> it >= 0 && it <= 1024)
                        .distinct()
                        .sorted()
                        .map(it ->
                                new LintError(LintError.LintLevel.ERROR, "priviledged port used by container: " + it));
    }
}
