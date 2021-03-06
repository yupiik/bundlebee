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
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.json.JsonValue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
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

            // depending data key entry name we can switch the separator depending first one
            if (it.startsWith("kubernetes.")) {
                final var value = findKubernetesValue(it, "\\.");
                if (value != null) {
                    return value;
                }
            } else if (it.startsWith("kubernetes/")) {
                final var value = findKubernetesValue(it, "/");
                if (value != null) {
                    return value;
                }
            }

            return config.getOptionalValue(it, String.class).orElse(null);
        });
    }

    private String findKubernetesValue(final String key, final String sep) {
        // depending the key we should accept both
        final var segments = key.split(sep);
        switch (segments.length) {
            case 8:
            case 9: // adds timeout in last segment
            case 10: // adds an indent to prefix all new lines (except first one) in last segment
                // kubernetes.<namespace>.serviceaccount.<account name>.secrets.<secret name prefix>.data.<entry name>[.<timeout in seconds>]
                if ("serviceaccount".equals(segments[2]) && "secrets".equals(segments[4]) && "data".equals(segments[6])) {
                    final var namespace = segments[1];
                    final var account = segments[3];
                    final var secretPrefix = segments[5];
                    final var dataName = segments[7];
                    final int timeout = segments.length == 9 ? Integer.parseInt(segments[8]) : 120;
                    final var secret = findSecret(namespace, account, secretPrefix, dataName, timeout);
                    if (segments.length == 10) {
                        return indent(secret, IntStream.range(0, Integer.parseInt(segments[9])).mapToObj(i -> " ").collect(joining()));
                    }
                    return secret;
                }
                break;
            default:
                // try in config
        }
        return null;
    }

    private String indent(final String secret, final String indent) {
        try (final var reader = new BufferedReader(new StringReader(secret))) {
            final var lines = reader.lines().collect(toList());
            return (lines.get(0) + "\n" + lines.stream().skip(1).map(l -> indent + l).collect(joining("\n"))).strip();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String findSecret(final String namespace, final String account, final String secretPrefix,
                              final String dataKey, final int timeout) {
        final var end = Instant.now().plusSeconds(timeout);
        int iterations = 0;
        do {
            iterations++;
            try {
                final var secret = kubeClient
                        .findServiceAccount(namespace, account)
                        .thenCompose(serviceAccount -> {
                            if (!serviceAccount.containsKey("secrets")) {
                                return completedFuture(null);
                            }
                            return serviceAccount
                                    .getJsonArray("secrets").stream()
                                    .filter(json -> json.getValueType() == JsonValue.ValueType.OBJECT)
                                    .map(JsonValue::asJsonObject)
                                    .filter(it -> it.containsKey("name"))
                                    .map(it -> it.getString("name"))
                                    .filter(it -> it.startsWith(secretPrefix))
                                    .sorted() // be deterministic for the same inputs
                                    .findFirst()
                                    .map(secretName -> kubeClient.findSecret(namespace, secretName)
                                            .thenApply(secretJson -> {
                                                if (!secretJson.containsKey("data")) {
                                                    return null;
                                                }
                                                final var data = secretJson.getJsonObject("data");
                                                if (!data.containsKey(dataKey)) {
                                                    return null;
                                                }
                                                final var content = data.getString(dataKey);
                                                try {
                                                    return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
                                                } catch (final RuntimeException re) {
                                                    return content;
                                                }
                                            }))
                                    .orElseGet(() -> completedFuture(null));
                        })
                        .exceptionally(err -> null)
                        .toCompletableFuture()
                        .get();
                if (secret != null) {
                    return secret;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (final ExecutionException e) {
                log.warning(e.getMessage());
            }
            try {
                Thread.sleep(250);
            } catch (final InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (iterations == 20) { // each 5s is more than enough
                log.info("Waiting for " + namespace + "/" + account + "/" + secretPrefix + "/" + dataKey + " secret");
                iterations = 0;
            }
        } while (Instant.now().isBefore(end));
        final var error = "Was not able to read secret " +
                "namespace=" + namespace + "/account=" + account + "/prefix=" + secretPrefix + " in " + timeout + "s";
        log.warning(error);
        throw new IllegalStateException(error);
    }
}
