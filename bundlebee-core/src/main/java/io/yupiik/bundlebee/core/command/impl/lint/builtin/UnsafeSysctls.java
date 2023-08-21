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
import javax.json.JsonValue;
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class UnsafeSysctls extends ContainerValueValidator {
    private final Set<String> forbidden = Set.of("kernel.msg", "kernel.sem", "kernel.shm", "fs.mqueue."); // + "net.*"

    @Override
    public String name() {
        return "unsafe-sysctls";
    }

    @Override
    public String description() {
        return "Alert on deployments specifying unsafe sysctls that may lead to severe problems like wrong behavior of containers";
    }

    @Override
    public String remediation() {
        return "Ensure container does not allow unsafe allocation of system resources by removing unsafe sysctls configurations.\n" +
                "For more details see https://kubernetes.io/docs/tasks/administer-cluster/sysctl-cluster/\n" +
                "https://docs.docker.com/engine/reference/commandline/run/#configure-namespaced-kernel-parameters-sysctls-at-runtime.";
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
        final var sysctls = securityContext.getJsonArray("sysctls");
        if (sysctls == null) {
            return Stream.empty();
        }
        return sysctls.stream()
                .map(JsonValue::asJsonObject)
                .map(it -> it.getString("name", ""))
                .filter(it -> forbidden.contains(it) || it.startsWith("net."))
                .map(it -> new LintError(LintError.LintLevel.ERROR, "Sysctls '" + it + "' is not recommended"));
    }
}
