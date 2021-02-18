/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import lombok.Data;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class HelpCommand implements Executable {
    @Any
    @Inject
    private Instance<Executable> executables;

    @Inject
    private BeanManager beanManager;

    @Inject
    @Description("Which command to show help for. If not set it will show all commands.")
    @ConfigProperty(name = "bundlebee.help.command", defaultValue = UNSET)
    private String command;

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Print help for all available commands.";
    }

    @Override
    public CompletionStage<?> execute() {
        log.info("\n" +
                "BundleBee\n" +
                "\n" +
                "  BundleBee is a light Kubernetes package manager. Available commands:\n" +
                "\n" +
                stream(executables)
                        .filter(it -> UNSET.equals(command) || command.equals(it.name()))
                        .map(executable -> {
                            final var parameters = findParameters(executable).collect(toList());
                            final var description = executable.description();
                            final int end = description.indexOf("\n//");
                            final var desc = reflowText(end > 0 ? description.substring(0, end) : description, "        ") +
                                    (parameters.isEmpty() ? "" : "\n");
                            return "" +
                                    "  [] " + executable.name() + ": " +
                                    Character.toLowerCase(desc.charAt(0)) + desc.substring(1) + "\n" +
                                    parameters.stream().map(p -> "" +
                                            "    " + p.getName() +
                                            (p.getDefaultValue() != null ? " (default: " + p.getDefaultValue()
                                                    .replace("\n", "\\\\n") + ")" : "") + ": " +
                                            reflowText(p.getDescription(), "          "))
                                            .sorted()
                                            .collect(joining("\n"));
                        })
                        .sorted()
                        .collect(joining("\n\n", "", "\n")));
        return completedFuture(null);
    }

    // functionally we want executables.stream() but maven 3.6 requires this workaround for our maven plugin
    private Stream<Executable> stream(final Instance<Executable> executables) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(executables.iterator(), Spliterator.IMMUTABLE), false);
    }

    // not perfect impl but sufficient for now
    private String reflowText(final String content, final String prefix) {
        final var lines = content.split("\n");
        final var builder = new StringBuilder();
        int currentCount = builder.length();
        for (final String s : lines) {
            final var line = s.trim();
            if (line.isBlank()) {
                builder.append("\n\n").append(prefix);
                currentCount = 0;
                continue;
            } else if (line.startsWith("* ")) {
                builder.append("\n").append(prefix);
                currentCount = 0;
            }
            final var words = line.split(" ");
            for (int w = 0; w < words.length; w++) {
                final var word = words[w];
                if (currentCount + word.length() + prefix.length() < 80) {
                    if (currentCount > 0) {
                        builder.append(" ");
                    }
                    builder.append(word);
                    currentCount += 1 + word.length();
                } else {
                    builder.append("\n").append(prefix).append(word);
                    currentCount = word.length();
                }
            }
        }
        return builder.toString();
    }

    private Stream<Parameter> findParameters(final Executable executable) {
        final var prefix = Pattern.compile("^bundlebee\\." + executable.name() + "\\."); // see io.yupiik.bundlebee.core.BundleBee.toProperties
        return beanManager.createInjectionTarget(beanManager.createAnnotatedType(executable.getClass())).getInjectionPoints().stream()
                .filter(it -> it.getAnnotated().isAnnotationPresent(ConfigProperty.class) && it.getAnnotated().isAnnotationPresent(Description.class))
                .map(it -> {
                    final var annotated = it.getAnnotated();
                    final var annotation = annotated.getAnnotation(ConfigProperty.class);
                    var name = annotation.name();
                    if (name.isEmpty()) {
                        name = it.getMember().getDeclaringClass().getName() + '.' + it.getMember().getName();
                    }
                    final var desc = annotated.getAnnotation(Description.class).value();
                    return new Parameter(
                            "--" + prefix.matcher(name).replaceFirst(""),
                            ConfigProperty.UNCONFIGURED_VALUE.equals(annotation.defaultValue()) ? null : annotation.defaultValue(),
                            Character.toLowerCase(desc.charAt(0)) + desc.substring(1));
                });
    }

    @Data
    private static class Parameter {
        private final String name;
        private final String defaultValue;
        private final String description;
    }
}
