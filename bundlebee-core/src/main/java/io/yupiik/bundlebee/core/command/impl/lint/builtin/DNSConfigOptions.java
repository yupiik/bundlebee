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
import javax.json.JsonValue;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Dependent
public class DNSConfigOptions extends CheckValue {
    public DNSConfigOptions() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/dnsConfig/options",
                        "Deployment", "/spec/template/spec/dnsConfig/options",
                        "CronJob", "/spec/jobTemplate/spec/template/spec/dnsConfig/options",
                        "Job", "/spec/template/spec/dnsConfig/options",
                        "Pod", "/spec/dnsConfig/options"),
                false);
    }

    @Override
    public String name() {
        return "dnsconfig-options";
    }

    @Override
    public String description() {
        return "Alert on deployments that have no specified dnsConfig options";
    }

    @Override
    public String remediation() {
        return "Specify dnsconfig options in your Pod specification to ensure the expected DNS setting on the Pod.\n" +
                "Refer to https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/#pod-dns-config for details.";
    }

    @Override
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        return value.getValueType() != JsonValue.ValueType.ARRAY || value.asJsonArray().stream()
                .map(JsonValue::asJsonObject)
                .filter(o -> "ndots".equals(o.getString("name", "")))
                .noneMatch(o -> {
                    try {
                        return Integer.parseInt(o.getString("value", "5")) <= 2;
                    } catch (final NumberFormatException nfe) {
                        return true;
                    }
                }) ?
                Stream.of(new LintError(LintError.LintLevel.WARNING, "No ndots configuration for pod(s)")) :
                Stream.empty();
    }
}
