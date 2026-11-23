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
package io.yupiik.bundlebee.build;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;

/**
 * Opt-in Java 17 packaged-artifact contracts, not an integration test against a cluster.
 * Enable with {@code -Dbundlebee.rollback.enabled=true}; Maven comparisons default to enabled.
 * Artifacts are never built here. Override {@code bundlebee.rollback.{head,release,current}.jar},
 * {@code bundlebee.rollback.mvn}, and {@code bundlebee.root} for another installation.
 * Every command, log, wire trace and decoded state remains in {@code /tmp/bundlebee-rollback-parity-*}.
 * State tracking is apply's resource inventory/cleanup, not recovery of resource contents on failure.
 */
@EnabledIfSystemProperty(named = "bundlebee.rollback.enabled", matches = "true")
class RollbackStateCompatibilityTest {
    private static final String BASE = "/api/v1/namespaces/default/";
    private static final String LIVE = BASE + "configmaps/live";
    private static final String STALE = BASE + "configmaps/stale";
    private static final String SECRET = BASE + "secrets/parity-bbs";
    private static final String APPLY_QUERY = "?fieldManager=kubectl-client-side-apply&fieldValidation=Strict";
    private final Path root =
            Path.of(System.getProperty("bundlebee.root", "..")).toAbsolutePath().normalize();
    private final Path java = Path.of(System.getProperty("java.home"), "bin/java");

