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

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.configuration.GlobalConfiguration;
import io.yupiik.bundlebee.core.configuration.HelpConfiguration;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@DefaultScoped
@Command(name = "help", description = "print help for all available commands.")
public class HelpCommand implements CompletingExecutable {
    private static final Pattern NEWLINE = Pattern.compile("\n");
    private static final Pattern SPACE = Pattern.compile(" ");
    private final Logger log = Logger.getLogger(HelpCommand.class.getName());

    public HelpCommand(final GlobalConfiguration options, final ParameterExtractor parameterExtractor) {
        this.options = options.help();
        this.parameterExtractor = parameterExtractor;
    }

    private final HelpConfiguration options;
    private final ParameterExtractor parameterExtractor;

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "print help for all available commands.";
    }

    @Override
    public Stream<String> complete(final Map<String, String> config, final String optionName) {
        return switch (optionName) {
            case "command" ->
                parameterExtractor.commands().stream().map(io.yupiik.fusion.cli.internal.CliCommand::name);
            case "shared", "args" -> Stream.of("true", "false");
            default -> Stream.empty();
        };
    }

    @Override
    public CompletionStage<?> execute() {
        final var showAll = UNSET.equals(options.command());
        final var shared = options.showSharedOptions()
                ? " Shared Options:\n\n"
                        + toString(parameterExtractor
                                .sharedParameters()
                                .map(parameter -> new ParameterExtractor.Parameter(
                                        "--" + parameter.configName(),
                                        parameterExtractor.defaultValue(parameter),
                                        parameter.description()))
                                .toList())
                        + "\n\n"
                : "";
        log.info("\nBundleBee\n\n" + (showAll ? "  BundleBee is a light Kubernetes package manager.\n\n" : "")
                + shared + (showAll ? " Available commands:\n\n" : "")
                + parameterExtractor.commands().stream()
                        .filter(command -> showAll || options.command().equals(command.name()))
                        .filter(command -> !"true".equals(command.metadata().get("bundlebee.hidden")))
                        .map(this::commandHelp)
                        .sorted()
                        .collect(joining(options.showArgs() ? "\n\n" : "\n", "", "\n")));
        return completedFuture(null);
    }

    private String commandHelp(final CliCommand<?> command) {
        final var description = command.description() == null ? "" : command.description();
        final var end = description.indexOf("\n//");
        final var summary = reflowText(end > 0 ? description.substring(0, end) : description, "        ");
        final var parameters = command.parameters().stream()
                .filter(parameter ->
                        !"help".equals(command.name()) || parameter.configName().startsWith("bundlebee.help."))
                .map(parameter -> parameterExtractor.toParameter(command, parameter))
                .toList();
        return "  [] " + command.name() + ": " + lowerFirst(summary)
                + (options.showArgs() ? (parameters.isEmpty() ? "" : "\n") + "\n" + toString(parameters) : "");
    }

    // not perfect impl but sufficient for now
    private String reflowText(final String content, final String prefix) {
        final var lines = NEWLINE.split(content);
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
            final var words = SPACE.split(line);
            for (final String word : words) {
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

    private String toString(final List<ParameterExtractor.Parameter> parameters) {
        return parameters.stream()
                .map(p -> "    " + p.getName()
                        + (p.getDefaultValue() != null
                                ? " (default: " + p.getDefaultValue().replace("\n", "\\\\n") + ")"
                                : "")
                        + ": " + reflowText(lowerFirst(p.getDescription()), "          "))
                .sorted()
                .collect(joining("\n"));
    }

    private String lowerFirst(final String value) {
        return value == null || value.isEmpty() ? "" : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
