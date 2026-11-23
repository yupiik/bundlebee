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
package io.yupiik.bundlebee.core.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.configuration.GlobalConfiguration;
import io.yupiik.bundlebee.core.service.ContainerSanitizer;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KubeClientTreeTest {
    private RuntimeContainer container;
    private final List<RuntimeContainer> clients = new ArrayList<>();

    @BeforeEach
    void start() {
        container = ConfiguringContainer.of().start();
    }

    @AfterEach
    void stop() {
        clients.forEach(RuntimeContainer::close);
        container.close();
    }

    @Test
    void defaultSanitizationAndConfiguredJsonPatchUseFusionTrees() throws Exception {
        try (final var mapper = mapper()) {
            final var api = new Api();
            final var defaults = client(mapper, api, Map.of());
            final var sanitized = defaults.forDescriptorWithOriginal(
                            null,
                            "{\"kind\":\"ConfigMap\",\"$schema\":\"ignored\",\"$bundlebeeIgnoredLintingRules\":[],\"data\":{\"nullable\":null}}",
                            "json",
                            item -> CompletableFuture.completedFuture(item))
                    .toCompletableFuture()
                    .get()
                    .get(0);
            assertTrue(sanitized.getRaw().containsKey("$schema"));
            assertFalse(sanitized.getPrepared().containsKey("$schema"));
            assertFalse(sanitized.getPrepared().containsKey("$bundlebeeIgnoredLintingRules"));
            assertTrue(((Map<?, ?>) sanitized.getPrepared().get("data")).containsKey("nullable"));
            final var custom = client(
                    mapper,
                    api,
                    Map.of(
                            "bundlebee.kube.implicitlyDroppedAttributes",
                            "[{\"op\":\"add\",\"path\":\"/added\",\"value\":null}]"));
            final var patched = custom.forDescriptor(
                            null, "{\"kind\":\"ConfigMap\"}", "json", CompletableFuture::completedFuture)
                    .toCompletableFuture()
                    .get()
                    .get(0);
            assertTrue(patched.containsKey("added"));
            assertNull(patched.get("added"));
        }
    }

    @Test
    void metadataInjectionDoesNotMutateRawTreesOrDropNulls() {
        try (final var mapper = mapper()) {
            final var client = client(mapper, new Api(), Map.of());
            final var labels = new LinkedHashMap<String, Object>();
            labels.put("old", null);
            final var metadata = Map.<String, Object>of("name", "test", "labels", labels);
            final var original = Map.<String, Object>of("metadata", metadata, "kind", "ConfigMap");
            final var updated = client.injectMetadata(original, Map.of("new", "value"));
            assertEquals(2, ((Map<?, ?>) ((Map<?, ?>) updated.get("metadata")).get("labels")).size());
            assertNull(((Map<?, ?>) ((Map<?, ?>) updated.get("metadata")).get("labels")).get("old"));
            assertEquals(1, labels.size());
            assertFalse(labels.containsKey("new"));
        }
    }

    @Test
    void unchangedPersistentVolumeClaimSkipsUpdateWithNumericAndTimestampDifferences() throws Exception {
        try (final var mapper = mapper()) {
            final var api = new Api();
            api.existing =
                    "{\"kind\":\"PersistentVolumeClaim\",\"metadata\":{\"name\":\"test\",\"labels\":{\"bundlebee.timestamp\":\"old\"}},\"spec\":{\"replicas\":1.0}}";
            client(mapper, api, Map.of())
                    .apply(
                            "{\"kind\":\"PersistentVolumeClaim\",\"metadata\":{\"name\":\"test\",\"labels\":{\"bundlebee.timestamp\":\"new\"}},\"spec\":{\"replicas\":1}}",
                            "json",
                            Map.of())
                    .toCompletableFuture()
                    .get();
            assertEquals(List.of("GET"), api.methods);
        }
    }

    @Test
    void patchFallbackAndFilteredStatefulSetNullsArePreserved() throws Exception {
        try (final var mapper = mapper()) {
            final var api = new Api();
            api.existing = "{\"kind\":\"StatefulSet\"}";
            api.rejectFirstPatch = true;
            client(mapper, api, Map.of())
                    .apply(
                            "{\"kind\":\"StatefulSet\",\"metadata\":{\"name\":\"test\"},\"spec\":{\"replicas\":null,\"forbidden\":true}}",
                            "json",
                            Map.of())
                    .toCompletableFuture()
                    .get();
            assertEquals(List.of("GET", "PATCH", "PATCH"), api.methods);
            assertEquals(
                    List.of("application/strategic-merge-patch+json", "application/merge-patch+json"), api.patchTypes);
        }
    }

    @Test
    void serviceAccountConflictUsesTypedStatusAndRetries() throws Exception {
        try (final var mapper = mapper()) {
            final var api = new Api();
            api.conflictOnCreate = true;
            client(mapper, api, Map.of())
                    .apply("{\"kind\":\"ServiceAccount\",\"metadata\":{\"name\":\"test\"}}", "json", Map.of(), true)
                    .toCompletableFuture()
                    .get();
            assertEquals(List.of("POST", "GET", "PATCH"), api.methods);
        }
    }

    @Test
    void jsonHttpResponsePreservesOpenContentAndDelegatesMetadata() throws Exception {
        try (final var mapper = mapper()) {
            final var api = new Api();
            api.existing = "{\"kind\":\"Custom\",\"data\":{\"values\":[1,\"two\",true,null]},\"unknown\":null}";
            final var response = client(mapper, api, Map.of())
                    .getResources("{\"kind\":\"Custom\",\"metadata\":{\"name\":\"test\"}}", "json")
                    .toCompletableFuture()
                    .get()
                    .get(0);
            assertEquals(200, response.statusCode());
            assertEquals("GET", response.request().method());
            assertEquals(response.request().uri(), response.uri());
            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertTrue(response.previousResponse().isEmpty());
            assertTrue(response.sslSession().isEmpty());
            assertTrue(response.headers().map().isEmpty());
            assertTrue(response.body().containsKey("unknown"));
            assertNull(response.body().get("unknown"));
            assertEquals(
                    Arrays.asList(new BigDecimal("1"), "two", true, null),
                    ((Map<?, ?>) response.body().get("data")).get("values"));
        }
    }

    @Test
    void descriptorsRejectScalarListEntries() {
        try (final var mapper = mapper()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client(mapper, new Api(), Map.of())
                            .forDescriptor(null, "[1]", "json", CompletableFuture::completedFuture));
        }
    }

    private JsonMapper mapper() {
        return container.lookup(JsonMapper.class).instance();
    }

    private KubeClient client(final JsonMapper mapper, final Api api, final Map<String, String> configuration) {
        final GlobalConfiguration settings;
        try (final var configured = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                ApplicationScoped.class, ConfigurationSource.class, () -> configuration::get))
                        .start();
                final var instance = configured.lookup(GlobalConfiguration.class)) {
            settings = instance.instance();
        }
        final var clientContainer = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(new ProvidedInstanceBean<>(ApplicationScoped.class, KubernetesClient.class, () -> api))
                .start();
        clients.add(clientContainer);
        final var client = new KubeClient(
                new Yaml2JsonConverter(container, mapper),
                new ContainerSanitizer(),
                new ApiPreloader(mapper, api),
                clientContainer,
                mapper,
                null,
                settings.deployment(),
                settings.kube(),
                () -> "default",
                new KubernetesClientProducer());
        client.init();
        return client;
    }

    private static class Api extends KubernetesClient {
        private final List<String> methods = new ArrayList<>();
        private final List<String> patchTypes = new ArrayList<>();
        private String existing = "{}";
        private boolean rejectFirstPatch;
        private boolean conflictOnCreate;

        private Api() {
            super(new KubernetesClientConfiguration()
                    .setMaster("http://localhost")
                    .setToken("none"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) execute(request);
        }

        private CompletableFuture<HttpResponse<String>> execute(final HttpRequest request) {
            methods.add(request.method());
            var status = 200;
            if (conflictOnCreate && "POST".equals(request.method())) {
                return CompletableFuture.completedFuture(
                        new Response(
                                request,
                                409,
                                "{\"kind\":\"Status\",\"reason\":\"AlreadyExists\",\"details\":{\"kind\":\"serviceaccounts\",\"name\":\"test\"},\"code\":409}"));
            }
            if ("PATCH".equals(request.method())) {
                patchTypes.add(request.headers().firstValue("Content-Type").orElseThrow());
                if (rejectFirstPatch && patchTypes.size() == 1) {
                    status = 415;
                }
            }
            return CompletableFuture.completedFuture(new Response(request, status, existing));
        }
    }

    private record Response(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (k, v) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
