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
package io.yupiik.bundlebee.core.service;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.configuration.ThreadLocalConfigSource;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.lang.SubstitutorProducer;
import io.yupiik.bundlebee.core.test.BaseTestModule;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@FusionSupport(modules = AlveolusHandlerTest.TestConfigurationModule.class)
class AlveolusHandlerTest {
    public static class TestConfigurationModule extends BaseTestModule {
        public TestConfigurationModule() {
            super(Map.of(
                    "bundlebee.kube.skipLoadConfig", "true",
                    "message", "config",
                    "fallback", "config",
                    "option", "config"));
        }
    }

    @Fusion
    private AlveolusHandler handler;

    @Fusion
    private ArchiveReader archives;

    @Fusion
    private JsonMapper mapper;

    @Fusion
    private SubstitutorProducer producer;

    @Fusion
    private Substitutor substitutor;

    @Fusion
    private ThreadLocalConfigSource contextSource;

    @Fusion
    private Configuration configuration;

    @Test
    void rootLaunchFailureIsReturnedAsAStage() {
        final var manifest = mapper.fromString(Manifest.class, "{\"alveoli\":[{\"name\":\"main\"}]}");
        final var original = new IllegalArgumentException("root launch failure");
        final var execution = handler.executeOnAlveolus(
                null,
                manifest,
                manifest.alveoli().get(0),
                ctx -> {
                    throw original;
                },
                (ctx, desc) -> completedFuture(null),
                archives.newCache(),
                null,
                "root-failure");
        assertSame(
                original,
                assertThrows(
                                CompletionException.class,
                                () -> execution.toCompletableFuture().join())
                        .getCause());
    }

