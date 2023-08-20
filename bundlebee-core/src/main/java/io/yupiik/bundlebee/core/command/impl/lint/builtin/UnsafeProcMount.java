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
import javax.json.JsonObject;
import java.util.stream.Stream;

@Dependent
public class UnsafeProcMount extends ContainerValueValidator {
    @Override
    public String name() {
        return "unsafe-proc-mount";
    }

    @Override
    public String description() {
        return "Alert on deployments with unsafe /proc mount (procMount=Unmasked) that will bypass the default masking behavior of the container runtime";
    }

    @Override
    public String remediation() {
        return "Ensure container does not unsafely exposes parts of /proc by setting procMount=Default. \n" +
                "Unmasked ProcMount bypasses the default masking behavior of the container runtime.\n" +
                "See https://kubernetes.io/docs/concepts/security/pod-security-standards/ for more details.";
    }

    @Override
    protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
        return Stream.ofNullable(container.getJsonObject("securityContext"))
                .filter(it -> "Unmasked".equals(it.getString("procMount", "")))
                .map(it -> new LintError(LintError.LintLevel.ERROR, "procMount=Unmasked is used"));
    }
}
