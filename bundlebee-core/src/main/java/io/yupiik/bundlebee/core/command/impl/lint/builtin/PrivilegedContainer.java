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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class PrivilegedContainer extends CheckValue {
    public PrivilegedContainer() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/containers",
                        "Deployment", "/spec/template/spec/containers",
                        "CronJob", "/spec/jobTemplate/template/spec/containers",
                        "Job", "/spec/template/spec/containers",
                        "Pod", "/spec/containers"),
                true);
    }

    @Override
    public String name() {
        return "privileged-container";
    }

    @Override
    public String description() {
        return "Indicates when deployments have containers running in privileged mode.";
    }

    @Override
    public String remediation() {
        return "Do not run your container as privileged unless it is required.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return value.asJsonArray().stream()
                .map(JsonValue::asJsonObject)
                .map(it -> it.getJsonObject("securityContext"))
                .filter(Objects::nonNull)
                .anyMatch(it -> it.getBoolean("privileged", false)) ?
                Stream.of(new LintError(LintError.LintLevel.ERROR, "'privileged' is set to true")) :
                Stream.empty();
    }
}
