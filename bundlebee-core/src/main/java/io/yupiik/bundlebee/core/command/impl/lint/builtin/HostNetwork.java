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
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.WARNING;

@Dependent
public class HostNetwork extends CheckValue {
    public HostNetwork() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/hostNetwork",
                        "Deployment", "/spec/template/spec/hostNetwork",
                        "CronJob", "/spec/jobTemplate/spec/template/spec/hostNetwork",
                        "Job", "/spec/template/spec/hostNetwork",
                        "Pod", "/spec/hostNetwork"),
                true);
    }

    @Override
    public String name() {
        return "host-network";
    }

    @Override
    public String description() {
        return "Alert on pods/deployment-likes with sharing host's network namespace";
    }

    @Override
    public String remediation() {
        return "Ensure the host's network namespace is not shared.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return value.getValueType() == JsonValue.ValueType.TRUE ?
                Stream.of(new LintError(WARNING, "hostNetwork is true")) :
                Stream.empty();
    }
}
