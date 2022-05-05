/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.Maven;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.json.JsonValue;
import javax.json.spi.JsonProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    @Inject
    @BundleBee
    private JsonProvider json;

    @Inject
    private Maven maven;

    @Produces
    public Substitutor substitutor(final Config config) {
        return new Substitutor(it -> {
            try {
                if (it.startsWith("bundlebee-inline-file:")) {
                    final var bytes = readResource(it, "bundlebee-inline-file:");
                    return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
                }
                if (it.startsWith("bundlebee-base64-file:")) {
                    final var src = readResource(it, "bundlebee-base64-file:");
                    return src == null ? null : Base64.getEncoder().encodeToString(src);
                }
                if (it.startsWith("bundlebee-base64:")) {
                    return Base64.getEncoder().encodeToString(it.substring("bundlebee-base64:".length()).getBytes(StandardCharsets.UTF_8));
                }
                if (it.startsWith("bundlebee-quote-escaped-inline-file:")) {
                    final var resource = readResource(it, "bundlebee-quote-escaped-inline-file:");
                    return resource == null ? null : new String(resource, StandardCharsets.UTF_8)
                            .replace("'", "\\'")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\\\n");
                }
                if (it.startsWith("bundlebee-json-inline-file:")) {
                    final var resource = readResource(it, "bundlebee-json-inline-file:");
                    if (resource == null) {
                        return null;
                    }
                    final var value = json.createValue(new String(resource, StandardCharsets.UTF_8)).toString();
                    return value.substring(1, value.length() - 1);
                }
            } catch (final IOException ioe) {
                throw new IllegalStateException(ioe);
            }
            if (it.startsWith("bundlebee-indent:")) {
                final var sub = it.substring("bundlebee-indent:".length());
                final var sep = sub.indexOf(':');
                if (sep < 0) {
                    return it;
                }
                return indent(
                        sub.substring(sep + 1),
                        IntStream.range(0, Integer.parseInt(sub.substring(0, sep)))
                                .mapToObj(i -> " ")
                                .collect(joining()),
                        true);
            }
            if (it.startsWith("bundlebee-strip:")) {
                return it.substring("bundlebee-strip:".length()).strip();
            }
            if (it.startsWith("bundlebee-strip-leading:")) {
                return it.substring("bundlebee-strip-leading:".length()).stripLeading();
            }
            if (it.startsWith("bundlebee-strip-trailing:")) {
                return it.substring("bundlebee-strip-trailing:".length()).stripTrailing();
            }
            if (it.startsWith("bundlebee-maven-server-username:")) {
                return maven.findServerPassword(it.substring("bundlebee-maven-server-username:".length()))
                        .map(Maven.Server::getUsername)
                        .orElse(null);
            }
            if (it.startsWith("bundlebee-maven-server-password:")) {
                return maven.findServerPassword(it.substring("bundlebee-maven-server-password:".length()))
                        .map(Maven.Server::getPassword)
                        .orElse(null);
            }
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
            if ("timestamp".equals(it)) {
                return Long.toString(Instant.now().toEpochMilli());
            }
            if ("timestampSec".equals(it)) {
                return Long.toString(Instant.now().getEpochSecond());
            }
            if ("now".equals(it)) {
                return OffsetDateTime.now().toString();
            }
            if ("nowUTC".equals(it)) {
                return OffsetDateTime.now().atZoneSameInstant(ZoneId.of("UTC")).toString();
            }
            if (it.startsWith("date:")) {
                final var pattern = it.substring("date:".length());
                return OffsetDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
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
        }) {
            @Override
            protected String getOrDefault(final String varName, final String varDefaultValue) {
                final var value = super.getOrDefault(varName, varDefaultValue);
                log.finest(() -> "Resolved '" + varName + "' to '" + value + "'");
                return value;
            }
        };
    }

    private byte[] readResource(final String text, final String prefix) throws IOException {
        final var name = text.substring(prefix.length());
        final var path = Path.of(name);
        if (Files.exists(path)) {
            return Files.readAllBytes(path);
        }
        try (final var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) {
                return null;
            }
            return stream.readAllBytes();
        }
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
                        return indent(secret, IntStream.range(0, Integer.parseInt(segments[9])).mapToObj(i -> " ").collect(joining()), false);
                    }
                    return secret;
                }
                break;
            default:
                // try in config
        }
        return null;
    }

    private String indent(final String secret, final String indent, final boolean indentFirstLine) {
        try (final var reader = new BufferedReader(new StringReader(secret))) {
            final var lines = reader.lines().collect(toList());
            if (indentFirstLine) {
                return lines.stream().map(l -> indent + l).collect(joining("\n"));
            }
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
