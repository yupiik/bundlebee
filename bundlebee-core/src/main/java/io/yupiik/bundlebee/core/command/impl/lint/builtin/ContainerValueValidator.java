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
import io.yupiik.bundlebee.core.lang.Tuple2;

import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static javax.json.JsonValue.EMPTY_JSON_ARRAY;

public abstract class ContainerValueValidator extends CheckValue {
    private static final Set<String> RUNNABLE_TYPES = Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job");
    private static final Map<String, String> RUNNABLE_CONTAINERS_POINTERS = Map.of(
            "DeploymentConfig", "/spec/template/spec", // openshift
            "Deployment", "/spec/template/spec",
            "CronJob", "/spec/jobTemplate/template/spec",
            "Job", "/spec/template/spec",
            "Pod", "/spec");

    protected ContainerValueValidator() {
        super(RUNNABLE_TYPES, RUNNABLE_CONTAINERS_POINTERS, null);
    }

    protected ContainerValueValidator(final Set<String> types) {
        super(types, types.stream().collect(toMap(identity(), RUNNABLE_CONTAINERS_POINTERS::get)), null);
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue rawSpec) {
        final var spec = rawSpec.asJsonObject();
        final var containers = ofNullable(spec.getJsonArray("containers")).orElse(EMPTY_JSON_ARRAY);
        final var initContainers = ofNullable(spec.getJsonArray("initContainers")).orElse(EMPTY_JSON_ARRAY);
        return Stream.concat(containers.stream(), supportsInitContainers() ? initContainers.stream() : Stream.empty())
                .map(JsonValue::asJsonObject)
                .map(it -> new Tuple2<>(
                        it,
                        initContainers.size() > 1 ?
                                new Tuple2<>(true, it.getString("name", "-")) :
                                null))
                .flatMap(it -> validate(it.getFirst(), descriptor)
                        .map(e -> new LintError(e.getLevel(), e.getMessage() +
                                (it.getSecond() != null ?
                                        " (" + (it.getSecond().getFirst() ? "init-" : "") + "container=" + it.getSecond().getSecond() + ")" : ""))));
    }

    @Override
    protected boolean ignoreError(final LintableDescriptor descriptor) {
        return true; // if pointer is missing
    }

    protected boolean supportsInitContainers() {
        return false;
    }

    protected abstract Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor);
}
