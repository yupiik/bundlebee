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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@DefaultScoped
public class NoExtensionsV1Beta extends CheckValue {

    public NoExtensionsV1Beta() {
        super(
                Set.of("Ingress", "ReplicaSet", "Deployment", "DaemonSet", "NetworkPolicy", "PodSecurityPolicy"),
                Map.of( // v1.22
                        "Ingress", // v1.22
                        "/apiVersion",
                        "ReplicaSet",
                        "/apiVersion",
                        "Deployment",
                        "/apiVersion",
                        "DaemonSet",
                        "/apiVersion",
                        "NetworkPolicy",
                        "/apiVersion",
                        "PodSecurityPolicy",
                        "/apiVersion"),
                false);
    }

    @Override
    public String name() {
        return "no-extensions-v1beta";
    }

    @Override
    public String description() {
        return "Indicates when objects use deprecated API versions under extensions/v1beta.";
    }

    @Override
    public String remediation() {
        return "Migrate using the apps/v1 API versions for the objects.\n"
                + "Refer to https://kubernetes.io/blog/2019/07/18/api-deprecations-in-1-16/ for details.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final Object value) {
        return value instanceof String && "extensions/v1beta1".equals(((String) value))
                ? Stream.of(
                        new LintError(LintError.LintLevel.WARNING, "'extensions/v1beta1' shouldn't be used anymore"))
                : Stream.empty();
    }
}
