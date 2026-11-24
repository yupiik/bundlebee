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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.fusion.cli.internal.BaseCliCommand;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CompletionServiceTest {
    @Test
    void discoversLazilyAndDetachesValuesBeforeClosingTheLookup() {
        assertCompletionScope(false);
    }

    @Test
    void closesTheStreamAndLookupWhenLazyCompletionFails() {
        assertCompletionScope(true);
    }

    @Test
    void plainRunnableAndExecutableHaveNoValueProposalsAndAreNotRun() {
        final var constructions = new AtomicInteger();
        final var runnable = descriptor("runnable", () -> {
            constructions.incrementAndGet();
            return () -> {
                throw new AssertionError("Completion must not execute commands");
            };
        });
        final var executable = descriptor("executable", () -> {
            constructions.incrementAndGet();
            return new PlainExecutable();
        });
        final var commands = service(List.of(runnable, executable)).getCommands();
        assertEquals(0, constructions.get());
        assertTrue(commands.get("runnable").get().toList().isEmpty());
        assertTrue(commands.get("executable").get().toList().isEmpty());
        assertEquals(0, constructions.get());
        assertTrue(commands.get("runnable").complete(Map.of(), "value").toList().isEmpty());
        assertTrue(
                commands.get("executable").complete(Map.of(), "value").toList().isEmpty());
        assertEquals(2, constructions.get());
    }

    private void assertCompletionScope(final boolean fail) {
        final var events = new ArrayList<String>();
        final var failure = new IllegalStateException("Completion failed");
        final var bean =
                new ProvidedInstanceBean<Probe>(DefaultScoped.class, Probe.class, () -> {
                    events.add("create");
                    return new Probe(events, fail ? failure : null);
                }) {
                    @Override
                    public void destroy(final RuntimeContainer container, final Probe instance) {
                        events.add("destroy");
                    }
                };
        try (final var container = ConfiguringContainer.of().register(bean).start()) {
            final CliCommand<? extends Runnable> descriptor = new BaseCliCommand<Configuration, Runnable>(
                    "probe",
                    "Completion probe.",
                    configuration -> configuration,
                    (configuration, dependencies) -> {
                        final var lookup = container.lookup(Probe.class);
                        dependencies.add(lookup);
                        return lookup.instance();
                    },
                    List.of());
            final var command = service(List.of(descriptor)).getCommands().get("probe");
            assertTrue(command.get().toList().isEmpty());
            assertTrue(events.isEmpty(), "Descriptor discovery must not create a command");
            if (fail) {
                assertSame(
                        failure,
                        assertThrows(
                                IllegalStateException.class,
                                () -> command.complete(Map.of("other", "configured"), "value")));
            } else {
                try (final var proposals = command.complete(Map.of("other", "configured"), "value")) {
                    assertEquals(List.of("create", "consume", "stream-close", "destroy"), events);
                    assertEquals(List.of("configured:value"), proposals.toList());
                }
            }
            assertEquals(List.of("create", "consume", "stream-close", "destroy"), events);
        }
    }

    private CompletionService service(final List<CliCommand<? extends Runnable>> descriptors) {
        return new CompletionService(new ParameterExtractor(descriptors, null), key -> Optional.empty());
    }

    private CliCommand<? extends Runnable> descriptor(final String name, final Supplier<Runnable> factory) {
        return new BaseCliCommand<Configuration, Runnable>(
                name,
                "Plain command.",
                configuration -> configuration,
                (configuration, dependencies) -> factory.get(),
                List.of());
    }

    private static class PlainExecutable implements Executable {
        @Override
        public String name() {
            return "plain";
        }

        @Override
        public String description() {
            return "Plain command.";
        }

        @Override
        public CompletionStage<?> execute() {
            throw new AssertionError("Completion must not execute commands");
        }

        // A similarly named method does not opt a plain executable into completion.
        public Stream<String> complete(final Map<String, String> options, final String key) {
            throw new AssertionError("Only CompletingExecutable supplies values");
        }
    }

    private static final class Probe extends PlainExecutable implements CompletingExecutable {
        private final List<String> events;
        private final RuntimeException failure;

        private Probe(final List<String> events, final RuntimeException failure) {
            this.events = events;
            this.failure = failure;
        }

        @Override
        public Stream<String> complete(final Map<String, String> options, final String key) {
            return Stream.of(options.get("other") + ':' + key)
                    .map(value -> {
                        assertEquals(List.of("create"), events, "Lookup must remain open while consuming proposals");
                        events.add("consume");
                        if (failure != null) {
                            throw failure;
                        }
                        return value;
                    })
                    .onClose(() -> {
                        assertEquals(List.of("create", "consume"), events, "Stream must close before the lookup");
                        events.add("stream-close");
                    });
        }
    }
}
