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

import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Option discovery only creates generated CLI descriptors. Value completers are created on demand.
 */
@ApplicationScoped
public class CompletionService {
    private final ParameterExtractor parameters;
    private final Configuration configuration;

    public CompletionService(final ParameterExtractor parameters, final Configuration configuration) {
        this.parameters = parameters;
        this.configuration = configuration;
    }

    public List<ParameterExtractor.Parameter> getShared() {
        return parameters
                .sharedParameters()
                .map(parameter -> new ParameterExtractor.Parameter(
                        "--" + parameter.configName(), parameters.defaultValue(parameter), parameter.description()))
                .toList();
    }

    public Map<String, Command> getCommands() {
        final var shared = getShared();
        return parameters.commands().stream().collect(toMap(CliCommand::name, command -> {
            final var own = parameters.toParameters(command).toList();
            final BiFunction<Map<String, String>, String, Stream<String>> completer = (options, key) -> {
                try (final var instance = command.create(configuration, new ArrayList<>())) {
                    if (instance.instance() instanceof CompletingExecutable executable) {
                        try (final var proposals = executable.complete(options, key)) {
                            return proposals.toList().stream();
                        }
                    }
                    return Stream.empty();
                }
            };
            if (List.of(
                            "add-alveolus",
                            "build",
                            "cipher-password",
                            "create-master-password",
                            "new",
                            "version",
                            "cipher",
                            "decipher",
                            "list-alveoli",
                            "list-lint-rules")
                    .contains(command.name())) {
                return new Command(command, completer, own::stream);
            }
            return new Command(
                    command,
                    completer,
                    () -> Stream.concat(shared.stream(), own.stream()).distinct());
        }));
    }

    public static class Command implements Supplier<Stream<ParameterExtractor.Parameter>> {
        private final CliCommand<? extends Runnable> executable;
        private final BiFunction<Map<String, String>, String, Stream<String>> completer;
        private final Supplier<Stream<ParameterExtractor.Parameter>> parameters;

        @Override
        public Stream<ParameterExtractor.Parameter> get() {
            return parameters.get();
        }

        public CliCommand<? extends Runnable> getExecutable() {
            return this.executable;
        }

        public Stream<String> complete(final Map<String, String> options, final String key) {
            return completer.apply(options, key);
        }

        public Supplier<Stream<ParameterExtractor.Parameter>> getParameters() {
            return this.parameters;
        }

        private Command(
                final CliCommand<? extends Runnable> executable,
                final BiFunction<Map<String, String>, String, Stream<String>> completer,
                final Supplier<Stream<ParameterExtractor.Parameter>> parameters) {
            this.executable = executable;
            this.completer = completer;
            this.parameters = parameters;
        }
    }
}
