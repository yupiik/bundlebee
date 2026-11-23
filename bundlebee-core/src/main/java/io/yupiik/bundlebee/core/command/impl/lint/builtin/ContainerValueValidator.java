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

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.bundlebee.core.lang.Tuple2;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public abstract class ContainerValueValidator extends CheckValue {

    private static final Set<String> RUNNABLE_TYPES = Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job");

    private static final Map<String, String> RUNNABLE_CONTAINERS_POINTERS = Map.of( // openshift
            "DeploymentConfig", // openshift
            "/spec/template/spec",
            "Deployment",
            "/spec/template/spec",
            "CronJob",
            "/spec/jobTemplate/spec/template/spec",
            "Job",
            "/spec/template/spec",
            "Pod",
            "/spec");

    protected ContainerValueValidator() {
        super(RUNNABLE_TYPES, RUNNABLE_CONTAINERS_POINTERS, null);
    }

    protected ContainerValueValidator(final Set<String> types) {
        super(types, types.stream().collect(toMap(identity(), RUNNABLE_CONTAINERS_POINTERS::get)), null);
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final Object rawSpec) {
        if (!(rawSpec instanceof Map<?, ?> spec)) {
            return Stream.empty();
        }
        final var containers =
                (spec.get("containers") instanceof List<?> containersValue ? containersValue : List.of());
        final var initContainers =
                (spec.get("initContainers") instanceof List<?> initContainersValue ? initContainersValue : List.of());
        return Stream.concat(containers.stream(), supportsInitContainers() ? initContainers.stream() : Stream.empty())
                .filter(Map.class::isInstance)
                .<Map<?, ?>>map(it -> (Map<?, ?>) it)
                .map(it -> new Tuple2<>(
                        it,
                        initContainers.size() > 1
                                ? new Tuple2<>(true, (it.get("name") instanceof String nameValue ? nameValue : "-"))
                                : null))
                .flatMap(it -> validate(it.first(), descriptor)
                        .map(e -> new LintError(
                                e.getLevel(),
                                e.getMessage()
                                        + (it.second() != null
                                                ? " (" + (it.second().first() ? "init-" : "") + "container="
                                                        + it.second().second() + ")"
                                                : ""))));
    }

    @Override
    protected boolean ignoreError(final LintableDescriptor descriptor) {
        // if pointer is missing
        return true;
    }

    protected boolean supportsInitContainers() {
        return false;
    }

    protected abstract Stream<LintError> validate(final Map<?, ?> container, final LintableDescriptor descriptor);
}
