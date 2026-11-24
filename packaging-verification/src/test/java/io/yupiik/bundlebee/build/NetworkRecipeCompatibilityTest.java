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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;

/** Runs only packaged artifacts; no cluster, reactor build, or fixture rewriting. */
@EnabledIfSystemProperty(named = "bundlebee.network.enabled", matches = "true")
class NetworkRecipeCompatibilityTest {
    private static final Pattern QUERY_SEPARATOR = Pattern.compile("\\?");
    private final Path root =
            Path.of(System.getProperty("bundlebee.root", "..")).toAbsolutePath().normalize();
    private final Path java = Path.of(System.getProperty("java.home"), "bin/java");

    @TestFactory
    Stream<DynamicTest> networkRecipes() {
        final var selected = Pattern.compile(System.getProperty("bundlebee.network.recipes", ".*"));
        return Stream.of(
                        new Recipe("patch-existing", "apply", "ApplyCommandTest.apply", false, List.of()),
                        new Recipe("create-missing", "apply", "ApplyCommandTest.apply", true, List.of()),
                        new Recipe(
                                "put-existing",
                                "apply",
                                "ApplyCommandTest.apply",
                                false,
                                List.of("bundlebee.kube.putOnUpdate", "true")),
                        new Recipe("merge-patch-fallback", "apply", "ApplyCommandTest.apply", false, List.of()),
                        new Recipe(
                                "namespace-override",
                                "apply",
                                "ApplyCommandTest.apply",
                                false,
                                List.of("bundlebee.kube.namespace", "network-parity")),
                        new Recipe("await-exists", "apply", "ApplyCommandTest.applyAwait", false, List.of()),
                        new Recipe(
                                "await-json-pointer",
                                "apply",
                                "ApplyCommandTest.applyAwaitCondition",
                                false,
                                List.of()),
                        new Recipe("dependencies", "apply", "ApplyCommandTest.withdep", false, List.of()),
                        new Recipe(
                                "excluded-descriptor",
                                "apply",
                                "ApplyCommandTest.withdep",
                                false,
                                List.of("excludedDescriptors", "ApplyCommandTest.d1")),
                        new Recipe(
                                "conditional-patch",
                                "apply",
                                "ApplyCommandTest.includeIfPatch",
                                false,
                                List.of("ApplyCommandTest.includeIfPatch", "true")),
                        new Recipe(
                                "skipped-patch",
                                "apply",
                                "ApplyCommandTest.includeIfPatch",
                                false,
                                List.of("ApplyCommandTest.includeIfPatch", "false")),
                        new Recipe(
                                "nested-placeholder-template",
                                "apply",
                                "ApplyCommandTest.fromTemplate",
                                false,
                                List.of("ApplyCommandTest.fromTemplate.port", "8181")),
                        new Recipe("custom-content-type", "apply", "customContentType", false, List.of()),
                        new Recipe("delete-dependencies", "delete", "ApplyCommandTest.withdep", false, List.of()),
                        new Recipe("delete-missing", "delete", "ApplyCommandTest.withdep", true, List.of()))
                .filter(recipe -> selected.matcher(recipe.name()).matches())
                .map(recipe -> DynamicTest.dynamicTest(recipe.name(), () -> compare(recipe)));
    }

