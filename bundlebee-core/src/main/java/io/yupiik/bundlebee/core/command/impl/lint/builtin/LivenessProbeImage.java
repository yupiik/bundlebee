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
import javax.json.JsonObject;
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class LivenessProbeImage extends ContainerValueValidator {
    public LivenessProbeImage() {
        super(Set.of("Deployment"));
    }

    @Override
    public String name() {
        return "no-liveness-probe";
    }

    @Override
    public String description() {
        return "Ensures a liveness probe is defined.";
    }

    @Override
    public String remediation() {
        return "Any container (from containers array) should have a liveness probe.";
    }

    @Override
    protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
        final var probe = container.getJsonObject("livenessProbe");
        if (probe == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No liveness probe"));
        }
        return Stream.empty();
    }
}
