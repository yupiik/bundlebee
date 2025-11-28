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

@Dependent
public class DeprecatedServiceAccountField extends CheckValue {
    public DeprecatedServiceAccountField() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/serviceAccount", // openshift
                        "Deployment", "/spec/template/spec/serviceAccount",
                        "CronJob", "/spec/jobTemplate/spec/template/spec/serviceAccount",
                        "Job", "/spec/template/spec/serviceAccount",
                        "Pod", "/spec/serviceAccount"),
                true);
    }

    @Override
    public String name() {
        return "deprecated-service-account-field";
    }

    @Override
    public String description() {
        return "Indicates when deployments use the deprecated serviceAccount field.";
    }

    @Override
    public String remediation() {
        return "Use the serviceAccountName field instead. If you must specify serviceAccount, ensure values for serviceAccount and serviceAccountName match.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return Stream.of(new LintError(LintError.LintLevel.WARNING, "'serviceAccount' field is deprecated"));
    }
}
