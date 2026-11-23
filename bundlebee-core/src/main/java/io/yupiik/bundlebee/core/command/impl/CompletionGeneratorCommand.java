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
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.service.CompletionService;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "completion",
        description =
                "Execute bash completion. " + "Should be setup in your ~/.bashrc or ~/.profile file to be active.\n"
                        + "// end of short description\n"
                        + "The result will be the list of proposal you can inject into COMPREPLY array.\n" + "\n"
                        + "To set it up you can add this snippet to your .bashrc "
                        + "(assumes you uses the `bundlebee` linux binary and it is in your `$PATH` otherwise replace bundlebee by your own launching script):\n"
                        + "\n" + "[source,bash]\n" + "----\n"
                        + "complete -o default -o nospace -C \"bundlebee completion\" bundlebee\n" + "----\n")
public class CompletionGeneratorCommand implements Executable {
    private final Logger log = Logger.getLogger(CompletionGeneratorCommand.class.getName());

    public CompletionGeneratorCommand(
            final Options options, CompletionService completionService, Substitutor substitutor) {
        this.completionService = completionService;
        this.substitutor = substitutor;
        this.useLogger = options.useLogger();
        this.compLine = options.compLine();
        this.compPoint = options.compPoint();
    }

    private final CompletionService completionService;
    private final Substitutor substitutor;
    private final boolean useLogger;
    private final String compLine;
    private final int compPoint;

    @Override
    public String name() {
        return "completion";
    }

    @Override
    public String description() {
        return "Execute bash completion. " + "Should be setup in your ~/.bashrc or ~/.profile file to be active.\n"
                + "// end of short description\n"
                + "The result will be the list of proposal you can inject into COMPREPLY array.\n" + "\n"
                + "To set it up you can add this snippet to your .bashrc "
                + "(assumes you uses the `bundlebee` linux binary and it is in your `$PATH` otherwise replace bundlebee by your own launching script):\n"
                + "\n" + "[source,bash]\n" + "----\n"
                + "complete -o default -o nospace -C \"bundlebee completion\" bundlebee\n" + "----\n";
    }

    @Override
    public CompletionStage<?> execute() {
        final Consumer<String> log = useLogger ? this.log::info : System.out::println;
        final var proposals = complete(parseLine(compLine)).collect(joining("\n"));
        log.accept(proposals);
        return completedFuture(true);
    }

    private Stream<String> complete(final List<Arg> args) {
        final Optional<Arg> completed = args.stream()
                .filter(a -> compPoint > a.from && compPoint <= a.to)
                .findFirst();
        final int idx;
        final String current;
        if (completed.isPresent()) {
            final var arg = completed.orElseThrow();
            current = arg.value.substring(0, compPoint - 1 - arg.from);
            idx = args.indexOf(arg);
        } else {
            final int end = compLine.indexOf(' ', compPoint);
            current = compLine.substring(
                            compLine.lastIndexOf(' ', compPoint - 1) + 1, end < 0 ? compLine.length() : end)
                    .trim();
            idx = args.size();
        }
        switch (idx) {
            case // just to be complete but should never be triggered
            0:
                return Stream.of("bundlebee").filter(it -> it.startsWith(current));
            case 1:
                return completionService.getCommands().keySet().stream()
                        .filter(it -> it.startsWith(current))
                        .sorted();
            default:
                if ((idx % 2) == 0) {
                    // key (--xxx) - absolutely we should do "-2" for binary and command but "(x-2) % 2 == x % 2"
                    final var cmd = completionService.getCommands().get(args.get(1).value);
                    if (cmd == null) {
                        return Stream.of();
                    }
                    final var alreadySet = args.stream()
                            .map(a -> a.value)
                            .flatMap(this::toPossibleKeys)
                            .collect(toSet());
                    final var parameters = cmd.get().collect(toList());
                    return parameters.stream()
                            .map(ParameterExtractor.Parameter::getName)
                            .filter(it -> it.startsWith(current))
                            .filter(it -> !alreadySet.contains(it))
                            .sorted((a1, a2) -> {
                                // shared last
                                if (a1.equals(a2)) {
                                    return 0;
                                }
                                if (a1.contains(".") && a2.contains(".")) {
                                    return a1.compareTo(a2);
                                }
                                if (a1.contains(".")) {
                                    return 1;
                                }
                                if (a2.contains(".")) {
                                    return -1;
                                }
                                return a1.compareTo(a2);
                            });
                }
                // note that returning an empty list will enable bash default completion if complete is used with -o
                // default
                final var cmd =
                        completionService.getCommands().get(args.get(1).value.trim());
                if (cmd == null) {
                    return Stream.of();
                }
                return toPossibleKeys(args.get(idx - 1).value)
                        .findAny()
                        .map(it -> toKey("bundlebee." + cmd.getExecutable().name() + '.', it))
                        .map(argName -> {
                            if (isGlobalOption(argName)) {
                                return completeSharedOption(argName);
                            }
                            final var map = toOptionMap(
                                    "bundlebee." + cmd.getExecutable().name() + '.', args);
                            return cmd.complete(map, argName);
                        })
                        .map(options -> options.filter(it -> it.startsWith(current)))
                        .orElseGet(Stream::empty);
        }
    }

