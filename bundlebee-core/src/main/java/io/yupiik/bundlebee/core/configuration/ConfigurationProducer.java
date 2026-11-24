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

import io.yupiik.bundlebee.core.http.HttpClientConfiguration;
import io.yupiik.bundlebee.core.kube.DeploymentConfiguration;
import io.yupiik.bundlebee.core.kube.KubernetesConfiguration;
import io.yupiik.bundlebee.core.service.MavenConfiguration;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;

/** Exposes typed subgroups without defining their configuration leaves a second time. */
@DefaultScoped
public class ConfigurationProducer {
    @Bean
    @ApplicationScoped
    public HttpClientConfiguration httpclient(final GlobalConfiguration configuration) {
        return configuration.httpclient();
    }

    @Bean
    @ApplicationScoped
    public MavenConfiguration maven(final GlobalConfiguration configuration) {
        return configuration.maven();
    }

    @Bean
    @ApplicationScoped
    public KubernetesConfiguration kube(final GlobalConfiguration configuration) {
        return configuration.kube();
    }

    @Bean
    @ApplicationScoped
    public DeploymentConfiguration deployment(final GlobalConfiguration configuration) {
        return configuration.deployment();
    }
}