    @Test
    void manifestDependencyContextsPrecedeInvocationWithoutLeakingAcrossAsyncBranches(@TempDir final Path directory)
            throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(
                kubernetes.resolve("test.json"), "{{message}}|{{inherited}}|{{option}}|{{fallback}}|{{executionId}}");
        final var manifest = locatedManifest(directory, """
                {"alveoli":[
                  {"name":"root","placeholders":{"message":"parent","inherited":"parent"},
                   "dependencies":[{"name":"left"},{"name":"right"}],
                   "descriptors":[{"name":"test.json","interpolate":true}]},
                  {"name":"left","placeholders":{"message":"left"},
                   "descriptors":[{"name":"test.json","interpolate":true}]},
                  {"name":"right","placeholders":{"message":"right"},
                   "descriptors":[{"name":"test.json","interpolate":true}]}]}
                """);
        final var gates = new ConcurrentHashMap<String, CompletableFuture<Void>>();
        final var results = new ConcurrentHashMap<String, String>();
        final var operations = new ArrayList<CompletableFuture<?>>();
        final var executor = Executors.newFixedThreadPool(2);
        try {
            for (final var id : List.of("one", "two")) {
                producer.setInvocationPlaceholders(id, Map.of("message", "invocation", "option", id));
                operations.add(handler.executeOnAlveolus(
                                null,
                                manifest,
                                manifest.alveoli().get(0),
                                ctx -> {
                                    if ("root".equals(ctx.getAlveolus().name())) {
                                        return completedFuture(null);
                                    }
                                    final var gate = new CompletableFuture<Void>();
                                    gates.put(id + "/" + ctx.getAlveolus().name(), gate);
                                    return gate;
                                },
                                (ctx, desc) -> {
                                    results.put(id + "/" + ctx.getAlveolus().name(), desc.getContent());
                                    return completedFuture(null);
                                },
                                archives.newCache(),
                                null,
                                id)
                        .toCompletableFuture());
            }
            assertEquals(4, gates.size());
            assertTrue(results.isEmpty());
            // The callbacks resume on different threads and deliberately interleave executions and branches.
            final var completions = gates.values().stream()
                    .map(gate -> executor.submit(() -> gate.complete(null)))
                    .toList();
            for (final var completion : completions) {
                completion.get(5, TimeUnit.SECONDS);
            }
            for (final var operation : operations) {
                operation.get(5, TimeUnit.SECONDS);
            }
            for (final var id : List.of("one", "two")) {
                for (final var name : List.of("left", "right", "root")) {
                    assertEquals(
                            ("root".equals(name) ? "parent" : name) + "|parent|" + id + "|config|" + id,
                            results.get(id + "/" + name));
                }
                assertEquals(
                        "invocation|" + id + "|config", substitutor.replace("{{message}}|{{option}}|{{fallback}}", id));
            }
            assertTrue(contextSource.getProperties().isEmpty());
            assertEquals("config", configuration.get("option").orElseThrow());
        } finally {
            gates.values().forEach(gate -> gate.complete(null));
            executor.shutdownNow();
            producer.clearInvocationPlaceholders("one");
            producer.clearInvocationPlaceholders("two");
        }
        assertEquals("config", substitutor.replace("{{message}}", "one"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForLaunchedDescriptorWhenPreparationOrLaunchThrows(
            final boolean launchFailure, @TempDir final Path directory) throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(kubernetes.resolve("first.json"), "{}");
        Files.writeString(kubernetes.resolve("second.json"), launchFailure ? "{}" : "{{bundlebee-base64-decode:%%%}}");
        final var manifest = locatedManifest(directory, """
                {"alveoli":[{"name":"main","descriptors":[
                  {"name":"first.json","interpolate":true},
                  {"name":"second.json","interpolate":true}]}]}
                """);
        final var pending = new CompletableFuture<Void>();
        final var original = new IllegalArgumentException("launch failure");
        final var siblingFailure = new IllegalStateException("sibling failure");
        final var launched = new ArrayList<String>();
        final var execution = handler.executeOnAlveolus(
                        null,
                        manifest,
                        manifest.alveoli().get(0),
                        null,
                        (ctx, desc) -> {
                            launched.add(desc.getConfiguration().name());
                            if ("first.json".equals(desc.getConfiguration().name())) {
                                return pending;
                            }
                            throw original;
                        },
                        archives.newCache(),
                        null,
                        "failure")
                .toCompletableFuture();
        assertFalse(execution.isDone());
        assertEquals(launchFailure ? List.of("first.json", "second.json") : List.of("first.json"), launched);
        pending.completeExceptionally(siblingFailure);
        final var failure =
                assertThrows(CompletionException.class, execution::join).getCause();
        assertEquals(2, failure.getSuppressed().length);
        assertTrue(List.of(failure.getSuppressed()).contains(siblingFailure));
        if (launchFailure) {
            assertTrue(List.of(failure.getSuppressed()).contains(original));
        } else {
            assertTrue(List.of(failure.getSuppressed()).stream().anyMatch(IllegalArgumentException.class::isInstance));
        }
    }

    @Test
    void waitsForLaunchedDependencyWhenNextDependencyLookupThrows(@TempDir final Path directory) throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(kubernetes.resolve("first.json"), "{}");
        final var manifest = locatedManifest(directory, """
                {"alveoli":[
                  {"name":"main","dependencies":[{"name":"first"},{"name":"missing-regression-dependency"}]},
                  {"name":"first","descriptors":[{"name":"first.json","interpolate":false}]}]}
                """);
        final var pending = new CompletableFuture<Void>();
        final var execution = handler.executeOnAlveolus(
                        null,
                        manifest,
                        manifest.alveoli().get(0),
                        null,
                        (ctx, desc) -> pending,
                        archives.newCache(),
                        null,
                        "dependency-failure")
                .toCompletableFuture();
        assertFalse(execution.isDone());
        pending.complete(null);
        final var failure =
                assertThrows(CompletionException.class, execution::join).getCause();
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("missing-regression-dependency"));
    }

    private Manifest locatedManifest(final Path directory, final String json) {
        final var manifest = mapper.fromString(Manifest.class, json);
        return manifest.withAlveoli(manifest.alveoli().stream()
                .map(alveolus -> alveolus.descriptors() == null
                        ? alveolus
                        : alveolus.withDescriptors(alveolus.descriptors().stream()
                                .map(descriptor -> descriptor.withLocation(directory.toString()))
                                .toList()))
                .toList());
    }

    @Test
    void waitsForLaunchedAwaiterWhenNextAwaiterThrows(@TempDir final Path directory) throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(kubernetes.resolve("first.json"), "{}");
        Files.writeString(kubernetes.resolve("second.json"), "{}");
        final var manifest = locatedManifest(directory, """
                {"alveoli":[{"name":"main","descriptors":[
                  {"name":"first.json","interpolate":false},
                  {"name":"second.json","interpolate":false}]}]}
                """);
        final var pending = new CompletableFuture<Void>();
        final var original = new IllegalArgumentException("awaiter failure");
        final var execution = handler.executeOnAlveolus(
                        null,
                        manifest,
                        manifest.alveoli().get(0),
                        null,
                        (ctx, desc) -> completedFuture(null),
                        archives.newCache(),
                        desc -> {
                            if ("first.json".equals(desc.getConfiguration().name())) {
                                return pending;
                            }
                            throw original;
                        },
                        "awaiter-failure")
                .toCompletableFuture();
        assertFalse(execution.isDone());
        pending.complete(null);
        final var failure =
                assertThrows(CompletionException.class, execution::join).getCause();
        assertEquals(1, failure.getSuppressed().length);
        assertSame(original, failure.getSuppressed()[0]);
    }

    @Test
    void doesNotLaunchLaterRankAfterEarlierRankFailure(@TempDir final Path directory) throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(kubernetes.resolve("first.json"), "{}");
        Files.writeString(kubernetes.resolve("second.json"), "{}");
        final var manifest = locatedManifest(directory, """
                {"alveoli":[{"name":"main","descriptors":[
                  {"name":"first.json","interpolate":false,"await":true},
                  {"name":"second.json","interpolate":false}]}]}
                """);
        final var pending = new CompletableFuture<Void>();
        final var launched = new ArrayList<String>();
        final var original = new IllegalArgumentException("first rank failure");
        final var execution = handler.executeOnAlveolus(
                        null,
                        manifest,
                        manifest.alveoli().get(0),
                        null,
                        (ctx, desc) -> {
                            launched.add(desc.getConfiguration().name());
                            return pending;
                        },
                        archives.newCache(),
                        null,
                        "rank-failure")
                .toCompletableFuture();
        assertFalse(execution.isDone());
        assertEquals(List.of("first.json"), launched);
        pending.completeExceptionally(original);
        final var failure =
                assertThrows(CompletionException.class, execution::join).getCause();
        assertEquals(1, failure.getSuppressed().length);
        assertSame(original, failure.getSuppressed()[0]);
        assertEquals(List.of("first.json"), launched);
    }

    @Test
    void interpolatesAndAppliesGenericPatch(@TempDir final Path directory) throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(kubernetes.resolve("test.json"), """
                {"metadata":{"name":"{{name}}"},"value":"before","count":1}
                """);
        final var manifest = mapper.fromString(Manifest.class, """
                {"alveoli":[{
                  "name":"main",
                  "placeholders":{"name":"app","replacement":"after"},
                  "descriptors":[{"name":"test.json","interpolate":true}],
                  "patches":[{"descriptorName":"test.json","interpolate":true,"patch":[
                    {"op":"replace","path":"/value","value":"{{replacement}}"},
                    {"op":"replace","path":"/count","value":3},
                    {"op":"add","path":"/items","value":["value",true,null]},
                    {"op":"add","path":"/nullable","value":null}
                  ]}]
                }]}
                """);
        final var rootAlveolus = manifest.alveoli().get(0);
        final var alveolus = rootAlveolus.withDescriptors(rootAlveolus.descriptors().stream()
                .map(descriptor -> descriptor.withLocation(directory.toString()))
                .collect(Collectors.toList()));
        final var result = new AtomicReference<Object>();
        handler.executeOnAlveolus(
                        "Test",
                        manifest,
                        alveolus,
                        null,
                        (context, descriptor) -> {
                            result.set(mapper.fromString(Object.class, descriptor.getContent()));
                            return completedFuture(null);
                        },
                        archives.newCache(),
                        null,
                        "test")
                .toCompletableFuture()
                .join();
        assertEquals(mapper.fromString(Object.class, """
                {"metadata":{"name":"app"},"value":"after","count":3,"items":["value",true,null],"nullable":null}
                """), result.get());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "42",
                "\"invalid\"",
                "{}",
                "{\"op\":42,\"path\":\"/value\"}",
                "{\"op\":\"replace\",\"path\":42}",
                "{\"op\":\"copy\",\"path\":\"/value\"}"
            })
    void rejectsMalformedPatchOperations(final String operation, @TempDir final Path directory) throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(kubernetes.resolve("test.json"), "{\"value\":\"before\"}");
        final var manifest = mapper.fromString(
                Manifest.class,
                "{\"alveoli\":[{\"name\":\"main\",\"descriptors\":[{\"name\":\"test.json\"}],"
                        + "\"patches\":[{\"descriptorName\":\"test.json\",\"patch\":[" + operation + "]}]}]}");
        final var root = manifest.alveoli().get(0);
        final var alveolus = root.withDescriptors(root.descriptors().stream()
                .map(descriptor -> descriptor.withLocation(directory.toString()))
                .collect(Collectors.toList()));
        final var failure = assertThrows(
                RuntimeException.class,
                () -> handler.executeOnAlveolus(
                                "Test",
                                manifest,
                                alveolus,
                                null,
                                (context, descriptor) -> {
                                    throw new AssertionError("Malformed patch must not be applied");
                                },
                                archives.newCache(),
                                null,
                                "test")
                        .toCompletableFuture()
                        .join());
        Throwable cause = failure;
        while (cause.getCause() != null || cause.getSuppressed().length > 0) {
            cause = cause.getCause() != null ? cause.getCause() : cause.getSuppressed()[0];
        }
        assertInstanceOf(IllegalArgumentException.class, cause);
    }
}
