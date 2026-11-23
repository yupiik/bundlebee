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

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.ERROR;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@DefaultScoped
public class DockerSock extends CheckValue {

    public DockerSock() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of( // openshift
                        "DeploymentConfig", // openshift
                        "/spec/template/spec/volumes",
                        "Deployment",
                        "/spec/template/spec/volumes",
                        "CronJob",
                        "/spec/jobTemplate/spec/template/spec/volumes",
                        "Job",
                        "/spec/template/spec/volumes",
                        "Pod",
                        "/spec/volumes"),
                true);
    }

    @Override
    public String name() {
        return "docker-sock";
    }

    @Override
    public String description() {
        return "Alert on deployments with docker.sock mounted in containers.";
    }

    @Override
    public String remediation() {
        return "Ensure the Docker socket is not mounted inside any containers by removing the associated \n"
                + "Volume and VolumeMount in deployment yaml specification.\n"
                + "If the Docker socket is mounted inside a container it could allow processes running within \n"
                + "the container to execute Docker commands which would effectively allow for full control of the host.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final Object value) {
        return value instanceof List<?>
                        && (value instanceof List<?> values ? values : List.of())
                                .stream()
                                        .filter(Map.class::isInstance)
                                        .<Map<?, ?>>map(it -> (Map<?, ?>) it)
                                        .map(it -> (it.get("hostPath") instanceof Map<?, ?> hostPathValue
                                                ? hostPathValue
                                                : null))
                                        .filter(Objects::nonNull)
                                        .anyMatch(it -> (it.get("path") instanceof String pathValue ? pathValue : "")
                                                .endsWith("docker.sock"))
                ? Stream.of(new LintError(ERROR, "docker.sock shouldn't be bound from the host"))
                : Stream.empty();
    }
}
