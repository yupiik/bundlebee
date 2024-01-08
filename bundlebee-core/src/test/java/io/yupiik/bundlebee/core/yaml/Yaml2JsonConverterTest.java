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
package io.yupiik.bundlebee.core.yaml;

import io.yupiik.bundlebee.core.kube.KubeConfig;
import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Cdi
class Yaml2JsonConverterTest {
    @Inject
    private Yaml2JsonConverter converter;


    @Test
    void convert() {
        final var yaml = readYaml();
        final var loaded = converter.convert(KubeConfig.class, yaml);

        // the String.join/split(,) are just there to make the diff easier to read when this test fails
        final var actual = String.join("\n", ("KubeConfig(apiVersion=v1\n" +
                " kind=Config\n" +
                " currentContext=minikube\n" +
                " clusters=[KubeConfig.NamedCluster(name=minikube\n" +
                " cluster=KubeConfig.Cluster(server=https://192.168.49.2:8443\n" +
                " certificateAuthority=minikube/ca.crt\n" +
                " certificateAuthorityData=null\n" +
                " insecureSkipTlsVerify=false\n" +
                " extensions=[KubeConfig.NamedExtension(name=cluster_info\n" +
                " extension={\"last-update\":\"Tue\n" +
                " 02 Feb 2021 16:29:29 CET\"\n" +
                "\"provider\":\"minikube.sigs.k8s.io\"\n" +
                "\"version\":\"v1.17.0\"})]))]\n" +
                " contexts=[KubeConfig.NamedContext(name=minikube\n" +
                " context=KubeConfig.Context(cluster=minikube\n" +
                " namespace=default\n" +
                " user=minikube\n" +
                " certificateAuthority=null\n" +
                " extensions=[KubeConfig.NamedExtension(name=context_info\n" +
                " extension={\"last-update\":\"Tue\n" +
                " 02 Feb 2021 16:29:29 CET\"\n" +
                "\"provider\":\"minikube.sigs.k8s.io\"\n" +
                "\"version\":\"v1.17.0\"})]))]\n" +
                " users=[KubeConfig.NamedUser(name=minikube\n" +
                " user=KubeConfig.User(username=null\n" +
                " password=null\n" +
                " token=null\n" +
                " tokenFile=null\n" +
                " clientCertificate=minikube/profiles/minikube/client.crt\n" +
                " clientCertificateData=null\n" +
                " clientKey=minikube/profiles/minikube/client.key\n" +
                " clientKeyData=null))])")
                .split(","));
        final var expected = String.join("\n", loaded.toString().split(","));
        assertEquals(actual, expected);
    }

    private String readYaml() {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream("kubeconfig.minikube.sample")), StandardCharsets.UTF_8))) {
            return reader.lines().collect(joining("\n"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
