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

import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

public abstract class ContainerValueValidator extends CheckValue {
    private static final Set<String> RUNNABLE_TYPES = Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job");
    private static final Map<String, String> RUNNABLE_CONTAINERS_POINTERS = Map.of(
            "DeploymentConfig", "/spec/template/spec/containers", // openshift
            "Deployment", "/spec/template/spec/containers",
            "CronJob", "/spec/jobTemplate/template/spec/containers",
            "Job", "/spec/template/spec/containers",
            "Pod", "/spec/containers");

    protected ContainerValueValidator() {
        super(RUNNABLE_TYPES, RUNNABLE_CONTAINERS_POINTERS, null);
    }

    protected ContainerValueValidator(final Set<String> types) {
        super(types, types.stream().collect(toMap(identity(), RUNNABLE_CONTAINERS_POINTERS::get)), null);
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue containers) {
        return containers.asJsonArray().stream()
                .map(JsonValue::asJsonObject)
                .flatMap(it -> validate(it, descriptor));
    }

    @Override
    protected boolean ignoreError(final LintableDescriptor descriptor) {
        return true; // if pointer is missing
    }

    protected abstract Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor);
}
