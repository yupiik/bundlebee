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
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

@Dependent
public class ReadSecretFromEnvVar extends ContainerValueValidator {
    @Override
    public String name() {
        return "read-secret-from-env-var";
    }

    @Override
    public String description() {
        return "Indicates when a deployment reads secret from environment variables.\n" +
                "CIS Benchmark 5.4.1: \"Prefer using secrets as files over secrets as environment variables. \"";
    }

    @Override
    public String remediation() {
        return "If possible, rewrite application code to read secrets from mounted secret files, rather than from environment variables.\n" +
                "Refer to https://kubernetes.io/docs/concepts/configuration/secret/#using-secrets for details.";
    }

    @Override
    protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
        final var env = container.getJsonArray("env");
        if (env == null) {
            return Stream.empty();
        }
        return env.stream()
                .map(JsonValue::asJsonObject)
                .filter(e -> ofNullable(e.getJsonObject("valueFrom"))
                        .map(v -> v.containsKey("secretKeyRef"))
                        .orElse(false))
                .map(it -> new LintError(LintError.LintLevel.ERROR, "Secret read from env for '" + it.getString("name", "") + "' environment variable"));
    }
}
