/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class DefaultServiceAccount extends CheckValue {
    public DefaultServiceAccount() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/serviceAccountName", // openshift
                        "Deployment", "/spec/template/spec/serviceAccountName",
                        "CronJob", "/spec/jobTemplate/template/spec/serviceAccountName",
                        "Job", "/spec/template/spec/serviceAccountName",
                        "Pod", "/spec/serviceAccountName"),
                true);
    }

    @Override
    public String name() {
        return "default-service-account";
    }

    @Override
    public String description() {
        return "Indicates when pods use the default service account.";
    }

    @Override
    public String remediation() {
        return "Create a dedicated service account for your pod.\n" +
                "Refer to https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/ for details.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue serviceAccountName) {
        return serviceAccountName.getValueType() == JsonValue.ValueType.STRING && "default".equals(((JsonString) serviceAccountName).getString()) ?
                Stream.of(new LintError(LintError.LintLevel.ERROR, "'default' service account used")) :
                Stream.empty();
    }
}
