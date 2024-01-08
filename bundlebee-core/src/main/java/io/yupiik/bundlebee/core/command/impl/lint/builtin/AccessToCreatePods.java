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

import javax.enterprise.context.Dependent;
import java.util.Set;

@Dependent
public class AccessToCreatePods extends AccessToResources {
    public AccessToCreatePods() {
        super(
                Set.of("pods", "deployments", "statefulsets", "replicasets", "cronjob", "jobs", "daemonsets"),
                Set.of("create"),
                Set.of("ClusterRoleBinding", "RoleBinding"));
    }

    @Override
    public String name() {
        return "access-to-create-pods";
    }

    @Override
    public String description() {
        return "Indicates when a subject (Group/User/ServiceAccount) has create access to Pods.\n" +
                "CIS Benchmark 5.1.4: The ability to create pods in a cluster opens up possibilities for privilege escalation and should be restricted, where possible.";
    }

    @Override
    public String remediation() {
        return "Where possible, remove create access to pod objects in the cluster.";
    }
}
