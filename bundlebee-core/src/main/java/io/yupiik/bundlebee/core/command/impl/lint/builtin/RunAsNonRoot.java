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
import java.util.ArrayList;
import java.util.stream.Stream;

@Dependent
public class RunAsNonRoot extends ContainerValueValidator {
    @Override
    public String name() {
        return "run-as-non-root";
    }

    @Override
    public String description() {
        return "Indicates when containers are not set to runAsNonRoot.";
    }

    @Override
    public String remediation() {
        return "Set runAsUser to a non-zero number and runAsNonRoot to true in your pod or container securityContext.\n" +
                "Refer to https://kubernetes.io/docs/tasks/configure-pod-container/security-context/ for details.";
    }

    @Override
    protected boolean supportsInitContainers() {
        return true;
    }

    @Override
    protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
        final var securityContext = container.getJsonObject("securityContext");
        if (securityContext == null) {
            return Stream.empty();
        }

        final var errors = new ArrayList<LintError>();
        if (!securityContext.getBoolean("runAsNonRoot", true)) {
            errors.add(new LintError(LintError.LintLevel.ERROR, "'runAsNonRoot' is false"));
        }
        if (0 == securityContext.getInt("runAsUser", 1_000 /* let's assume container is correct for now to avoid false positives */)) {
            errors.add(new LintError(LintError.LintLevel.ERROR, "'runAsUser' is 0"));
        }
        return errors.stream();
    }
}
