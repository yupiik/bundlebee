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
package io.yupiik.bundlebee.core.configuration;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.http.HttpClientConfiguration;
import io.yupiik.bundlebee.core.kube.DeploymentConfiguration;
import io.yupiik.bundlebee.core.kube.KubernetesConfiguration;
import io.yupiik.bundlebee.core.service.MavenConfiguration;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GlobalConfigurationTest {
    @Test
    void generatedDefaultsAndTypedServiceGroupsHaveOneDefinition() {
        try (final var container = container(Map.of());
                final var global = container.lookup(GlobalConfiguration.class);
                final var http = container.lookup(HttpClientConfiguration.class);
                final var maven = container.lookup(MavenConfiguration.class);
                final var kube = container.lookup(KubernetesConfiguration.class);
                final var deployment = container.lookup(DeploymentConfiguration.class)) {
            final var settings = global.instance();
            assertEquals(settings.httpclient(), http.instance());
            assertEquals(settings.maven(), maven.instance());
            assertEquals(settings.kube(), kube.instance());
            assertEquals(settings.deployment(), deployment.instance());
            assertEquals(-1, settings.httpclient().threads());
            assertEquals("none", settings.httpclient().forcedHttpVersion());
            assertEquals("none", settings.httpclient().connectTimeout());
            assertEquals("NORMAL", settings.httpclient().followRedirects());
            assertEquals(30000, settings.maven().timeout());
            assertEquals("auto", settings.kube().kubeconfig());
            assertEquals(UNSET, settings.kube().token());
            assertEquals(UNSET, settings.kube().context());
            assertEquals(UNSET, settings.kube().proxy().host());
            assertEquals(3128, settings.kube().proxy().port());
            assertEquals(UNSET, settings.kube().proxy().username());
            assertEquals(UNSET, settings.kube().proxy().password());
            assertTrue(settings.kube().proxy().setProxySystemProperties());
            assertEquals(60000, settings.kube().http().timeout());
            assertFalse(settings.kube().rateLimiter().enabled());
            assertEquals(100, settings.kube().rateLimiter().permits());
            assertEquals(1000, settings.kube().rateLimiter().window());
            assertEquals("none", settings.kube().tokenFile());
            assertNull(settings.kube().certificates());
            assertEquals("Strict", settings.deployment().fieldValidation());
            assertEquals(
                    List.of(
                            "replicas",
                            "template",
                            "updateStrategy",
                            "persistentVolumeClaimRetentionPolicy",
                            "minReadySeconds",
                            "serviceName",
                            "selector"),
                    settings.deployment().statefulsetSpecAllowedAttributes());
            assertEquals(UNSET, settings.help().command());
            assertTrue(settings.help().showArgs());
            assertTrue(settings.help().showSharedOptions());
        }
    }

    @Test
    void nestedOverridesUseExistingLeafKeysAndKeepDeploymentAtTheKubePrefix() {
        try (final var container = container(Map.ofEntries(
                        Map.entry("bundlebee.httpclient.threads", "3"),
                        Map.entry("bundlebee.maven.http.timeout", "1234"),
                        Map.entry("bundlebee.kube.kubeconfig", "explicit"),
                        Map.entry("bundlebee.kube.http.timeout", "42"),
                        Map.entry("bundlebee.kube.rateLimiter.permits", "7"),
                        Map.entry("bundlebee.kube.proxy.port", "123"),
                        Map.entry("bundlebee.kube.fieldValidation", "Warn"),
                        Map.entry("bundlebee.kube.force", "true"),
                        Map.entry("bundlebee.help.command", "apply"),
                        Map.entry("bundlebee.help.args", "false"),
                        Map.entry("bundlebee.help.shared", "false")));
                final var global = container.lookup(GlobalConfiguration.class);
                final var deployment = container.lookup(DeploymentConfiguration.class)) {
            final var settings = global.instance();
            assertEquals(3, settings.httpclient().threads());
            assertEquals(1234, settings.maven().timeout());
            assertEquals("explicit", settings.kube().kubeconfig());
            assertEquals(42, settings.kube().http().timeout());
            assertEquals(7, settings.kube().rateLimiter().permits());
            assertEquals(123, settings.kube().proxy().port());
            assertEquals("Warn", deployment.instance().fieldValidation());
            assertTrue(deployment.instance().force());
            assertEquals("apply", settings.help().command());
            assertFalse(settings.help().showArgs());
            assertFalse(settings.help().showSharedOptions());
        }
    }

    @Test
    void generatedSharedMetadataIsUniqueAndUsesTheNewKubeconfigLeaf() {
        try (final var container = container(Map.of("kubeconfig", "legacy-alias"));
                final var global = container.lookup(GlobalConfiguration.class);
                final var extractor = container.lookup(ParameterExtractor.class)) {
            // The runtime adapter can still handle its legacy alias; the typed root does not consume it.
            assertEquals("auto", global.instance().kube().kubeconfig());
            final var parameters = extractor.instance().sharedParameters().toList();
            final var indexed =
                    parameters.stream().collect(toMap(CliCommand.Parameter::configName, parameter -> parameter));
            assertEquals(parameters.size(), indexed.size());
            assertFalse(indexed.containsKey("kubeconfig"));
            assertEquals(
                    "--bundlebee-kube-kubeconfig",
                    indexed.get("bundlebee.kube.kubeconfig").cliName());
            assertEquals("auto", extractor.instance().defaultValue(indexed.get("bundlebee.kube.kubeconfig")));
            assertEquals("Strict", extractor.instance().defaultValue(indexed.get("bundlebee.kube.fieldValidation")));
            assertFalse(indexed.keySet().stream().anyMatch(key -> key.startsWith("bundlebee.kube.deployment.")));
            assertTrue(indexed.keySet().stream().allMatch(key -> key.startsWith("bundlebee.")));
        }
    }

    private RuntimeContainer container(final Map<String, String> values) {
        return ConfiguringContainer.of()
                .register(
                        new ProvidedInstanceBean<>(
                                ApplicationScoped.class, Configuration.class, () -> Configuration.of(values)) {
                            @Override
                            public int priority() {
                                // Test generated defaults independently of inherited Surefire system properties.
                                return 2000;
                            }
                        })
                .start();
    }
}
