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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.bundlebee.core.command.impl.lint.LintingCheck.LintableDescriptor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LintDynamicTreeTest {
    @ParameterizedTest
    @MethodSource("malformedValues")
    void malformedContainerCollections(final Object value) {
        final var descriptor = pod(Map.of("containers", value, "initContainers", value));
        assertEquals(List.of(), messages(new RunAsNonRoot(), descriptor));
        assertEquals(List.of(), messages(new PrivilegedContainer(), descriptor));
        assertEquals(List.of(), messages(new PrivilegeEscalationContainer(), descriptor));
        assertEquals(List.of(), messages(new PrivilegedPorts(), deployment(descriptor)));
    }

    @Test
    void missingAndNullContainerCollections() {
        for (final var spec :
                List.of(Map.<String, Object>of(), Collections.<String, Object>singletonMap("containers", null))) {
            assertEquals(List.of(), messages(new RunAsNonRoot(), pod(spec)));
            assertEquals(List.of(), messages(new PrivilegedContainer(), pod(spec)));
        }
    }

    @ParameterizedTest
    @MethodSource("malformedValues")
    void malformedSecurityContext(final Object value) {
        final var descriptor = pod(Map.of("containers", List.of(Map.of("securityContext", value))));
        assertEquals(List.of(), messages(new RunAsNonRoot(), descriptor));
        assertEquals(List.of(), messages(new NoReadOnlyRootFS(), descriptor));
        assertEquals(List.of(), messages(new DropNetRawCapability(), descriptor));
        assertEquals(List.of(), messages(new UnsafeSysctls(), descriptor));
    }

    @Test
    void mixedContainersStillReportValidSibling() {
        final var descriptor = pod(Map.of(
                "containers",
                Arrays.asList(
                        null,
                        false,
                        "invalid",
                        Map.of(
                                "securityContext",
                                Map.of(
                                        "privileged",
                                        true,
                                        "allowPrivilegeEscalation",
                                        true,
                                        "runAsNonRoot",
                                        false,
                                        "runAsUser",
                                        0)))));
        assertEquals(List.of("'privileged' is set to true"), messages(new PrivilegedContainer(), descriptor));
        assertEquals(
                List.of("'allowPrivilegeEscalation' is set to true"),
                messages(new PrivilegeEscalationContainer(), descriptor));
        assertEquals(List.of("'runAsNonRoot' is false", "'runAsUser' is 0"), messages(new RunAsNonRoot(), descriptor));
    }

    @Test
    void mixedNestedCollectionsStillReportValidSibling() {
        final var descriptor = pod(Map.of(
                "containers",
                List.of(Map.of(
                        "securityContext",
                                Map.of(
                                        "capabilities",
                                        Map.of("add", Arrays.asList(null, 1, "NET_RAW")),
                                        "sysctls",
                                        Arrays.asList(null, true, Map.of("name", "net.test"))),
                        "ports",
                                Arrays.asList(
                                        null, "invalid", Map.of("containerPort", "80"), Map.of("containerPort", 80)),
                        "env",
                                Arrays.asList(
                                        null,
                                        false,
                                        Map.of("name", "PASSWORD", "valueFrom", Map.of("secretKeyRef", Map.of())))))));
        assertEquals(List.of("'NET_RAW' capabilities usage"), messages(new DropNetRawCapability(), descriptor));
        assertEquals(List.of("Sysctls 'net.test' is not recommended"), messages(new UnsafeSysctls(), descriptor));
        assertEquals(
                List.of("priviledged port used by container: 80"),
                messages(new PrivilegedPorts(), deployment(descriptor)));
        assertEquals(
                List.of("Secret read from env for 'PASSWORD' environment variable"),
                messages(new ReadSecretFromEnvVar(), descriptor));
    }

    @Test
    void nullAndWrongScalarTypesDoNotCoerce() {
        final var context = Map.of(
                "privileged",
                "true",
                "allowPrivilegeEscalation",
                1,
                "runAsUser",
                "0",
                "runAsNonRoot",
                "false",
                "readOnlyRootFilesystem",
                "false");
        final var descriptor = pod(Map.of("containers", List.of(Map.of("securityContext", context))));
        assertEquals(List.of(), messages(new PrivilegedContainer(), descriptor));
        assertEquals(List.of(), messages(new PrivilegeEscalationContainer(), descriptor));
        assertEquals(List.of(), messages(new RunAsNonRoot(), descriptor));
        assertEquals(List.of(), messages(new NoReadOnlyRootFS(), descriptor));
        assertEquals(List.of(), messages(new HostIPC(), pod(Map.of("hostIPC", "true"))));
    }

    @Test
    void malformedAndMissingResourcesHaveNormalDiagnostics() {
        for (final var container :
                List.of(Map.of(), Collections.singletonMap("resources", null), Map.of("resources", "invalid"))) {
            assertEquals(
                    List.of("No resources element in container so no cpu limits."),
                    messages(new CheckCpuLimits(), pod(Map.of("containers", List.of(container)))));
        }
        assertEquals(
                List.of("No limits element in container resources"),
                messages(
                        new CheckCpuLimits(),
                        pod(Map.of("containers", List.of(Map.of("resources", Map.of("limits", false)))))));
    }

    @Test
    void malformedVolumesDoNotHideHostMounts() {
        final var descriptor = pod(Map.of(
                "volumes",
                Arrays.asList(
                        null,
                        1,
                        Map.of("hostPath", "invalid"),
                        Map.of("hostPath", Map.of("path", "/etc")),
                        Map.of("hostPath", Map.of("path", "/var/run/docker.sock")))));
        assertEquals(List.of("'/etc' shouldn't be mounted directly"), messages(new SensitiveHostMounts(), descriptor));
        assertEquals(List.of("docker.sock shouldn't be bound from the host"), messages(new DockerSock(), descriptor));
        assertEquals(2, messages(new WritableHostPath(), descriptor).size());
    }

    @Test
    void duplicateEnvironmentVariablesIgnoreMalformedEntries() {
        final var rule = new DuplicateEnvVar();
        rule.accept(pod(Map.of(
                "containers",
                Arrays.asList(
                        null,
                        false,
                        Map.of(
                                "name",
                                "app",
                                "env",
                                Arrays.asList(null, false, Map.of("name", 42), Map.of("name", "PORT")),
                                "envFrom",
                                Arrays.asList(
                                        null,
                                        true,
                                        Map.of("configMapRef", false),
                                        Map.of("configMapRef", Map.of("name", "config"))))))));
        rule.accept(new LintableDescriptor(
                "test",
                "config",
                Map.of("kind", "ConfigMap", "metadata", Map.of("name", "config"), "data", Map.of("PORT", "8080"))));
        assertEquals(
                List.of("Duplicated environment variables (container=app): [PORT]"),
                rule.afterAllSync().map(LintError::getMessage).toList());
    }

    @Test
    void malformedRoleRulesDoNotHideAccessToSecrets() {
        final var rule = new AccessToSecrets();
        rule.accept(new LintableDescriptor(
                "test",
                "role",
                Map.of(
                        "kind",
                        "Role",
                        "metadata",
                        Map.of("name", "reader"),
                        "rules",
                        Arrays.asList(
                                null,
                                false,
                                Map.of("apiGroups", false),
                                Map.of("verbs", false, "resources", "secrets"),
                                Map.of(
                                        "verbs",
                                        Arrays.asList(null, 42, "get"),
                                        "resources",
                                        Arrays.asList(null, false, "secrets"))))));
        rule.accept(new LintableDescriptor(
                "test",
                "binding",
                Map.of(
                        "kind",
                        "RoleBinding",
                        "metadata",
                        Map.of("name", "binding"),
                        "roleRef",
                        Map.of("apiGroup", "rbac.authorization.k8s.io", "kind", "Role", "name", "reader"))));
        assertEquals(
                List.of("RoleBinding 'binding' enables to get secrets"),
                rule.afterAllSync().map(LintError::getMessage).toList());
    }

    @Test
    void malformedMetadataUsesEmptyDomainNames() {
        for (final var value :
                Arrays.asList(null, false, "metadata", List.of(), Map.of("name", 1, "namespace", false))) {
            final var descriptor = new LintableDescriptor("test", "test", Collections.singletonMap("metadata", value));
            assertEquals("", descriptor.kind());
            assertEquals("", descriptor.name());
            assertEquals("", descriptor.namespace());
        }
    }

    private static Stream<Object> malformedValues() {
        return Stream.of(false, "invalid", 42, Map.of("invalid", true), List.of("invalid", false));
    }

    private LintableDescriptor pod(final Map<String, ?> spec) {
        return new LintableDescriptor("test", "pod", Map.of("kind", "Pod", "spec", spec));
    }

    private LintableDescriptor deployment(final LintableDescriptor pod) {
        return new LintableDescriptor(
                "test",
                "deployment",
                Map.of(
                        "kind",
                        "Deployment",
                        "spec",
                        Map.of("template", Map.of("spec", pod.getDescriptor().get("spec")))));
    }

    private List<String> messages(final CheckByKind rule, final LintableDescriptor descriptor) {
        return rule.validateSync(descriptor).map(LintError::getMessage).toList();
    }
}
