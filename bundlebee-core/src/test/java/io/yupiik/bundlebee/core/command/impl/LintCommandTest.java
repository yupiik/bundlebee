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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LintCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void lint(final CommandExecutor executor) { // no error on a service as of today
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(
                "lint", "--alveolus", "ApplyCommandTest.withdep"));
        assertEquals("No linting error.\n", logs);
    }

    @Test
    void podWrongResources(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: v1\n" +
                "kind: Pod\n" +
                "metadata:\n" +
                "  name: my-pod\n" +
                "  namespace: default\n" +
                "spec:\n" +
                "  containers:\n" +
                "  - name: my-pod-ctr\n" +
                "    image: polinux/stress:1\n" +
                "    resources:\n" +
                "      requests:\n" +
                "        memory: \"10Gi\"\n");
        assertOutput(
                executor, lintCommand, "" +
                        "There are linting errors:\n" +
                        "[test][desc.yaml] No cpu resource in container requests resources\n" +
                        "[test][desc.yaml] No limits element in container resources\n" +
                        "[test][desc.yaml] No limits element in container resources\n",
                "--forcedRules", "cpu-limits,cpu-requests,memory-limits,memory-requests");
    }

    @Test
    void podResourcesOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: v1\n" +
                "kind: Pod\n" +
                "metadata:\n" +
                "  name: my-pod\n" +
                "  namespace: default\n" +
                "spec:\n" +
                "  containers:\n" +
                "  - name: my-pod-ctr\n" +
                "    image: polinux/stress:1\n" +
                "    resources:\n" +
                "      requests:\n" +
                "        memory: \"10Gi\"\n" +
                "        cpu: 1\n" +
                "      limits:\n" +
                "        memory: \"10Gi\"\n" +
                "        cpu: 1\n");
        assertOutput(
                executor, lintCommand, "No linting error.\n",
                "--forcedRules", "cpu-limits,cpu-requests,memory-limits,memory-requests");
    }

    @Test
    void latestNoTag(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: v1\n" +
                "kind: Pod\n" +
                "metadata:\n" +
                "  name: my-pod\n" +
                "  namespace: default\n" +
                "spec:\n" +
                "  containers:\n" +
                "  - name: my-pod-ctr\n" +
                "    image: polinux/stress\n" +
                "    resources:\n" +
                "      requests:\n" +
                "        memory: \"10Gi\"\n" +
                "        cpu: 1\n" +
                "      limits:\n" +
                "        memory: \"10Gi\"\n" +
                "        cpu: 1\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] You shouldn't use latest tag since it is highly unstable: 'polinux/stress'\n",
                "--forcedRules", "no-latest");
    }

    @Test
    void latest(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: v1\n" +
                "kind: Pod\n" +
                "metadata:\n" +
                "  name: my-pod\n" +
                "  namespace: default\n" +
                "spec:\n" +
                "  containers:\n" +
                "  - name: my-pod-ctr\n" +
                "    image: polinux/stress:latest\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] You shouldn't use latest tag since it is highly unstable: 'polinux/stress:latest'\n",
                "--forcedRules", "no-latest");
    }

    @Test
    void antiAffinity1Ok(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 1\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: dont-fire\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: dont-fire\n" +
                "    spec:\n" +
                "      affinity:\n" +
                "        podAntiAffinity:\n" +
                "          requiredDuringSchedulingIgnoredDuringExecution:\n" +
                "            - labelSelector:\n" +
                "                matchExpressions:\n" +
                "                  - key: app.kubernetes.io/name\n" +
                "                    operator: In\n" +
                "                    values:\n" +
                "                      - dont-fire\n" +
                "              topologyKey: \"kubernetes.io/hostname\"\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          image: foo:1\n" +
                "          livenessProbe:\n" +
                "            exec:\n" +
                "              command: [\"/test\"]\n" +
                "          readinessProbe:\n" +
                "            exec:\n" +
                "              command: [\"/test\"]\n");
        assertOutput(executor, lintCommand, "No linting error.\n", "--forcedRules", "missing-anti-affinity");
    }


    @Test
    void antiAffinity3Ok(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: dont-fire\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: dont-fire\n" +
                "    spec:\n" +
                "      affinity:\n" +
                "        podAntiAffinity:\n" +
                "          requiredDuringSchedulingIgnoredDuringExecution:\n" +
                "            - labelSelector:\n" +
                "                matchExpressions:\n" +
                "                  - key: app.kubernetes.io/name\n" +
                "                    operator: In\n" +
                "                    values:\n" +
                "                      - dont-fire\n" +
                "              topologyKey: \"kubernetes.io/hostname\"\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          image: foo:1\n");
        assertOutput(executor, lintCommand, "No linting error.\n", "--forcedRules", "missing-anti-affinity");
    }

    @Test
    void antiAffinity3Ko(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: dont-fire\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: dont-fire\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          image: foo:1\n" +
                "          livenessProbe:\n" +
                "            exec:\n" +
                "              command: [\"/test\"]\n" +
                "          readinessProbe:\n" +
                "            exec:\n" +
                "              command: [\"/test\"]\n" +
                "          resources:\n" +
                "            requests:\n" +
                "              memory: \"10Gi\"\n" +
                "              cpu: 1\n" +
                "            limits:\n" +
                "              memory: \"10Gi\"\n" +
                "              cpu: 1\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] No '/spec/template/spec/affinity/podAntiAffinity' in 'desc.yaml'\n",
                "--forcedRules", "missing-anti-affinity");
    }

    @Test
    void missingReadiness(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 1\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: dont-fire\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: dont-fire\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          image: foo:1\n" +
                "          livenessProbe:\n" +
                "            exec:\n" +
                "              command: [\"/test\"]\n" +
                "          resources:\n" +
                "            requests:\n" +
                "              memory: \"10Gi\"\n" +
                "              cpu: 1\n" +
                "            limits:\n" +
                "              memory: \"10Gi\"\n" +
                "              cpu: 1\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] No readinessProbe probe\n",
                "--forcedRules", "no-readiness-probe");
    }

    @Test
    void missingLiveness(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 1\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: dont-fire\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: dont-fire\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          image: foo:1\n" +
                "          readinessProbe:\n" +
                "            exec:\n" +
                "              command: [\"/test\"]\n" +
                "          resources:\n" +
                "            requests:\n" +
                "              memory: \"10Gi\"\n" +
                "              cpu: 1\n" +
                "            limits:\n" +
                "              memory: \"10Gi\"\n" +
                "              cpu: 1\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] No liveness probe\n",
                "--forcedRules", "no-liveness-probe");
    }

    @Test
    void roleAndRoleBindingToCreatePodKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "---\n" +
                "apiVersion: rbac.authorization.k8s.io/v1\n" +
                "kind: Role\n" +
                "metadata:\n" +
                "  name: role1\n" +
                "  namespace: namespace-dev\n" +
                "rules:\n" +
                "  - apiGroups: [\"\"]\n" +
                "    resources: [\"pods\"]\n" +
                "    verbs: [\"create\"]\n" +
                "---\n" +
                "apiVersion: rbac.authorization.k8s.io/v1\n" +
                "kind: RoleBinding\n" +
                "metadata:\n" +
                "  name: role-binding1\n" +
                "  namespace: namespace-dev\n" +
                "subjects:\n" +
                "  - kind: ServiceAccount\n" +
                "    name: account1\n" +
                "    namespace: namespace-dev\n" +
                "roleRef:\n" +
                "  apiGroup: rbac.authorization.k8s.io\n" +
                "  kind: Role\n" +
                "  name: role1\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] RoleBinding 'role-binding1' enables to create pods\n",
                "--forcedRules", "access-to-create-pods");
    }

    @Test
    void roleAndRoleBindingToCreatePodOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "---\n" +
                "apiVersion: rbac.authorization.k8s.io/v1\n" +
                "kind: Role\n" +
                "metadata:\n" +
                "  name: role1\n" +
                "  namespace: namespace-dev\n" +
                "rules:\n" +
                "  - apiGroups: [\"\"]\n" +
                "    resources: [\"pods\"]\n" +
                "    verbs: [\"get\"]\n" +
                "---\n" +
                "apiVersion: rbac.authorization.k8s.io/v1\n" +
                "kind: RoleBinding\n" +
                "metadata:\n" +
                "  name: role-binding1\n" +
                "  namespace: namespace-dev\n" +
                "subjects:\n" +
                "  - kind: ServiceAccount\n" +
                "    name: account1\n" +
                "    namespace: namespace-dev\n" +
                "roleRef:\n" +
                "  apiGroup: rbac.authorization.k8s.io\n" +
                "  kind: Role\n" +
                "  name: role1\n");
        assertOutput(
                executor, lintCommand, "No linting error.\n",
                "--forcedRules", "access-to-create-pods");
    }

    @Test
    void netRawKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          capabilities:\n" +
                "            add:\n" +
                "              - NET_RAW\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'NET_RAW' capabilities usage\n",
                "--forcedRules", "drop-net-raw-capability");
    }

    @Test
    void netRawOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          capabilities:\n");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "drop-net-raw-capability");
    }


    @Test
    void defaultServiceAccountKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      serviceAccountName: default");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'default' service account used\n",
                "--forcedRules", "default-service-account");
    }

    @Test
    void defaultServiceAccountOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      serviceAccountName: app");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "default-service-account");
    }

    @Test
    void deprecatedServiceAccountKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      serviceAccount: default");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'serviceAccount' field is deprecated\n",
                "--forcedRules", "deprecated-service-account-field");
    }

    @Test
    void deprecatedServiceAccountOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      serviceAccountName: default");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "deprecated-service-account-field");
    }

    @Test
    void ndotsOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: app\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          image: app:1.2.3\n" +
                "          resources:\n" +
                "            limits:\n" +
                "              cpu: 1\n" +
                "              memory: 500Mi\n" +
                "            requests:\n" +
                "              cpu: 0.25\n" +
                "              memory: 100Mi\n" +
                "          runAsUser: 1001\n" +
                "          securityContext:\n" +
                "            readOnlyRootFilesystem: true\n" +
                "            runAsNonRoot: true\n" +
                "      dnsConfig:\n" +
                "        options:\n" +
                "          - name: ndots\n" +
                "            value: \"2\"");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "dnsconfig-options");
    }

    @Test
    void ndotsKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: app\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          image: app:1.2.3\n" +
                "      dnsConfig:\n" +
                "        options:\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] No '/spec/template/spec/dnsConfig/options' in 'desc.yaml'\n",
                "--forcedRules", "dnsconfig-options");
    }

    @Test
    void dockerSockKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        volumeMounts:\n" +
                "        - name: dockersock\n" +
                "          mountPath: \"/var/run/docker.sock\"\n" +
                "      volumes:\n" +
                "      - name: dockersock\n" +
                "        hostPath:\n" +
                "          path: /var/run/docker.sock");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] docker.sock shouldn't be bound from the host\n",
                "--forcedRules", "docker-sock");
    }

    @Test
    void dockerSockOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          volumeMounts:\n" +
                "            - name: thingsock\n" +
                "              mountPath: \"/var/run/thing.sock\"\n" +
                "      volumes:\n" +
                "        - name: thingsock\n" +
                "          hostPath:\n" +
                "            path: /var/run/thing.sock");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "docker-sock");
    }

    @Test
    void duplicatedEnvVarsKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: dont-fire-deployment\n" +
                "          env:\n" +
                "          - name: PORT\n" +
                "            value: \"8080\"\n" +
                "          - name: HOST\n" +
                "            value: \"localhost\"\n" +
                "        - name: fire-deployment\n" +
                "          env:\n" +
                "          - name: PORT\n" +
                "            value: \"8080\"\n" +
                "          - name: PORT\n" +
                "            value: \"9090\"");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] Duplicated environment variables (container=fire-deployment): [PORT]\n",
                "--forcedRules", "duplicate-env-var");
    }

    @Test
    void duplicatedEnvVarsConfigMapKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "---\n" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: dont-fire-deployment\n" +
                "          env:\n" +
                "          - name: PORT\n" +
                "            value: \"8080\"\n" +
                "          envFrom:\n" +
                "          - configMapRef:\n" +
                "              name: \"e\"\n" +
                "---\n" +
                "apiVersion: v1\n" +
                "kind: ConfigMap\n" +
                "metadata:\n" +
                "  name: e\n" +
                "data:\n" +
                "  PORT: 1234\n");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] Duplicated environment variables (container=dont-fire-deployment): [PORT]\n",
                "--forcedRules", "duplicate-env-var");
    }

    @Test
    void duplicatedEnvVarsOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: dont-fire-deployment\n" +
                "          env:\n" +
                "          - name: PORT\n" +
                "            value: \"8080\"\n" +
                "          - name: HOST\n" +
                "            value: \"localhost\"\n");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "duplicate-env-var");
    }

    @Test
    void duplicatedEnvVarsConfigMapOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "---\n" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: dont-fire-deployment\n" +
                "          env:\n" +
                "          - name: PORT\n" +
                "            value: \"8080\"\n" +
                "          envFrom:\n" +
                "          - configMapRef:\n" +
                "              name: \"e\"\n" +
                "---\n" +
                "apiVersion: v1\n" +
                "kind: ConfigMap\n" +
                "metadata:\n" +
                "  name: e\n" +
                "data:\n" +
                "  HOST: 1234\n");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "duplicate-env-var");
    }

    @Test
    void sensitiveHostMountsKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        volumeMounts:\n" +
                "        - name: config\n" +
                "          mountPath: /ignored\n" +
                "      volumes:\n" +
                "      - name: config\n" +
                "        hostPath:\n" +
                "          path: /etc");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] '/etc' shouldn't be mounted directly\n",
                "--forcedRules", "sensitive-host-mounts");
    }

    @Test
    void sensitiveHostMountsOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        volumeMounts:\n" +
                "        - name: config\n" +
                "          mountPath: /etc\n" +
                "      volumes:\n" +
                "      - name: config\n" +
                "        hostPath:\n" +
                "          path: /etc/foo");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "sensitive-host-mounts");
    }

    @Test
    void hostIpcTrueKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      hostIPC: true");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] hostIPC is true\n",
                "--forcedRules", "host-ipc");
    }

    @Test
    void hostIpcFalseOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n" +
                "      hostIPC: false");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "host-ipc");
    }

    @Test
    void hostIpcMissingOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  template:\n" +
                "    spec:\n");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "host-ipc");
    }

    @Test
    void allowPriviledgeEscalationMissingOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "privilege-escalation-container");
    }

    @Test
    void allowPriviledgeEscalationOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          securityContext:\n" +
                "            allowPrivilegeEscalation: false");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "privilege-escalation-container");
    }

    @Test
    void allowPriviledgeEscalationKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          allowPrivilegeEscalation: true");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'allowPrivilegeEscalation' is set to true\n",
                "--forcedRules", "privilege-escalation-container");
    }

    @Test
    void privilegedOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          securityContext:\n" +
                "            privileged: false");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "privileged-container");
    }

    @Test
    void privilegedKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          privileged: true");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'privileged' is set to true\n",
                "--forcedRules", "privileged-container");
    }

    @Test
    void privilegedPortOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps.openshift.io/v1\n" +
                "kind: DeploymentConfig\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          ports:\n" +
                "            - containerPort: 8080");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "privileged-ports");
    }

    @Test
    void privilegedPortKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        ports:\n" +
                "        - containerPort: 80");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] priviledged port used by container: 80\n",
                "--forcedRules", "privileged-ports");
    }

    @Test
    void noReadOnlyFsOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          securityContext:\n" +
                "            readOnlyRootFilesystem: true");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "no-read-only-root-fs");
    }

    @Test
    void noReadOnlyFsKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          readOnlyRootFilesystem: false");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'readOnlyRootFilesystem' set to true\n",
                "--forcedRules", "no-read-only-root-fs");
    }

    @Test
    void hpaOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: autoscaling/v2beta1\n" +
                "kind: HorizontalPodAutoscaler\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  minReplicas: 3\n" +
                "  maxReplicas: 100\n" +
                "  scaleTargetRef:\n" +
                "    apiVersion: apps/v1\n" +
                "    kind: Deployment\n" +
                "    name: testing\n" +
                "  targetCPUUtilizationPercentage: 85");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "hpa-minimum-three-replicas");
    }

    @Test
    void hpaKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: autoscaling/v2beta1\n" +
                "kind: HorizontalPodAutoscaler\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  minReplicas: 2\n" +
                "  maxReplicas: 100\n" +
                "  scaleTargetRef:\n" +
                "    apiVersion: apps/v1\n" +
                "    kind: Deployment\n" +
                "    name: testing\n" +
                "  targetCPUUtilizationPercentage: 85");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] HorizontalPodAutoscaler minimum replicas too low (should be >= 3)\n",
                "--forcedRules", "hpa-minimum-three-replicas");
    }

    @Test
    void replicasOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  replicas: 3");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "minimum-three-replicas");
    }

    @Test
    void replicasKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app\n" +
                "spec:\n" +
                "  replicas: 2");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] DeploymentLike minimum replicas too low (should be >= 3)\n",
                "--forcedRules", "minimum-three-replicas");
    }

    @Test
    void readSecretFromEnvVarOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        env:\n" +
                "        - name: TOKEN\n" +
                "          value: foo");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "read-secret-from-env-var");
    }

    @Test
    void readSecretFromEnvVarKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        env:\n" +
                "        - name: TOKEN\n" +
                "          valueFrom:\n" +
                "            secretKeyRef:\n" +
                "              name: my-secret\n" +
                "              key: token");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] Secret read from env for 'TOKEN' environment variable\n",
                "--forcedRules", "read-secret-from-env-var");
    }

    @Test
    void mismatchingSelectorOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: dont-fire\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: dont-fire\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "mismatching-selector");
    }

    @Test
    void mismatchingSelectorMissingKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] No /spec/template/metadata found\n",
                "--forcedRules", "mismatching-selector");
    }

    @Test
    void mismatchingSelectorKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app.kubernetes.io/name: other\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] Selector does not match pod template, missing expected labels: {app.kubernetes.io/name=app1}\n",
                "--forcedRules", "mismatching-selector");
    }

    @Test
    void extensionsV1Beta1Ok(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: networking.k8s.io/v1\n" +
                "kind: NetworkPolicy\n" +
                "metadata:\n" +
                "  name: app");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "no-extensions-v1beta");
    }

    @Test
    void extensionsV1Beta1Ko(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: extensions/v1beta1\n" +
                "kind: NetworkPolicy\n" +
                "metadata:\n" +
                "  name: app");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'extensions/v1beta1' shouldn't be used anymore\n",
                "--forcedRules", "no-extensions-v1beta");
    }

    @Test
    void runAsNonRootOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          securityContext:\n" +
                "            runAsUser: 1001\n" +
                "            runAsNonRoot: true");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "run-as-non-root");
    }

    @Test
    void runAsNonRootKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          securityContext:\n" +
                "            runAsUser: 0\n" +
                "            runAsNonRoot: false");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n" +
                        "[test][desc.yaml] 'runAsNonRoot' is false\n" +
                        "[test][desc.yaml] 'runAsUser' is 0\n",
                "--forcedRules", "run-as-non-root");
    }

    @Test
    void unsafeSysctlsOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          sysctls:\n" +
                "          - name: ok");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "unsafe-sysctls");
    }

    @Test
    void unsafeSysctlsKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          sysctls:\n" +
                "          - name: kernel.sem");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] Sysctls 'kernel.sem' is not recommended\n",
                "--forcedRules", "unsafe-sysctls");
    }

    @Test
    void procMountOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "unsafe-proc-mount");
    }

    @Test
    void procMountKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  replicas: 3\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        securityContext:\n" +
                "          procMount: Unmasked");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] procMount=Unmasked is used\n",
                "--forcedRules", "unsafe-proc-mount");
    }

    @Test
    void defaultNamespaceOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "  namespace: apps\n");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "use-namespace");
    }

    @Test
    void defaultNamespaceKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "  namespace: default");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] 'default' namespace is used\n",
                "--forcedRules", "use-namespace");
    }

    @Test
    void wildcardRuleOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: rbac.authorization.k8s.io/v1\n" +
                "kind: Role\n" +
                "metadata:\n" +
                "  name: role1\n" +
                "  namespace: namespace-dev\n" +
                "rules:\n" +
                "  - apiGroups: [\"\"]\n" +
                "    resources: [\"pods\"]\n" +
                "    verbs: [\"get\"]");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "wildcard-in-rules");
    }

    @Test
    void wildcardRuleKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: rbac.authorization.k8s.io/v1\n" +
                "kind: Role\n" +
                "metadata:\n" +
                "  name: role1\n" +
                "  namespace: namespace-dev\n" +
                "rules:\n" +
                "  - apiGroups: [\"\"]\n" +
                "    resources: [\"secrets\"]\n" +
                "    verbs: [\"*\"]");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n" +
                        "[test][desc.yaml] Wildcard verb used in rule {\"apiGroups\":[\"\"],\"resources\":[\"secrets\"],\"verbs\":[\"*\"]}\n",
                "--forcedRules", "wildcard-in-rules");
    }

    @Test
    void hostPathOk(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        volumeMounts:\n" +
                "        - name: config\n" +
                "          mountPath: /config\n" +
                "      volumes:\n" +
                "      - name: config\n" +
                "        persistentVolumeClaim:\n" +
                "          claimName: config");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "writable-host-mount");
    }

    @Test
    void hostPathKo(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        volumeMounts:\n" +
                "        - name: config\n" +
                "          mountPath: /config\n" +
                "      volumes:\n" +
                "      - name: config\n" +
                "        hostPath:\n" +
                "          path: /config");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n[test][desc.yaml] host path '/config' made available to container(s)\n",
                "--forcedRules", "writable-host-mount");
    }

    @Test
    void none(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: app1\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "      - name: app\n" +
                "        volumeMounts:\n" +
                "        - name: config\n" +
                "          mountPath: /config\n" +
                "      volumes:\n" +
                "      - name: config\n" +
                "        hostPath:\n" +
                "          path: /config");
        assertOutput(
                executor, lintCommand,
                "No linting error.\n",
                "--forcedRules", "none");
    }


    @Test
    void sarifReport(@TempDir final Path work, final CommandExecutor executor) throws IOException {
        final var lintCommand = writeAlveolus(work, "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: dont-fire\n" +
                "spec:\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app.kubernetes.io/name: app1\n" +
                "  template:\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: app\n" +
                "          securityContext:\n" +
                "            runAsUser: 0\n" +
                "            runAsNonRoot: false");
        final var report = work.resolve("sarif.json");
        assertOutput(
                executor, lintCommand,
                "There are linting errors:\n" +
                        "[test][desc.yaml] 'runAsNonRoot' is false\n" +
                        "[test][desc.yaml] 'runAsUser' is 0\n",
                "--forcedRules", "run-as-non-root", "--output", report.toString());
        assertEquals(
                "{" +
                        "\"version\":\"2.1.0\",\"$schema\":\"http://json.schemastore.org/sarif-2.1.0-rtm.4\",\"runs\":[" +
                        "{" +
                        "\"tool\":{\"driver\":{\"name\":\"Yupiik Bundlebee\",\"informationUri\":\"https://yupiik.io/bundlebee/\"," +
                        "\"rules\":[" +
                        "{" +
                        "\"id\":\"run-as-non-root\",\"shortDescription\":{\"text\":\"Indicates when containers are not set to runAsNonRoot.\"}," +
                        "\"helpUri\":\"https://www.yupiik.io/bundlebee/commands/lint.configuration.html#_run_as_non_root\",\"properties\":{}}]}" +
                        "}," +
                        "\"artifacts\":[{\"location\":{\"uri\":\"file://" + work + "/bundlebee/kubernetes/desc.yaml\"}}]," +
                        "\"results\":[" +
                        "{\"level\":\"error\",\"text\":{\"text\":\"'runAsNonRoot' is false\"},\"ruleId\":\"run-as-non-root\",\"ruleIndex\":0,\"locations\":[{\"physicalLocation\":{\"artifactLocation\":\"file://" + work + "/bundlebee/kubernetes/desc.yaml\",\"index\":0}}]}," +
                        "{\"level\":\"error\",\"text\":{\"text\":\"'runAsUser' is 0\"},\"ruleId\":\"run-as-non-root\",\"ruleIndex\":0,\"locations\":[{\"physicalLocation\":{\"artifactLocation\":\"file://" + work + "/bundlebee/kubernetes/desc.yaml\",\"index\":0}}]}]}" +
                        "]}",
                Files.readString(report));
    }

    private void assertOutput(final CommandExecutor executor, final String[] lintCommand, final String expected,
                              final String... customArgs) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(
                Stream.concat(Stream.of(lintCommand), Stream.of(customArgs)).toArray(String[]::new)));
        assertEquals(expected, logs);
    }

    private String[] writeAlveolus(final Path work, final String descriptor) throws IOException {
        final var k8s = Files.createDirectories(work.resolve("bundlebee/kubernetes"));
        Files.writeString(k8s.resolve("desc.yaml"), descriptor);
        final var manifest = Files.writeString(k8s.getParent().resolve("manifest.json"), "" +
                "{\n" +
                "  \"alveoli\": [\n" +
                "    {\n" +
                "      \"name\": \"test\",\n" +
                "      \"descriptors\": [{\"name\": \"desc.yaml\"}]\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        return new String[]{"lint", "--failLevel", "OFF", "--alveolus", "test", "--manifest", manifest.toString()};
    }
}
