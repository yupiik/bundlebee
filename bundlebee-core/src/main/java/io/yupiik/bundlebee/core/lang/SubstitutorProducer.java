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
package io.yupiik.bundlebee.core.lang;

import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.Maven;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.SEVERE;
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

    @Inject
    private BeanManager beanManager;

    @Inject
    private Event<OnPlaceholder> onPlaceholderEvent;

    @Produces
    public Substitutor substitutor(final Config config) {
        final var self = new AtomicReference<Substitutor>();
        final var ref = new Substitutor(it -> doSubstitute(self, config, it)) {
            @Override
            protected String getOrDefault(final String varName, final String varDefaultValue) {
                final var value = super.getOrDefault(varName, varDefaultValue);
                onPlaceholder(varName, varDefaultValue, value);
                return value;
            }
        };
        self.set(ref);
        return ref;
    }

    protected void onPlaceholder(final String varName, final String varDefaultValue, final String value) {
        log.finest(() -> "Resolved '" + varName + "' to '" + value + "'");
        if (onPlaceholderEvent != null) {
            onPlaceholderEvent.fire(new OnPlaceholder(varName, varDefaultValue, value));
        }
    }

    protected String doSubstitute(final AtomicReference<Substitutor> self, final Config config, final String placeholder) {
        try {
            if (placeholder.startsWith("bundlebee-inline-file:")) {
                final var bytes = readResource(placeholder, "bundlebee-inline-file:");
                return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
            }
            if (placeholder.startsWith("bundlebee-inlined-file:")) {
                final var bytes = readResource(placeholder, "bundlebee-inlined-file:");
                return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8)
                        // for json not using a space will cause }\n}\n... to be }} and break nested interpolation
                        .replace("\n", " ");
            }
            if (placeholder.startsWith("bundlebee-base64-file:")) {
                final var src = readResource(placeholder, "bundlebee-base64-file:");
                return src == null ? null : Base64.getEncoder().encodeToString(src);
            }
            if (placeholder.startsWith("bundlebee-base64:")) {
                return Base64.getEncoder().encodeToString(placeholder.substring("bundlebee-base64:".length()).getBytes(StandardCharsets.UTF_8));
            }
            if (placeholder.startsWith("bundlebee-quote-escaped-inline-file:")) {
                final var resource = readResource(placeholder, "bundlebee-quote-escaped-inline-file:");
                return resource == null ? null : new String(resource, StandardCharsets.UTF_8)
                        .replace("'", "\\'")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\\\n");
            }
            if (placeholder.startsWith("bundlebee-json-inline-file:")) {
                final var resource = readResource(placeholder, "bundlebee-json-inline-file:");
                if (resource == null) {
                    return null;
                }
                // ensure nested interpolation is done before otherwise double escaping is way harder to handle
                final var content = self.get().replace(new String(resource, StandardCharsets.UTF_8));
                final var value = json.createValue(content).toString();
                return value.substring(1, value.length() - 1);
            }
            if (placeholder.startsWith("bundlebee-json-string:")) {
                final var value = json.createValue(placeholder.substring("bundlebee-json-string:".length())).toString();
                return value.substring(1, value.length() - 1);
            }
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        if (placeholder.startsWith("bundlebee-indent:")) {
            final var sub = placeholder.substring("bundlebee-indent:".length());
            final var sep = sub.indexOf(':');
            if (sep < 0) {
                return placeholder;
            }
            return indent(
                    sub.substring(sep + 1),
                    IntStream.range(0, Integer.parseInt(sub.substring(0, sep)))
                            .mapToObj(i -> " ")
                            .collect(joining()),
                    true);
        }
        if (placeholder.startsWith("bundlebee-strip:")) {
            return placeholder.substring("bundlebee-strip:".length()).strip();
        }
        if (placeholder.startsWith("bundlebee-digest:")) {
            try {
                final var text = placeholder.substring("bundlebee-digest:".length());
                final int sep1 = text.indexOf(',');
                final int sep2 = text.indexOf(',', sep1 + 1);
                final var digest = MessageDigest.getInstance(text.substring(sep1 + 1, sep2).strip())
                        .digest(text.substring(sep2 + 1).strip().getBytes(StandardCharsets.UTF_8));
                final var encoding = text.substring(0, sep1).strip();
                switch (encoding.toLowerCase(ROOT)) {
                    case "base64":
                        return Base64.getEncoder().encodeToString(digest);
                    default:
                        throw new IllegalArgumentException("Unknown encoding: '" + encoding + "'");
                }
            } catch (final NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        if (placeholder.startsWith("bundlebee-uppercase:")) {
            return placeholder.substring("bundlebee-uppercase:".length()).toUpperCase(ROOT);
        }
        if (placeholder.startsWith("bundlebee-lowercase:")) {
            return placeholder.substring("bundlebee-lowercase:".length()).toLowerCase(ROOT);
        }
        if (placeholder.startsWith("bundlebee-strip-leading:")) {
            return placeholder.substring("bundlebee-strip-leading:".length()).stripLeading();
        }
        if (placeholder.startsWith("bundlebee-strip-trailing:")) {
            return placeholder.substring("bundlebee-strip-trailing:".length()).stripTrailing();
        }
        if (placeholder.startsWith("bundlebee-maven-server-username:")) {
            return maven.findServerPassword(placeholder.substring("bundlebee-maven-server-username:".length()))
                    .map(Maven.Server::getUsername)
                    .orElse(null);
        }
        if (placeholder.startsWith("bundlebee-maven-server-password:")) {
            return maven.findServerPassword(placeholder.substring("bundlebee-maven-server-password:".length()))
                    .map(Maven.Server::getPassword)
                    .orElse(null);
        }
        if (placeholder.startsWith("kubeconfig.cluster.") && placeholder.endsWith(".ip")) {
            final var name = placeholder.substring("kubeconfig.cluster.".length(), placeholder.length() - ".ip".length());
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
        if ("timestamp".equals(placeholder)) {
            return Long.toString(Instant.now().toEpochMilli());
        }
        if ("timestampSec".equals(placeholder)) {
            return Long.toString(Instant.now().getEpochSecond());
        }
        if ("now".equals(placeholder)) {
            return OffsetDateTime.now().toString();
        }
        if ("nowUTC".equals(placeholder)) {
            return OffsetDateTime.now().atZoneSameInstant(ZoneId.of("UTC")).toString();
        }
        if (placeholder.startsWith("date:")) {
            final var pattern = placeholder.substring("date:".length());
            return OffsetDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
        }
        if (placeholder.startsWith("jsr223:")) {
            try {
                final var resourceContent = readResource(placeholder, "jsr223:");
                final var script = ofNullable(resourceContent)
                        .map(c -> new String(c, StandardCharsets.UTF_8))
                        .orElseGet(() -> placeholder.substring("jsr223:".length()));
                return Scripts.execute(
                        script,
                        resourceContent == null ? null : placeholder.substring(placeholder.lastIndexOf('.') + 1),
                        beanManager);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        // depending data key entry name we can switch the separator depending first one
        if (placeholder.startsWith("kubernetes.")) {
            final var value = findKubernetesValue(placeholder, "\\.");
            if (value != null) {
                return value;
            }
        } else if (placeholder.startsWith("kubernetes/")) {
            final var value = findKubernetesValue(placeholder, "/");
            if (value != null) {
                return value;
            }
        }

        return config.getOptionalValue(placeholder, String.class).orElse(null);
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

    private static final class Scripts {
        private Scripts() {
            // no-op
        }

        private static String execute(final String content, final String lang, final BeanManager beanManager) {
            final var lg = ofNullable(lang).orElseGet(() -> guessLang(content));
            final var manager = new javax.script.ScriptEngineManager();
            var engine = manager.getEngineByExtension(lg);
            if (engine == null) {
                engine = manager.getEngineByName(lg);
                if (engine == null) {
                    engine = manager.getEngineByMimeType(lg);
                    if (engine == null) {
                        throw new IllegalStateException("" +
                                "No engine matching lang: '" + lg + "', " +
                                "add a comment line with `bundlebee.language: <lang>` to refine the language or " +
                                "ensure your JSR223 implementation is in the classpath.");
                    }
                }
            }
            final var bindings = engine.createBindings();
            bindings.put("lookupByName", (Function<String, Object>) name -> {
                final Bean<?> bean = beanManager.resolve(beanManager.getBeans(name));
                if (bean == null) {
                    throw new IllegalArgumentException("No bean '" + name + "' found.");
                }
                // important: on dependent beans it will leak the creation context, accepted cause it is a script and rare but not recommended
                return beanManager.getReference(bean, bean.getBeanClass(), beanManager.createCreationalContext(null));
            });
            bindings.put("lookupByType", (Function<Class<?>, Object>) type -> {
                final Bean<?> bean = beanManager.resolve(beanManager.getBeans(type));
                if (bean == null) {
                    throw new IllegalArgumentException("No bean " + type + " found.");
                }
                // important: on dependent beans it will leak the creation context, accepted cause it is a script and rare but not recommended
                return beanManager.getReference(bean, bean.getBeanClass(), beanManager.createCreationalContext(null));
            });
            try {
                return ofNullable(engine.eval(content, bindings)).map(String::valueOf).orElse(null);
            } catch (final javax.script.ScriptException e) {
                Logger.getLogger(Scripts.class.getName()).log(SEVERE, e, e::getMessage);
                throw new IllegalStateException(e);
            }
        }

        private static String guessLang(final String content) {
            final var marker = "bundlebee.language:";
            return Stream.of(content.split("\n"))
                    .map(String::trim)
                    .filter(it -> it.contains(marker))
                    .map(it -> it.substring(it.indexOf(marker + marker.length())).trim())
                    .findFirst()
                    .orElse("js");
        }
    }
}
