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
package io.yupiik.bundlebee.core.kube;

import java.util.List;

/**
 * Metadata-only compatibility view for the cluster-host placeholder.
 * Authentication, TLS, context selection and token refresh belong to Fusion's KubernetesClient.
 * This is deliberately not a second kubeconfig serialization model.
 */
public record KubeConfig(List<NamedCluster> clusters) {
    public KubeConfig {
        clusters = List.copyOf(clusters);
    }

    public List<NamedCluster> getClusters() {
        return clusters;
    }

    public record NamedCluster(String name, Cluster cluster) {
        public String getName() {
            return name;
        }

        public Cluster getCluster() {
            return cluster;
        }
    }

    public record Cluster(String server) {
        public String getServer() {
            return server;
        }
    }
}
