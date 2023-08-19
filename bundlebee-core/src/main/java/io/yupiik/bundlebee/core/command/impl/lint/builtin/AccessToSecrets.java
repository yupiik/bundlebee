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

import javax.enterprise.context.Dependent;
import java.util.Set;

@Dependent
public class AccessToSecrets extends AccessToResources {
    public AccessToSecrets() {
        super(
                Set.of("secrets"),
                Set.of("get", "list", "delete", "create", "watch", "*"),
                Set.of("ClusterRoleBinding", "RoleBinding"));
    }

    @Override
    public String name() {
        return "access-to-secrets";
    }

    @Override
    public String description() {
        return "Indicates when a subject (Group/User/ServiceAccount) has access to Secrets.\n" +
                "CIS Benchmark 5.1.2: Access to secrets should be restricted to the smallest possible group of users to reduce the risk of privilege escalation.";
    }

    @Override
    public String remediation() {
        return "Where possible, remove get, list and watch access to secret objects in the cluster.";
    }
}