    private Map<String, String> toOptionMap(final String prefix, final List<Arg> args) {
        final var out = new HashMap<String, String>();
        // don't use a loop since command can be not yet valid
        final var it = args.iterator();
        // bundlebee
        it.next();
        // command
        it.next();
        while (it.hasNext()) {
            final var option = it.next();
            if (option.value.startsWith("--") && it.hasNext()) {
                final var value = it.next();
                out.put(toKey(prefix, option.value), value.value);
            }
        }
        return out;
    }

    private String toKey(final String prefix, final String key) {
        var k = key;
        if (k.startsWith("--")) {
            k = k.substring("--".length());
        }
        k = k.replace('-', '.');
        if (k.startsWith(prefix)) {
            k = k.substring(prefix.length());
        }
        return k;
    }

    private Stream<String> completeSharedOption(final String argName) {
        switch (argName) {
            case "bundlebee.maven.preferCustomSettingsXml":
            case "bundlebee.maven.forceCustomSettingsXml":
            case "bundlebee.maven.repositories.downloads.enabled":
            case "bundlebee.kube.validateSSL":
            case "bundlebee.kube.verbose":
            case "bundlebee.kube.dryRun":
                return Stream.of("true", "false");
            case "bundlebee.kube.defaultPropagationPolicy":
                return Stream.of("Orphan", "Foreground", "Background");
            case "bundlebee.kube.customMetadataInjectionPoint":
                return Stream.of("labels", "annotations");
            default:
                return Stream.of();
        }
    }

    private boolean isGlobalOption(final String argName) {
        return argName.startsWith("bundlebee.maven.")
                || argName.startsWith("bundlebee.kube.")
                || argName.startsWith("bundlebee.httpclient.");
    }

    private Stream<String> toPossibleKeys(final String value) {
        final var prefix = value.startsWith("--") ? "--" : "";
        final var key = value.substring(prefix.length());
        return Stream.of(value, prefix + key.replace('-', '.'), prefix + key.replace('.', '-'));
    }

    // simple parser since we don't have COMP_WORDS using complete -C
    // (but it is easier to setup than a -F which requires more scripting)
    private List<Arg> parseLine(final String raw) {
        final List<Arg> result = new ArrayList<>();
        int from = 0;
        Character end = null;
        boolean escaped = false;
        final StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            from++;
            if (escaped) {
                escaped = false;
                current.append(c);
            } else if ((end != null && end == c) || (c == ' ' && end == null)) {
                if (current.length() > 0) {
                    result.add(new Arg(current.toString(), from - current.length() - 1, from - (c == ' ' ? 1 : 0)));
                    current.setLength(0);
                }
                end = null;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '\"' || c == '\'') {
                end = c;
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(new Arg(current.toString(), from - current.length() - 1, from));
        }
        return result;
    }

    private static class Arg {
        private final String value;
        private final int from;
        private final int to;

        public Arg(final String value, final int from, final int to) {
            this.value = value;
            this.from = from;
            this.to = to;
        }
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.completion.useLogger",
                    defaultValue = "false",
                    documentation = "If true logger will be used instead of stdout.")
            boolean useLogger,

            @Property(
                    value = "comp.line",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Matches bash COMP_LINE environment variable, represents the current command line.")
            String compLine,

            @Property(
                    value = "comp.point",
                    defaultValue = "-1",
                    documentation =
                            "Matches bash COMP_POINT environment variable, represents the index of the cursor position.")
            int compPoint) {}
}
