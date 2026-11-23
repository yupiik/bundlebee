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
package io.yupiik.bundlebee.core.command.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.descriptor.State;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyStateTest {
    @TempDir
    private Path directory;

    @Test
    void preservesEncodedApiBasePathsDuringCleanupAndConfirmation() throws Exception {
        try (final var cluster = new Cluster(directory, "/clusters/team%2Fone")) {
            cluster.run(false);
            assertEquals(1, cluster.deletions.get());
            assertEquals(
                    2,
                    cluster.rawPaths.stream()
                            .filter(path ->
                                    path.equals("/clusters/team%2Fone/api/v1/namespaces/default/configmaps/stale"))
                            .count());
            assertFalse(cluster.current.resources().stream()
                    .anyMatch(resource -> resource.path().endsWith("/stale")));
        }
    }

    @Test
    void checkpointsBeforeCleanupAndRemovesOnlyConfirmedAbsentResources() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.run(false);
            assertEquals(
                    State.Status.PENDING_DELETION,
                    cluster.saved.get(0).resources().stream()
                            .filter(resource -> resource.path().endsWith("/stale"))
                            .findFirst()
                            .orElseThrow()
                            .status());
            assertEquals(
                    List.of("live"),
                    cluster.current.resources().stream()
                            .map(resource ->
                                    resource.path().substring(resource.path().lastIndexOf('/') + 1))
                            .toList());
            assertTrue(cluster.events.indexOf("state-write") < cluster.events.indexOf("delete-stale"));
            assertEquals(List.of("state-write", "delete-stale", "confirm-stale", "state-write"), cluster.events);
        }
    }

    @Test
    void retainsSkippedResourcesForTheNextApply() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.run(true);
            assertEquals(State.Status.PENDING_DELETION, cluster.stale().status());
            assertEquals(0, cluster.deletions.get());
            cluster.run(false);
            assertEquals(1, cluster.deletions.get());
            assertFalse(cluster.current.resources().stream()
                    .anyMatch(resource -> resource.path().endsWith("/stale")));
        }
    }

    @Test
    void flagsFailedCleanupWithoutStoringTheResponseBodyAndPropagatesFailure() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.deleteStatus = 500;
            assertThrows(RuntimeException.class, () -> cluster.run(false));
            assertEquals(State.Status.DELETE_FAILED, cluster.stale().status());
            assertEquals(500, cluster.stale().httpStatus());
            assertFalse(cluster.mapper.toString(cluster.current).contains("sensitive-response"));
            cluster.deleteStatus = 200;
            cluster.run(false);
            assertFalse(cluster.current.resources().stream()
                    .anyMatch(resource -> resource.path().endsWith("/stale")));
        }
    }

    @Test
    void treatsAcceptedDeletionAsPendingWhileTheResourceStillExists() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.deleteStatus = 202;
            cluster.removeOnDelete = false;
            cluster.run(false);
            assertEquals(State.Status.PENDING_DELETION, cluster.stale().status());
            assertEquals(202, cluster.stale().httpStatus());
        }
    }

    @Test
    void confirmsAbsenceEvenForHttp200DeletionWithFinalizers() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.removeOnDelete = false;
            cluster.run(false);
            assertEquals(State.Status.PENDING_DELETION, cluster.stale().status());
            assertEquals(200, cluster.stale().httpStatus());
        }
    }

    @Test
    void treatsAlreadyMissingAsCleanedUp() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.deleteStatus = 404;
            cluster.run(false);
            assertFalse(cluster.current.resources().stream()
                    .anyMatch(resource -> resource.path().endsWith("/stale")));
            assertFalse(cluster.events.contains("confirm-stale"));
        }
    }

    @Test
    void refusesCleanupWhenCheckpointCannotBeSaved() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.failStateWriteAt = 1;
            assertThrows(RuntimeException.class, () -> cluster.run(false));
            assertEquals(0, cluster.deletions.get());
            assertTrue(cluster.saved.isEmpty());
            assertNotNull(cluster.stale());
        }
    }

    @Test
    void retainsCheckpointIfFinalSaveFailsAndRecoversOnTheNextRun() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.failStateWriteAt = 2;
            assertThrows(RuntimeException.class, () -> cluster.run(false));
            assertEquals(State.Status.PENDING_DELETION, cluster.stale().status());
            cluster.failStateWriteAt = -1;
            cluster.deleteStatus = 404;
            cluster.run(false);
            assertFalse(cluster.current.resources().stream()
                    .anyMatch(resource -> resource.path().endsWith("/stale")));
        }
    }

    @Test
    void recordsPartialApplyFailuresAndDoesNotPrunePreviousResources() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            Files.writeString(
                    cluster.descriptor,
                    cluster.mapper.toString(List.of(cluster.resource("live"), cluster.resource("broken"))));
            cluster.failApply = true;
            assertThrows(RuntimeException.class, () -> cluster.run(false));
            assertEquals(0, cluster.deletions.get());
            assertNull(cluster.stale().status());
            final var applied = cluster.current.resources().stream()
                    .filter(resource -> resource.path().endsWith("/live"))
                    .findFirst()
                    .orElseThrow();
            assertNull(applied.status());
            final var failed = cluster.current.resources().stream()
                    .filter(resource -> resource.path().endsWith("/broken"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(State.Status.APPLY_FAILED, failed.status());
        }
    }

    @Test
    void neverSendsCredentialsToAForeignOriginFromStoredState() throws Exception {
        final var requests = new AtomicInteger();
        final var foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        foreign.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        foreign.start();
        try (final var cluster = new Cluster(directory, "")) {
            final var path = "http://127.0.0.1:" + foreign.getAddress().getPort()
                    + "/api/v1/namespaces/default/configmaps/stale";
            cluster.current = new State(1, List.of(new State.Resource(path)));
            assertThrows(RuntimeException.class, () -> cluster.run(false));
            assertEquals(0, requests.get());
            assertEquals(State.Status.DELETE_FAILED, cluster.stale().status());
        } finally {
            foreign.stop(0);
        }
    }

    @Test
    void refusesMalformedOrUnsupportedStateBeforeApplyingAnything() throws Exception {
        try (final var cluster = new Cluster(directory, "")) {
            cluster.rawState = "{broken-json";
            assertThrows(RuntimeException.class, () -> cluster.run(false));
            assertTrue(cluster.events.isEmpty());
            cluster.rawState = cluster.mapper.toString(new State(2, List.of()));
            assertThrows(RuntimeException.class, () -> cluster.run(false));
            assertTrue(cluster.events.isEmpty());
        }
    }

    private static final class Cluster implements AutoCloseable {
        private static final String ROOT = "/api/v1/namespaces/default/";
        private final RuntimeContainer container = ConfiguringContainer.of().start();
        private final Instance<JsonMapper> lookup = container.lookup(JsonMapper.class);
        private final JsonMapper mapper = lookup.instance();
        private final HttpServer server;
        private final Path manifest;
        private final Path descriptor;
        private final String endpoint;
        private final List<State> saved = new CopyOnWriteArrayList<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<String> rawPaths = new CopyOnWriteArrayList<>();
        private final Set<String> absent = ConcurrentHashMap.newKeySet();
        private final AtomicInteger deletions = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private State current;
        private String rawState;
        private int deleteStatus = 200;
        private int failStateWriteAt = -1;
        private boolean removeOnDelete = true;
        private boolean failApply;

        private Cluster(final Path directory, final String basePath) throws Exception {
            manifest = Files.createDirectories(directory.resolve("bundlebee")).resolve("manifest.json");
            final var descriptors = Files.createDirectories(manifest.getParent().resolve("kubernetes"));
            descriptor = descriptors.resolve("live.json");
            Files.writeString(
                    manifest,
                    "{\"alveoli\":[{\"name\":\"state-fixture\",\"descriptors\":[{\"name\":\"live.json\"}]}]}");
            Files.writeString(descriptor, mapper.toString(resource("live")));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
            current = new State(1, List.of(new State.Resource(endpoint + ROOT + "configmaps/stale")));
            server.createContext("/", exchange -> {
                try (exchange) {
                    rawPaths.add(exchange.getRequestURI().getRawPath());
                    final var path = exchange.getRequestURI()
                            .getPath()
                            .substring(URI.create(basePath).getPath().length());
                    final var method = exchange.getRequestMethod();
                    var status = 200;
                    Object body = Map.of();
                    if (path.equals("/api/v1")) {
                        body = Map.of(
                                "resources",
                                List.of(
                                        Map.of("name", "configmaps", "kind", "ConfigMap", "namespaced", true),
                                        Map.of("name", "secrets", "kind", "Secret", "namespaced", true)));
                    } else if (path.equals(ROOT + "secrets/state-fixture-bbs")) {
                        if (method.equals("PATCH") || method.equals("POST") || method.equals("PUT")) {
                            if (writes.incrementAndGet() == failStateWriteAt) {
                                status = 500;
                            } else {
                                final var secret = mapper.fromString(
                                        Map.class,
                                        new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                                final var data = (Map<?, ?>) secret.get("data");
                                current = mapper.fromString(
                                        State.class,
                                        new String(Base64.getDecoder().decode((String) data.get("state")), UTF_8));
                                saved.add(current);
                                events.add("state-write");
                            }
                        }
                        body = Map.of(
                                "apiVersion",
                                "v1",
                                "kind",
                                "Secret",
                                "metadata",
                                Map.of("name", "state-fixture-bbs", "namespace", "default"),
                                "data",
                                Map.of(
                                        "state",
                                        Base64.getEncoder()
                                                .encodeToString((rawState == null ? mapper.toString(current) : rawState)
                                                        .getBytes(UTF_8))));
                    } else if (path.equals(ROOT + "configmaps/stale")) {
                        if (method.equals("DELETE")) {
                            events.add("delete-stale");
                            deletions.incrementAndGet();
                            status = deleteStatus;
                            if (removeOnDelete && status >= 200 && status < 300) {
                                absent.add(path);
                            }
                        } else {
                            events.add("confirm-stale");
                            status = absent.contains(path) ? 404 : 200;
                        }
                        body = Map.of("message", "sensitive-response");
                    } else if (path.startsWith(ROOT + "configmaps/")) {
                        final var name = path.substring(path.lastIndexOf('/') + 1);
                        body = resource(name);
                        if (method.equals("PATCH") && name.equals("broken") && failApply) {
                            status = 500;
                        }
                    }
                    final var bytes = mapper.toString(body).getBytes(UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        private Map<String, Object> resource(final String name) {
            return Map.of(
                    "apiVersion",
                    "v1",
                    "kind",
                    "ConfigMap",
                    "metadata",
                    Map.of("name", name, "namespace", "default"),
                    "data",
                    Map.of("value", "value"));
        }

        private void run(final boolean skipCleanup) throws Exception {
            final var configuration = new HashMap<String, String>();
            configuration.put("bundlebee.kube.api", endpoint);
            configuration.put("kubeconfig", "explicit");
            configuration.put("bundlebee.kube.token", "state-fixture-token");
            configuration.put("bundlebee.kube.http.timeout", "5000");
            configuration.put("bundlebee.httpclient.threads", "2");
            final var configFile = manifest.resolveSibling("kube.properties");
            Files.writeString(configFile, "bundlebee.kube.api=" + endpoint + "\nkubeconfig=explicit\n");
            new BundleBee()
                    .launch(
                            new String[] {
                                "apply",
                                "--alveolus",
                                "state-fixture",
                                "--manifest",
                                manifest.toString(),
                                "--from",
                                manifest.getParent().getParent().toString(),
                                "--trackState",
                                "true",
                                "--injectTimestamp",
                                "false",
                                "--injectBundleBeeMetadata",
                                "false",
                                "--skipStaledResourceDeletion",
                                Boolean.toString(skipCleanup),
                                "--config-file",
                                configFile.toString()
                            },
                            configuration);
        }

        private State.Resource stale() {
            return current.resources().stream()
                    .filter(resource -> resource.path().endsWith("/stale"))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            server.stop(0);
            lookup.close();
            container.close();
        }
    }
}
