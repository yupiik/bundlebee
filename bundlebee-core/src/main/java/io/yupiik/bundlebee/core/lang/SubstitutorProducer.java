/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.lang;

import io.yupiik.bundlebee.core.kube.KubeClient;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.net.URI;
import java.util.Objects;

@ApplicationScoped
public class SubstitutorProducer {
    @Inject
    private KubeClient kubeClient;

    @Produces
    public Substitutor substitutor(final Config config) {
        return new Substitutor(it -> {
            if (it.startsWith("kubeconfig.cluster.") && it.endsWith(".ip")) {
                final var name = it.substring("kubeconfig.cluster.".length(), it.length() - ".ip".length());
                return URI.create(
                        kubeClient.getLoadedKubeConfig()
                                .getClusters().stream()
                                .filter(c -> Objects.equals(c.getName(), name))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("No cluster named '" + name + "' found"))
                                .getCluster()
                                .getServer())
                        .getHost();
            }
            return config.getOptionalValue(it, String.class).orElse(null);
        });
    }
}
