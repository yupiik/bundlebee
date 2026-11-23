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
package io.yupiik.bundlebee.operator;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class OperatorInstallationTest {
    @Test
    void deploymentUsesNamespacedWatchAndTypedConnectivityKeys() throws Exception {
        try (final var container = ConfiguringContainer.of().start();
                final var mapper = container.lookup(JsonMapper.class);
                final var stream = Objects.requireNonNull(getClass()
                        .getResourceAsStream("/bundlebee/kubernetes/io.yupiik/bundlebee-operator/deployment.json"))) {
            final Map<?, ?> deployment =
                    mapper.instance().fromString(Map.class, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            final var spec = (Map<?, ?>) deployment.get("spec");
            final var template = (Map<?, ?>) spec.get("template");
            final var pod = (Map<?, ?>) template.get("spec");
            final var containers = (List<?>) pod.get("containers");
            final var operator = (Map<?, ?>) containers.get(0);
            final var environment = ((List<?>) operator.get("env"))
                    .stream()
                            .map(Map.class::cast)
                            .collect(toMap(
                                    entry -> entry.get("name").toString(),
                                    entry -> entry.get("value").toString()));
            assertEquals("{{bundlebee.operator.namespace:-default}}", environment.get("BUNDLEBEE_OPERATOR_NAMESPACES"));
            assertEquals("true", environment.get("OPERATOR_ENABLED"));
            assertEquals("true", environment.get("FUSION_LAUNCHER_USEHOOK"));
            assertFalse(environment.containsKey("KUBECONFIG"));

            final Map<?, ?> kubeconfig =
                    mapper.instance().fromString(Map.class, environment.get("BUNDLEBEE_KUBE_KUBECONFIG"));
            final var cluster = (Map<?, ?>) ((List<?>) kubeconfig.get("clusters")).get(0);
            assertEquals("https://kubernetes.default.svc", ((Map<?, ?>) cluster.get("cluster")).get("server"));
            final var context = (Map<?, ?>) ((List<?>) kubeconfig.get("contexts")).get(0);
            assertEquals(
                    "{{bundlebee.operator.namespace:-default}}", ((Map<?, ?>) context.get("context")).get("namespace"));
        }
    }

    @Test
    void installationInterpolatesAllDescriptorNamespaces() throws Exception {
        final var manifest = Path.of(Objects.requireNonNull(getClass().getResource("/bundlebee/manifest.json"))
                .toURI());
        assertDoesNotThrow(() -> new BundleBee()
                .launch(
                        new String[] {
                            "apply",
                            "--from",
                            manifest.getParent().getParent().toString(),
                            "--manifest",
                            manifest.toString(),
                            "--alveolus",
                            "auto"
                        },
                        Map.of(
                                "bundlebee.kube.dryRun", "true",
                                "bundlebee.kube.skipLoadConfig", "true",
                                "bundlebee.operator.namespace", "operator-installation-test")));
    }
}
