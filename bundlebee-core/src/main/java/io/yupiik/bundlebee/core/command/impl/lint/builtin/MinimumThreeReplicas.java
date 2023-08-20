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
import javax.json.JsonNumber;
import javax.json.JsonValue;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.WARNING;

@Dependent
public class MinimumThreeReplicas extends CheckValue {
    public MinimumThreeReplicas() {
        super(

                Set.of("DeploymentConfig", "Deployment"),
                Map.of(
                        "DeploymentConfig", "/spec/replicas",
                        "Deployment", "/spec/replicas"),
                false);
    }

    @Override
    public String name() {
        return "minimum-three-replicas";
    }

    @Override
    public String description() {
        return "Indicates when a deployment uses less than three replicas";
    }

    @Override
    public String remediation() {
        return "Increase the number of replicas in the deployment to at least three to increase the fault tolerance of the deployment.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return value.getValueType() == JsonValue.ValueType.NUMBER && ((JsonNumber) value).intValue() < 3 ?
                Stream.of(new LintError(WARNING, "DeploymentLike minimum replicas too low (should be >= 3)")) :
                Stream.empty();
    }
}