    @Test
    void skippingCleanupRetainsStalePathsForTheNextInventory() throws Exception {
        final var work = Files.createTempDirectory("bundlebee-rollback-parity-skip-inventory-");
        fixture(work);
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var mapper = lookup.instance();
            final var prior = mapper.toString(Map.of(
                    "version",
                    1,
                    "resources",
                    List.of(
                            Map.of("path", "https://kubernetes.api" + LIVE),
                            Map.of("path", "https://kubernetes.api" + STALE))));
            final var skipped = run("state-skip-stale", work, "current-skip", currentJar(), null, prior, mapper);
            assertTrue(skipped.requests().stream().noneMatch(r -> r.method().equals("DELETE")));
            assertInventory(
                    skipped.state(), mapper, Map.of(LIVE, Map.of(), STALE, Map.of("status", "PENDING_DELETION")));
            final var next =
                    run("state-restore-diff", work, "current-next", currentJar(), null, skipped.state(), mapper);
            assertEquals(
                    List.of(STALE),
                    next.requests().stream()
                            .filter(r -> r.method().equals("DELETE"))
                            .map(Request::path)
                            .toList(),
                    "Previously skipped stale paths remain available for later cleanup");
            assertState(next.state(), mapper);
        }
    }

    @Test
    void cleanupHttpFailureFailsApplyAndRetainsTheResource() throws Exception {
        final var work = Files.createTempDirectory("bundlebee-rollback-parity-cleanup-failure-");
        fixture(work);
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var mapper = lookup.instance();
            final var prior = mapper.toString(
                    Map.of("version", 1, "resources", List.of(Map.of("path", "https://kubernetes.api" + STALE))));
            final var result = run("state-cleanup-failure", work, "current-cleanup", currentJar(), null, prior, mapper);
            assertEquals(
                    List.of(STALE),
                    result.requests().stream()
                            .filter(r -> r.method().equals("DELETE"))
                            .map(Request::path)
                            .toList());
            assertInventory(
                    result.states().get(0),
                    mapper,
                    Map.of(LIVE, Map.of(), STALE, Map.of("status", "PENDING_DELETION")));
            assertInventory(
                    result.state(),
                    mapper,
                    Map.of(LIVE, Map.of(), STALE, Map.of("status", "DELETE_FAILED", "httpStatus", 500)));
            assertEquals(2, result.states().size(), "Checkpoint and final actual inventory");
            final var write = result.requests().stream()
                    .filter(r -> r.method().equals("PATCH") && r.path().startsWith(SECRET))
                    .findFirst()
                    .orElseThrow();
            final var deletion = result.requests().stream()
                    .filter(r -> r.method().equals("DELETE"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(result.requests().indexOf(write) < result.requests().indexOf(deletion));
        }
    }

    @Test
    void absoluteInventoryUrlsCannotAddressAnotherOriginWithTheCurrentCredential() throws Exception {
        final var work = Files.createTempDirectory("bundlebee-rollback-parity-foreign-origin-");
        fixture(work);
        final var foreignRequests = new CopyOnWriteArrayList<String>();
        final var foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        foreign.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                foreignRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                        + exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("{}".getBytes(UTF_8));
            }
        });
        foreign.start();
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var mapper = lookup.instance();
            final var prior = mapper.toString(Map.of(
                    "version",
                    1,
                    "resources",
                    List.of(Map.of(
                            "path", "http://127.0.0.1:" + foreign.getAddress().getPort() + LIVE))));
            run("state-foreign-origin", work, "head-foreign", baseline("head", "1.0.33-SNAPSHOT"), null, prior, mapper);
            assertEquals(List.of(), foreignRequests);
            final var result = run("state-foreign-origin", work, "current-foreign", currentJar(), null, prior, mapper);
            assertEquals(
                    List.of(),
                    foreignRequests,
                    "Never forward the configured credential to a persisted foreign origin");
            assertTrue(result.requests().stream().noneMatch(r -> r.method().equals("DELETE")));
            final var foreignPath = "http://127.0.0.1:" + foreign.getAddress().getPort() + LIVE;
            assertInventory(
                    result.state(), mapper, Map.of(LIVE, Map.of(), foreignPath, Map.of("status", "DELETE_FAILED")));
            Files.writeString(work.resolve("foreign-origin.requests"), foreignRequests.toString());
        } finally {
            foreign.stop(0);
        }
    }

    @TestFactory
    Stream<DynamicTest> rollbackAndState() {
        final var selected = Pattern.compile(System.getProperty("bundlebee.rollback.recipes", ".*"));
        return Stream.of(
                        "explicit-rollback",
                        "state-create",
                        "state-restore-diff",
                        "state-skip-stale",
                        "state-malformed",
                        "state-missing-fields",
                        "state-unknown-fields",
                        "state-legacy-top-level",
                        "state-disabled")
                .filter(name -> selected.matcher(name).matches())
                .map(name -> DynamicTest.dynamicTest(name, () -> compare(name)));
    }

    private void compare(final String recipe) throws Exception {
        assertEquals(17, Runtime.version().feature(), "Run this harness with explicit Java 17");
        final var work = Files.createTempDirectory(Path.of("/tmp"), "bundlebee-rollback-parity-" + recipe + "-");
        fixture(work);
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var mapper = lookup.instance();
            final var head = baseline("head", "1.0.33-SNAPSHOT");
            // Produce real legacy wire bytes before feeding them back with a stale inventory entry.
            final var seed = run("state-create", work, "seed-head-cli", head, null, null, mapper);
            assertState(seed.state(), mapper);
            final var restored = new LinkedHashMap<String, Object>();
            object(mapper.fromString(Object.class, seed.state()))
                    .forEach((key, value) -> restored.put((String) key, value));
            restored.put(
                    "resources",
                    List.of(
                            Map.of("path", "https://kubernetes.api" + LIVE),
                            Map.of("path", "https://kubernetes.api" + STALE),
                            Map.of()));
            final String prior = switch (recipe) {
                case "state-restore-diff", "state-skip-stale", "state-disabled", "state-legacy-top-level" ->
                    mapper.toString(restored);
                case "state-malformed" -> "{broken-json";
                case "state-missing-fields" -> "{}";
                case "state-unknown-fields" -> {
                    restored.put("future", Map.of("nested", List.of(1, true, "ignored")));
                    yield mapper.toString(restored);
                }
                default -> null;
            };
            if (prior != null) {
                Files.writeString(work.resolve("prior-state.json"), prior);
            }
            final var baseline = run(recipe, work, "head-cli", head, null, prior, mapper);
            assertContract(recipe, baseline, mapper);
            final var comparisons = new ArrayList<Executable>();
            comparisons.add(() -> check(
                    recipe,
                    baseline,
                    run(recipe, work, "release-cli", baseline("release", "1.0.32"), null, prior, mapper),
                    mapper));
            comparisons.add(() -> check(
                    recipe, baseline, run(recipe, work, "current-cli", currentJar(), null, prior, mapper), mapper));
            if (Boolean.parseBoolean(System.getProperty("bundlebee.rollback.maven", "true"))) {
                comparisons.add(() -> check(
                        recipe,
                        baseline,
                        run(
                                recipe,
                                work,
                                "release-maven",
                                null,
                                System.getProperty("bundlebee.rollback.release.plugin", "1.0.32"),
                                prior,
                                mapper),
                        mapper));
                comparisons.add(() -> check(
                        recipe,
                        baseline,
                        run(
                                recipe,
                                work,
                                "current-maven",
                                null,
                                System.getProperty("bundlebee.rollback.current.plugin", "2.0.0-SNAPSHOT"),
                                prior,
                                mapper),
                        mapper));
            }
            assertAll("Rollback/state approved contracts: " + recipe + " (evidence: " + work + ")", comparisons);
        }
    }

    private void check(final String recipe, final Outcome expected, final Outcome actual, final JsonMapper mapper) {
        assertContract(recipe, actual, mapper);
        if (!actual.current()) {
            assertEquals(expected.requests(), actual.requests(), "Legacy CLI/plugin baseline parity");
            assertEquals(
                    expected.state() == null ? null : mapper.fromString(Object.class, expected.state()),
                    actual.state() == null ? null : mapper.fromString(Object.class, actual.state()));
        } else if (!recipe.equals("state-malformed")) {
            // The approved v1 bugfix intentionally changes cleanup and state persistence.
            // Compare descriptor traffic only; assert checkpoint/final state separately.
            assertEquals(
                    descriptorTraffic(expected.requests()),
                    descriptorTraffic(actual.requests()),
                    "Descriptor apply/rollback remains compatible; state traffic intentionally differs");
        }
    }

    private void assertContract(final String recipe, final Outcome outcome, final JsonMapper mapper) {
        final var requests = outcome.requests();
        assertFalse(requests.isEmpty());
        if (outcome.current() && recipe.equals("state-malformed")) {
            assertTrue(
                    requests.stream()
                            .noneMatch(r ->
                                    Set.of("POST", "PATCH", "PUT", "DELETE").contains(r.method())),
                    "Invalid persisted state fails closed before descriptor mutation or state overwrite");
            assertEquals(null, outcome.state());
            return;
        }
        if (recipe.equals("explicit-rollback")) {
            final var deletes =
                    requests.stream().filter(r -> r.method().equals("DELETE")).toList();
            assertEquals(
                    List.of(LIVE + "?gracePeriodSeconds=0"),
                    deletes.stream().map(Request::path).toList());
            final var mutation = requests.stream()
                    .filter(r -> Set.of("POST", "PATCH", "PUT").contains(r.method()))
                    .toList();
            assertEquals(1, mutation.size());
            assertEquals(
                    BASE + "configmaps/previous" + APPLY_QUERY, mutation.get(0).path());
            assertEquals(
                    "old", object(object(mutation.get(0).body()).get("data")).get("value"));
            assertTrue(
                    requests.indexOf(deletes.get(0)) < requests.indexOf(mutation.get(0)),
                    "Delete before restoring previous resources");
            assertTrue(
                    requests.stream().noneMatch(r -> r.path().contains("secrets")),
                    "Rollback does not persist apply state");
            return;
        }
        final var deletes = requests.stream()
                .filter(r -> r.method().equals("DELETE"))
                .map(Request::path)
                .toList();
        final var shouldDrop = outcome.current()
                ? recipe.equals("state-restore-diff") || recipe.equals("state-unknown-fields")
                : recipe.equals("state-legacy-top-level");
        assertEquals(
                shouldDrop ? List.of(STALE) : List.of(), deletes, "Drop only stale paths, not retained or null paths");
        assertEquals(
                1,
                requests.stream()
                        .filter(r -> r.method().equals("PATCH") && r.path().equals(LIVE + APPLY_QUERY))
                        .count());
        if (recipe.equals("state-disabled")) {
            assertEquals(null, outcome.state());
            assertTrue(requests.stream().noneMatch(r -> r.path().contains("secrets")));
        } else {
            final var firstResource = requests.stream()
                    .filter(r -> !r.path().equals("/api/v1"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(SECRET, firstResource.path(), "Load previous inventory before applying");
            assertEquals("GET", firstResource.method());
            if (outcome.current() && recipe.equals("state-skip-stale")) {
                assertInventory(
                        outcome.state(), mapper, Map.of(LIVE, Map.of(), STALE, Map.of("status", "PENDING_DELETION")));
            } else {
                assertState(outcome.state(), mapper);
            }
            final var write = requests.stream()
                    .filter(r -> Set.of("PATCH", "POST").contains(r.method())
                            && r.path().startsWith(BASE + "secrets"))
                    .findFirst()
                    .orElseThrow();
            final var secret = object(write.body());
            assertEquals("v1", secret.get("apiVersion"));
            assertEquals("Secret", secret.get("kind"));
            assertEquals("parity-bbs", object(secret.get("metadata")).get("name"));
            assertEquals("default", object(secret.get("metadata")).get("namespace"));
            if (shouldDrop) {
                assertTrue(
                        requests.indexOf(write)
                                < requests.indexOf(requests.stream()
                                        .filter(r -> r.method().equals("DELETE"))
                                        .findFirst()
                                        .orElseThrow()),
                        "Save checkpoint before stale cleanup");
                if (outcome.current()) {
                    assertEquals(2, outcome.states().size(), "Checkpoint and final inventory writes");
                    assertInventory(
                            outcome.states().get(0),
                            mapper,
                            Map.of(LIVE, Map.of(), STALE, Map.of("status", "PENDING_DELETION")));
                    final var deleteIndex = requests.indexOf(requests.stream()
                            .filter(r -> r.method().equals("DELETE"))
                            .findFirst()
                            .orElseThrow());
                    final var confirmation = requests.stream()
                            .filter(r -> r.method().equals("GET") && r.path().equals(STALE))
                            .findFirst()
                            .orElseThrow();
                    assertTrue(deleteIndex < requests.indexOf(confirmation), "Confirm absence after DELETE");
                    final var writes = requests.stream()
                            .filter(r -> Set.of("PATCH", "POST").contains(r.method())
                                    && r.path().startsWith(SECRET))
                            .toList();
                    assertTrue(
                            requests.indexOf(confirmation) < requests.indexOf(writes.get(1)),
                            "Persist final inventory only after confirmation");
                }
            }
        }
    }

    private void assertState(final String state, final JsonMapper mapper) {
        assertEquals(
                mapper.fromString(
                        Object.class,
                        "{\"version\":1,\"resources\":[{\"path\":\"https://kubernetes.api" + LIVE + "\"}]}"),
                mapper.fromString(Object.class, state),
                "Finite v1 State wire contract: version and resource path inventory");
    }

    private void assertInventory(
            final String state, final JsonMapper mapper, final Map<String, Map<String, Object>> expected) {
        final var decoded = object(mapper.fromString(Object.class, state));
        assertEquals(1, ((Number) decoded.get("version")).intValue());
        final var resources = new LinkedHashMap<String, Object>();
        for (final var entry : (List<?>) decoded.get("resources")) {
            final var resource = new LinkedHashMap<String, Object>();
            object(entry).forEach((key, value) -> resource.put((String) key, value));
            final var path = (String) resource.remove("path");
            final var key = path.startsWith("https://kubernetes.api")
                    ? path.substring("https://kubernetes.api".length())
                    : path;
            assertFalse(resources.containsKey(key), "No duplicate inventory paths");
            resources.put(key, resource);
        }
        // Decode the expected JSON as well to normalize JSON number representations.
        assertEquals(mapper.fromString(Object.class, mapper.toString(expected)), resources);
    }

    private List<Request> descriptorTraffic(final List<Request> requests) {
        return requests.stream()
                .filter(r -> !r.path().startsWith(SECRET) && !r.path().equals(STALE))
                .toList();
    }

    private Path baseline(final String label, final String version) {
        return Path.of(System.getProperty(
                "bundlebee.rollback." + label + ".jar",
                "/tmp/bundlebee-validation-baseline-" + label + "/bundlebee-core/target/bundlebee-core-" + version
                        + "-fat.jar"));
    }

    private Path currentJar() throws Exception {
        final var configured = System.getProperty("bundlebee.rollback.current.jar");
        if (configured != null) {
            return Path.of(configured);
        }
        try (final var files = Files.list(root.resolve("bundlebee-core/target"))) {
            final var jars =
                    files.filter(p -> p.toString().endsWith("-fat.jar")).toList();
            assertEquals(1, jars.size(), "Specify bundlebee.rollback.current.jar if ambiguous");
            return jars.get(0);
        }
    }

    private void fixture(final Path work) throws Exception {
        Files.writeString(work.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>rollback</groupId>
                  <artifactId>parity</artifactId><version>1</version>
                </project>
                """);
        final var descriptors = Files.createDirectories(work.resolve("bundlebee/kubernetes"));
        Files.writeString(work.resolve("bundlebee/manifest.json"), """
                {"alveoli":[
                  {"name":"parity","version":"2.0.0","descriptors":[{"name":"live.json","type":"kubernetes"}]},
                  {"name":"previous","version":"1.0.0","descriptors":[{"name":"previous.json","type":"kubernetes"}]}
                ]}
                """);
        for (final var name : List.of("live", "previous")) {
            Files.writeString(
                    descriptors.resolve(name + ".json"), """
                    {"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"%s","namespace":"default"},"data":{"value":"%s"}}
                    """.formatted(name, name.equals("live") ? "new" : "old"));
        }
    }

    private Outcome run(
            final String recipe,
            final Path work,
            final String label,
            final Path jar,
            final String plugin,
            final String prior,
            final JsonMapper mapper)
            throws Exception {
        assertEquals(17, Runtime.version().feature(), "Run this harness with explicit Java 17");
        final var requests = new CopyOnWriteArrayList<Request>();
        final var wire = new CopyOnWriteArrayList<Request>();
        final var states = new CopyOnWriteArrayList<String>();
        final var failures = new CopyOnWriteArrayList<Throwable>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try (exchange) {
                final var text = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                final var body = text.isBlank() ? null : mapper.fromString(Object.class, text);
                final var path = exchange.getRequestURI().toString();
                final var method = exchange.getRequestMethod();
                wire.add(new Request(
                        method, path, body, exchange.getRequestHeaders().getFirst("Content-Type")));
                Object comparableBody = body;
                if (Set.of("POST", "PATCH", "PUT").contains(method) && path.startsWith(BASE + "secrets")) {
                    final var rawState = new String(
                            Base64.getDecoder().decode((String)
                                    object(object(body).get("data")).get("state")),
                            UTF_8);
                    Files.writeString(work.resolve(label + ".raw-state.json"), rawState);
                    final var state = rawState.replace(endpoint, "https://kubernetes.api");
                    states.add(state);
                    final var copy = new LinkedHashMap<String, Object>();
                    object(body).forEach((key, value) -> copy.put((String) key, value));
                    // Compare decoded inventory, not base64 spelling of JSON object property order.
                    copy.put("data", Map.of("state", mapper.fromString(Object.class, state)));
                    comparableBody = copy;
                }
                requests.add(new Request(
                        method,
                        path,
                        normalize(comparableBody),
                        exchange.getRequestHeaders().getFirst("Content-Type")));
                final var stateRead = method.equals("GET") && path.equals(SECRET);
                final var missing = stateRead && prior == null;
                final var encoded = prior == null
                        ? ""
                        : Base64.getEncoder()
                                .encodeToString(prior.replace("https://kubernetes.api", endpoint)
                                        .getBytes(UTF_8));
                final var response = stateRead && prior != null
                        ? mapper.toString(
                                recipe.equals("state-legacy-top-level")
                                        ? Map.of("state", encoded)
                                        : Map.of(
                                                "apiVersion",
                                                "v1",
                                                "kind",
                                                "Secret",
                                                "metadata",
                                                Map.of("name", "parity-bbs"),
                                                "data",
                                                Map.of("state", encoded)))
                        : "{}";
                final var bytes = response.getBytes(UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(
                        recipe.equals("state-cleanup-failure") && method.equals("DELETE")
                                ? 500
                                : method.equals("GET") && path.equals(STALE)
                                        ? 404
                                        : missing ? 404 : method.equals("POST") ? 201 : 200,
                        bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (final Throwable error) {
                failures.add(error);
            }
        });
        server.start();
        try {
            final var rollback = recipe.equals("explicit-rollback");
            final var action = rollback ? "rollback" : "apply";
            final var settings = new LinkedHashMap<String, String>();
            settings.put("bundlebee." + action + ".from", work.toString());
            settings.put(
                    "bundlebee." + action + ".manifest",
                    work.resolve("bundlebee/manifest.json").toString());
            settings.put("bundlebee." + action + ".alveolus", "parity");
            settings.put("bundlebee.kubeconfig", "explicit");
            settings.put(
                    "bundlebee.kube.api",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            settings.put("bundlebee.apply.injectTimestamp", "false");
            settings.put("bundlebee.apply.injectBundleBeeMetadata", "false");
            settings.put("bundlebee.apply.trackState", Boolean.toString(!rollback && !recipe.equals("state-disabled")));
            settings.put(
                    "bundlebee.apply.skipStaledResourceDeletion", Boolean.toString(recipe.equals("state-skip-stale")));
            if (recipe.equals("state-foreign-origin")) {
                settings.put("bundlebee.kube.token", "state-fixture-token");
            }
            if (rollback) {
                settings.put("bundlebee.rollback.previousFrom", work.toString());
                settings.put(
                        "bundlebee.rollback.previousManifest",
                        work.resolve("bundlebee/manifest.json").toString());
                settings.put("bundlebee.rollback.previousAlveolus", "previous");
                settings.put("bundlebee.rollback.awaitTimeout", "0");
                settings.put("bundlebee.rollback.gracePeriodSeconds", "0");
            }
            final var command = new ArrayList<String>();
            if (jar != null) {
                assertTrue(Files.isRegularFile(jar), "Missing prebuilt jar: " + jar);
                command.addAll(List.of(java.toString(), "-Duser.home=" + work, "-jar", jar.toString(), action));
                settings.forEach((key, value) -> {
                    command.add("--" + key.replace('.', '-'));
                    command.add(value);
                });
            } else {
                command.addAll(List.of(
                        System.getProperty("bundlebee.rollback.mvn", "mvn"),
                        "-o",
                        "-B",
                        "-ntp",
                        "io.yupiik:bundlebee-maven-plugin:" + plugin + ":" + action));
                settings.forEach((key, value) -> command.add("-D" + key + "=" + value));
            }
            Files.writeString(work.resolve(label + ".command"), String.join("\n", command));
            final var builder = new ProcessBuilder(command)
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(work.resolve(label + ".log").toFile());
            builder.environment().clear();
            builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
            builder.environment().put("PATH", System.getProperty("java.home") + "/bin:/usr/bin:/bin");
            final var process = builder.start();
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Timeout: " + work.resolve(label + ".log"));
                Files.writeString(work.resolve(label + ".requests"), requests.toString());
                Files.writeString(work.resolve(label + ".wire-requests"), wire.toString());
                if (!states.isEmpty()) {
                    Files.writeString(work.resolve(label + ".state.json"), states.get(states.size() - 1));
                    Files.writeString(
                            work.resolve(label + ".states.json"),
                            mapper.toString(states.stream()
                                    .map(state -> mapper.fromString(Object.class, state))
                                    .toList()));
                }
                assertEquals(List.of(), failures, "Fake server failures");
                final var expectedFailure = label.startsWith("current-")
                        && Set.of("state-malformed", "state-cleanup-failure", "state-foreign-origin")
                                .contains(recipe);
                assertEquals(expectedFailure, process.exitValue() != 0, () -> read(work.resolve(label + ".log")));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                }
            }
            assertTrue(
                    states.size() <= (label.startsWith("current-") ? 2 : 1), "Bounded checkpoint/final state writes");
            return new Outcome(
                    List.copyOf(requests),
                    states.isEmpty() ? null : states.get(states.size() - 1),
                    List.copyOf(states),
                    label.startsWith("current-"));
        } finally {
            server.stop(0);
        }
    }

    private Object normalize(final Object value) {
        // Rollback deliberately forces timestamp labels on. Normalize only that volatile label.
        if (value instanceof Map<?, ?> map) {
            final var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, item) ->
                    copy.put((String) key, key.equals("bundlebee.timestamp") ? "<timestamp>" : normalize(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalize).toList();
        }
        return value;
    }

    private Map<?, ?> object(final Object value) {
        assertTrue(value instanceof Map<?, ?>, "Expected JSON object, got " + value);
        return (Map<?, ?>) value;
    }

    private String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final Exception error) {
            return error.toString();
        }
    }

    private record Request(String method, String path, Object body, String contentType) {}

    private record Outcome(List<Request> requests, String state, List<String> states, boolean current) {}
}
