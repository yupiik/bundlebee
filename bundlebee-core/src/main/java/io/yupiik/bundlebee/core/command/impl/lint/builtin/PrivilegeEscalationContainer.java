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
public class PrivilegeEscalationContainer extends CheckValue {
    public PrivilegeEscalationContainer() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/containers",
                        "Deployment", "/spec/template/spec/containers",
                        "CronJob", "/spec/jobTemplate/spec/template/spec/containers",
                        "Job", "/spec/template/spec/containers",
                        "Pod", "/spec/containers"),
                true);
    }

    @Override
    public String name() {
        return "privilege-escalation-container";
    }

    @Override
    public String description() {
        return "Alert on containers of allowing privilege escalation that could gain more privileges than its parent process.";
    }

    @Override
    public String remediation() {
        return "Ensure containers do not allow privilege escalation by setting\n" +
                "allowPrivilegeEscalation=false, privileged=false and removing CAP_SYS_ADMIN capability.\n" +
                "See https://kubernetes.io/docs/tasks/configure-pod-container/security-context/ for more details.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return value.asJsonArray().stream()
                .map(JsonValue::asJsonObject)
                .map(it -> it.getJsonObject("securityContext"))
                .filter(Objects::nonNull)
                .anyMatch(it -> it.getBoolean("allowPrivilegeEscalation", false)) ?
                Stream.of(new LintError(LintError.LintLevel.ERROR, "'allowPrivilegeEscalation' is set to true")) :
                Stream.empty();
    }
}
