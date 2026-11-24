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
package io.yupiik.bundlebee.operator;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.bundlebee.core.command.impl.ApplyCommand;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.lang.SubstitutorProducer;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.bundlebee.operator.handler.CommandRunner;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.kubernetes.operator.base.impl.FusionGeneratedModule;
import io.yupiik.kubernetes.operator.base.impl.Threads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandRunnerTest {
    @Test
    void preparationFailureKeepsRealSharedServicesAndIdStateUntilSiblingSettles(@TempDir final Path directory)
            throws Exception {
        final var kubernetes = Files.createDirectories(directory.resolve("bundlebee/kubernetes"));
        Files.writeString(directory.resolve("bundlebee/manifest.json"), "{}");
        Files.writeString(kubernetes.resolve("first.json"), "{{message}}");
        Files.writeString(kubernetes.resolve("second.json"), "{{bundlebee-base64-decode:%%%}}");
        final var pending = new CompletableFuture<Void>();
        final var entered = new CountDownLatch(1);
        final var closed = new CopyOnWriteArrayList<String>();
        final var executionId = new AtomicReference<String>();
        final var executionStage = new AtomicReference<CompletableFuture<?>>();
        final var closeFailure = new IllegalStateException("close failure");
        try (final var threads = new Threads(Executors.newSingleThreadScheduledExecutor());
                final var container = ConfiguringContainer.of()
                        .disableModule(FusionGeneratedModule.class)
                        .register(new ProvidedInstanceBean<>(DefaultScoped.class, Threads.class, () -> threads))
                        .register(new ProvidedInstanceBean<>(
                                DefaultScoped.class,
                                ConfigurationSource.class,
                                () -> key -> "bundlebee.kube.skipLoadConfig".equals(key) ? "true" : null))
                        .start();
                final var producer = container.lookup(SubstitutorProducer.class);
                final var substitutor = container.lookup(Substitutor.class);
                final var handler = container.lookup(AlveolusHandler.class);
                final var archives = container.lookup(ArchiveReader.class);
                final var mapper = container.lookup(JsonMapper.class)) {
            final var manifest = mapper.instance().fromString(Manifest.class, """
                    {"alveoli":[{"name":"main","descriptors":[
                      {"name":"first.json","interpolate":true},
                      {"name":"second.json","interpolate":true}]}]}
                    """);
            final var root = manifest.alveoli().get(0);
            final var located = root.withDescriptors(root.descriptors().stream()
                    .map(desc -> desc.withLocation(directory.toString()))
                    .toList());
            final var runner = runner(producer.instance(), (configuration, dependents) -> new Instance<ApplyCommand>() {
                @Override
                public FusionBean<ApplyCommand> bean() {
                    return null;
                }

                @Override
                public ApplyCommand instance() {
                    return new ApplyCommand(
                            new ApplyCommand.Options(
                                    "test", "manifest", "from", false, false, false, false, false, false, 0, "", ""),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null) {
                        @Override
                        public CompletionStage<?> execute(final String id) {
                            executionId.set(id);
                            final var stage = handler.instance()
                                    .executeOnAlveolus(
                                            null,
                                            manifest,
                                            located,
                                            null,
                                            (ctx, desc) -> {
                                                assertEquals("invocation", desc.getContent());
                                                return pending.thenRun(() -> {
                                                    assertTrue(closed.isEmpty());
                                                    assertEquals(
                                                            "invocation",
                                                            substitutor
                                                                    .instance()
                                                                    .replace("{{message}}", id));
                                                    assertNotNull(
                                                            archives.instance().newCache());
                                                });
                                            },
                                            archives.instance().newCache(),
                                            null,
                                            id)
                                    .toCompletableFuture();
                            executionStage.set(stage);
                            entered.countDown();
                            return stage;
                        }
                    };
                }

                @Override
                public void close() {
                    assertTrue(executionStage.get().isDone());
                    assertEquals("invocation", substitutor.instance().replace("{{message}}", executionId.get()));
                    closed.add("command");
                    throw closeFailure;
                }
            });
            final var executor = Executors.newSingleThreadExecutor();
            try {
                final var invocation = executor.submit(() -> {
                    try {
                        runner.run(new String[] {"apply", "--message", "invocation"});
                    } finally {
                        entered.countDown();
                    }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (executionStage.get() == null) {
                    invocation.get(5, TimeUnit.SECONDS);
                    fail("Command did not reach traversal");
                }
                assertFalse(executionStage.get().isDone());
                assertFalse(invocation.isDone());
                assertTrue(closed.isEmpty());
                assertEquals("invocation", substitutor.instance().replace("{{message}}", executionId.get()));
                pending.complete(null);
                final var failure = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS));
                final var commandFailure = assertInstanceOf(CompletionException.class, failure.getCause());
                assertEquals(List.of(closeFailure), List.of(commandFailure.getSuppressed()));
                assertInstanceOf(
                        IllegalArgumentException.class,
                        commandFailure.getCause().getSuppressed()[0]);
                assertEquals(List.of("command"), closed);
                assertEquals("cleared", substitutor.instance().replace("{{message:-cleared}}", executionId.get()));
                assertNotNull(
                        archives.instance().newCache(), "Shared service must still be usable after invocation cleanup");
            } finally {
                pending.complete(null);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void joinsBeforeClosingLookupAndClearsPlaceholdersAfterAsyncFailure() throws Exception {
        final var state = new InvocationState();
        final var completion = new CompletableFuture<Void>();
        final var entered = new CountDownLatch(1);
        final var closes = new CopyOnWriteArrayList<String>();
        final var runner = runner(state, (configuration, dependents) -> new Instance<ApplyCommand>() {
            @Override
            public FusionBean<ApplyCommand> bean() {
                return null;
            }

            @Override
            public ApplyCommand instance() {
                return new ApplyCommand(
                        new ApplyCommand.Options(
                                "test", "manifest", "from", false, false, false, false, false, false, 0, "", ""),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null) {
                    @Override
                    public CompletionStage<?> execute(final String executionId) {
                        assertEquals(Map.of("recipe.message", "one"), state.active.get(executionId));
                        entered.countDown();
                        return completion;
                    }
                };
            }

            @Override
            public void close() {
                assertTrue(completion.isDone(), "Lookup must remain open until the command completes");
                assertEquals(1, state.active.size(), "Placeholders must outlive lookup cleanup");
                closes.add("command");
            }
        });
        final var executor = Executors.newSingleThreadExecutor();
        try {
            final var invocation = executor.submit(() -> runner.run(new String[] {"apply", "--recipe.message", "one"}));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(invocation.isDone());
            assertTrue(closes.isEmpty());
            completion.completeExceptionally(new IllegalStateException("async failure"));
            final var failure = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS));
            assertEquals("async failure", failure.getCause().getCause().getMessage());
            assertEquals(List.of("command"), closes);
            assertTrue(state.active.isEmpty());
            assertEquals(1, state.cleared.size());
        } finally {
            completion.complete(null);
            executor.shutdownNow();
        }
    }

    @Test
    void constructionFailureClosesDependentsInReverseOrderAndClearsPlaceholders() {
        final var state = new InvocationState();
        final var closed = new CopyOnWriteArrayList<String>();
        final var failure = new IllegalArgumentException("creation failure");
        final var runner = runner(state, (configuration, dependents) -> {
            dependents.add(dependent("first", closed, false));
            dependents.add(dependent("second", closed, true));
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> runner.run(new String[] {"apply"})));
        assertEquals(List.of("second", "first"), closed);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(state.active.isEmpty());
        assertEquals(1, state.cleared.size());
    }

    private static Instance<Runnable> dependent(final String name, final List<String> closed, final boolean fail) {
        return new Instance<>() {
            @Override
            public FusionBean<Runnable> bean() {
                return null;
            }

            @Override
            public Runnable instance() {
                return () -> {};
            }

            @Override
            public void close() {
                closed.add(name);
                if (fail) {
                    throw new IllegalStateException("close failure");
                }
            }
        };
    }

    private static CommandRunner runner(
            final SubstitutorProducer state,
            final BiFunction<Configuration, List<Instance<?>>, Instance<ApplyCommand>> factory) {
        final var command = new CliCommand<ApplyCommand>() {
            @Override
            public String name() {
                return "apply";
            }

            @Override
            public String description() {
                return "test apply";
            }

            @Override
            public List<Parameter> parameters() {
                return List.of();
            }

            @Override
            public Instance<ApplyCommand> create(
                    final Configuration configuration, final List<Instance<?>> dependents) {
                return factory.apply(configuration, dependents);
            }
        };
        return new CommandRunner(new ParameterExtractor(List.of(command), null), state, key -> Optional.empty());
    }

    private static class InvocationState extends SubstitutorProducer {
        private final Map<String, Map<String, String>> active = new ConcurrentHashMap<>();
        private final List<String> cleared = new CopyOnWriteArrayList<>();

        @Override
        public void setInvocationPlaceholders(final String id, final Map<String, String> placeholders) {
            assertNull(active.putIfAbsent(id, Map.copyOf(placeholders)));
        }

        @Override
        public void clearInvocationPlaceholders(final String id) {
            assertNotNull(active.remove(id));
            cleared.add(id);
        }
    }
}
