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

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.ERROR;

@Dependent
public class WritableHostPath extends CheckValue {
    public WritableHostPath() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/volumes", // openshift
                        "Deployment", "/spec/template/spec/volumes",
                        "CronJob", "/spec/jobTemplate/spec/template/spec/volumes",
                        "Job", "/spec/template/spec/volumes",
                        "Pod", "/spec/volumes"),
                true);
    }

    @Override
    public String name() {
        return "writable-host-mount";
    }

    @Override
    public String description() {
        return "Indicates when containers mount a host path as writable.";
    }

    @Override
    public String remediation() {
        return "Set containers to mount host paths as readOnly, if you need to access files on the host.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return value.asJsonArray().stream()
                .map(JsonValue::asJsonObject)
                .map(it -> it.getJsonObject("hostPath"))
                .filter(Objects::nonNull)
                .map(it -> new LintError(ERROR, "host path '" + it.getString("path", "") + "' made available to container(s)"));
    }
}