    private void compare(final Recipe recipe) throws Exception {
        assertEquals(17, Runtime.version().feature(), "Run this harness with explicit Java 17");
        final var work = Files.createTempDirectory(Path.of("/tmp"), "bundlebee-network-parity-" + recipe.name() + "-");
        Files.writeString(work.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>network</groupId>
                  <artifactId>recipe</artifactId><version>1</version>
                </project>
                """);
        try (final var container = ConfiguringContainer.of().start();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var mapper = lookup.instance();
            final var baseline = run(recipe, work, "head-cli", baseline("head", "1.0.33-SNAPSHOT"), null, mapper);
            assertFalse(baseline.isEmpty(), "Baseline must contact the loopback server");
            final var method = recipe.command().equals("delete")
                    ? "DELETE"
                    : recipe.missing() ? "POST" : recipe.name().equals("put-existing") ? "PUT" : "PATCH";
            assertTrue(baseline.stream().anyMatch(r -> r.method().equals(method)), baseline.toString());
            if (recipe.name().startsWith("await-")) {
                assertEquals(
                        2,
                        baseline.stream()
                                .filter(r ->
                                        r.method().equals("GET") && r.path().endsWith("/services/s"))
                                .count());
            }
            if (recipe.name().equals("conditional-patch")) {
                assertTrue(baseline.toString().contains("patched=true"), baseline.toString());
            }
            if (recipe.name().equals("skipped-patch")) {
                assertFalse(baseline.toString().contains("patched="), baseline.toString());
            }
            if (recipe.name().equals("nested-placeholder-template")) {
                assertTrue(baseline.toString().contains("port=8181"), baseline.toString());
                assertTrue(baseline.toString().contains("port=7070"), baseline.toString());
            }
            final var results = new ArrayList<Executable>();
            results.add(() -> assertEquals(
                    baseline, run(recipe, work, "release-cli", baseline("release", "1.0.32"), null, mapper)));
            results.add(() -> assertEquals(baseline, run(recipe, work, "current-cli", currentJar(), null, mapper)));
            if (Boolean.parseBoolean(System.getProperty("bundlebee.network.maven", "true"))) {
                results.add(() -> assertEquals(baseline, run(recipe, work, "release-maven", null, "1.0.32", mapper)));
                results.add(() ->
                        assertEquals(baseline, run(recipe, work, "current-maven", null, "2.0.0-SNAPSHOT", mapper)));
            }
            assertAll("Request parity: " + recipe.name() + " (artifacts in " + work + ")", results);
        }
    }

    private Path baseline(final String name, final String version) {
        return Path.of(System.getProperty(
                "bundlebee.network." + name + ".jar",
                "/tmp/bundlebee-validation-baseline-" + name + "/bundlebee-core/target/bundlebee-core-" + version
                        + "-fat.jar"));
    }

    private Path currentJar() throws Exception {
        try (final var paths = Files.list(root.resolve("bundlebee-core/target"))) {
            return paths.filter(p -> p.toString().endsWith("-fat.jar"))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private List<Request> run(
            final Recipe recipe,
            final Path work,
            final String label,
            final Path jar,
            final String pluginVersion,
            final JsonMapper mapper)
            throws Exception {
        final var requests = new CopyOnWriteArrayList<Request>();
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                final var body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
                requests.add(new Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(),
                        body.isBlank() ? null : mapper.fromString(Object.class, body),
                        exchange.getRequestHeaders().getFirst("Content-Type")));
                final var missing = recipe.missing()
                        && (exchange.getRequestMethod().equals("GET")
                                || exchange.getRequestMethod().equals("DELETE"));
                final var unsupportedPatch = recipe.name().equals("merge-patch-fallback")
                        && exchange.getRequestMethod().equals("PATCH")
                        && "application/strategic-merge-patch+json"
                                .equals(exchange.getRequestHeaders().getFirst("Content-Type"));
                final var response = (missing
                                ? "{\"kind\":\"Status\",\"reason\":\"NotFound\"}"
                                : "{\"status\":{\"phase\":\"Active\"}}")
                        .getBytes(UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(
                        missing
                                ? 404
                                : unsupportedPatch
                                        ? 415
                                        : exchange.getRequestMethod().equals("POST") ? 201 : 200,
                        response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            final var settings = new ArrayList<>(List.of(
                    "from",
                    root.resolve("bundlebee-core/src/test/resources").toString(),
                    "manifest",
                    root.resolve("bundlebee-core/src/test/resources/bundlebee/manifest.json")
                            .toString(),
                    "alveolus",
                    recipe.alveolus(),
                    "kubeconfig",
                    "explicit",
                    "bundlebee-kube-api",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "injectTimestamp",
                    "false",
                    "injectBundleBeeMetadata",
                    "false"));
            settings.addAll(recipe.options());
            final var command = new ArrayList<String>();
            if (jar != null) {
                command.addAll(
                        List.of(java.toString(), "-Duser.home=" + work, "-jar", jar.toString(), recipe.command()));
                for (int i = 0; i < settings.size(); i += 2) {
                    command.add("--" + settings.get(i).replace('.', '-'));
                    command.add(settings.get(i + 1));
                }
            } else {
                command.addAll(List.of(
                        System.getProperty("bundlebee.network.mvn", "mvn"),
                        "-o",
                        "-B",
                        "-ntp",
                        "io.yupiik:bundlebee-maven-plugin:" + pluginVersion + ":" + recipe.command()));
                for (int i = 0; i < settings.size(); i += 2) {
                    final var key = settings.get(i);
                    final var property = switch (key) {
                        case "bundlebee-kube-api" -> "bundlebee.kube.api";
                        case "kubeconfig" -> "bundlebee.kubeconfig";
                        case "injectTimestamp",
                                "injectBundleBeeMetadata",
                                "alveolus",
                                "from",
                                "manifest",
                                "excludedDescriptors" -> "bundlebee." + recipe.command() + "." + key;
                        default -> key;
                    };
                    command.add("-D" + property + "=" + settings.get(i + 1));
                }
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
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Timed out: " + work.resolve(label + ".log"));
                Files.writeString(work.resolve(label + ".requests"), requests.toString());
                assertEquals(0, process.exitValue(), () -> read(work.resolve(label + ".log")));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                }
            }
            // Only independent resources can race; preserve the request order for each resource path.
            // Do not reorder different resources for dependency/deletion-order recipes.
            if (recipe.name().contains("dependencies")) {
                return List.copyOf(requests);
            }
            return requests.stream()
                    .sorted(Comparator.comparing(r -> QUERY_SEPARATOR.split(r.path(), 2)[0]))
                    .toList();
        } finally {
            server.stop(0);
        }
    }

    private String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final Exception error) {
            return error.toString();
        }
    }

    private record Recipe(String name, String command, String alveolus, boolean missing, List<String> options) {}

    private record Request(String method, String path, Object body, String contentType) {}
}
