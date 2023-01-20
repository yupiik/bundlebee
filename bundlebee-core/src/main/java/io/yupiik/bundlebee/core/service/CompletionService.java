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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.lang.ConfigHolder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
public class CompletionService {
    @Any
    @Inject
    private Instance<ConfigHolder> configHolders;

    @Any
    @Inject
    private Instance<Executable> executables;

    @Inject
    private ParameterExtractor parameterExtractor;

    @Getter
    private List<ParameterExtractor.Parameter> shared;

    @Getter
    private Map<String, Command> commands;

    @PostConstruct
    private void init() {
        shared = stream(configHolders)
                .map(ConfigHolder::getClass)
                .map(c -> c.getName().contains("$$") ? c.getSuperclass() : c) // unwrap proxy
                .flatMap(it -> parameterExtractor.toParameters(it, n -> n))
                .collect(toList());
        commands = stream(executables).collect(toMap(Executable::name, executable -> {
            final var pattern = Pattern.compile("^bundlebee\\." + executable.name() + "\\.");
            final var commandParameters = parameterExtractor.toParameters(executable.getClass(), s -> pattern.matcher(s).replaceFirst("")).collect(toList());
            if (noSharedConfigCommandNames().anyMatch(n -> n.equals(executable.name()))) {
                return new Command(executable, executable.completer(), commandParameters::stream);
            }
            return new Command(executable, executable.completer(), () -> Stream.concat(shared.stream(), commandParameters.stream()));
        }));
    }

    private Stream<String> noSharedConfigCommandNames() {
        return Stream.of("add-alveolus", "build", "cipher-password", "create-master-password", "new", "version");
    }

    private <T> Stream<T> stream(final Instance<T> instance) { // mvn 3.6 workaround
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(instance.iterator(), Spliterator.IMMUTABLE), false);
    }

    @RequiredArgsConstructor
    public static class Command implements Supplier<Stream<ParameterExtractor.Parameter>> {
        @Getter
        private final Executable executable;

        @Getter
        private final Executable.Completer completer;

        @Delegate
        private final Supplier<Stream<ParameterExtractor.Parameter>> parameters;
    }
}
