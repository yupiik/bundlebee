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

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.WARNING;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@DefaultScoped
public class HPAMinimumThreeReplicas extends CheckValue {

    public HPAMinimumThreeReplicas() {
        super(Set.of("HorizontalPodAutoscaler"), Map.of("HorizontalPodAutoscaler", "/spec/minReplicas"), false);
    }

    @Override
    public String name() {
        return "hpa-minimum-three-replicas";
    }

    @Override
    public String description() {
        return "Indicates when a HorizontalPodAutoscaler specifies less than three minReplicas";
    }

    @Override
    public String remediation() {
        return "Increase the number of replicas in the HorizontalPodAutoscaler to at least three to increase fault tolerance.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final Object value) {
        return value instanceof Number && ((Number) value).intValue() < 3
                ? Stream.of(new LintError(WARNING, "HorizontalPodAutoscaler minimum replicas too low (should be >= 3)"))
                : Stream.empty();
    }
}
