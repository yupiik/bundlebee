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

import static java.util.Optional.ofNullable;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@DefaultScoped
public class ReadSecretFromEnvVar extends ContainerValueValidator {

    @Override
    public String name() {
        return "read-secret-from-env-var";
    }

    @Override
    public String description() {
        return "Indicates when a deployment reads secret from environment variables.\n"
                + "CIS Benchmark 5.4.1: \"Prefer using secrets as files over secrets as environment variables. \"";
    }

    @Override
    public String remediation() {
        return "If possible, rewrite application code to read secrets from mounted secret files, rather than from environment variables.\n"
                + "Refer to https://kubernetes.io/docs/concepts/configuration/secret/#using-secrets for details.";
    }

    @Override
    protected boolean supportsInitContainers() {
        return true;
    }

    @Override
    protected Stream<LintError> validate(final Map<?, ?> container, final LintableDescriptor descriptor) {
        final var env = (container.get("env") instanceof List<?> envValue ? envValue : List.of());
        return env.stream()
                .filter(Map.class::isInstance)
                .<Map<?, ?>>map(it -> (Map<?, ?>) it)
                .filter(e -> ofNullable(
                                (e.get("valueFrom") instanceof Map<?, ?> valueFromValue ? valueFromValue : null))
                        .map(v -> v.containsKey("secretKeyRef"))
                        .orElse(false))
                .map(it -> new LintError(
                        LintError.LintLevel.ERROR,
                        "Secret read from env for '" + (it.get("name") instanceof String nameValue ? nameValue : "")
                                + "' environment variable"));
    }
}
