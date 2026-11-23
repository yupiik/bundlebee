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
package io.yupiik.bundlebee.core;

import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.lang.SubstitutorProducer;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.cli.CliAwaiter;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.source.EnvironmentSource;
import io.yupiik.fusion.framework.api.container.configuration.source.SystemPropertiesSource;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Synchronous embedding entry point backed by Fusion's generated CLI descriptors. */
public final class BundleBee {
    public static void main(final String... args) {
        new BundleBee().launch(args);
    }

    public void launch(final String... args) {
        launch(args, Map.of());
    }

    /** Overrides are invocation-local, copied, and never installed as JVM properties. */
    public void launch(final String[] args, final Map<String, String> overrides) {
        final var values = overrides.entrySet().stream()
                .flatMap(entry -> Stream.of(entry, Map.entry(entry.getKey().replace('.', '-'), entry.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b));
        launch(args, values::get);
    }

    /** The source remains lazy, notably for Maven expression evaluation in descriptors. */
    public void launch(final String[] args, final ConfigurationSource overrides) {
        launch(args, overrides, Map.of());
    }

    /** Placeholders are copied and exposed only to substitution, never to service configuration. */
    public void launch(
            final String[] args, final ConfigurationSource overrides, final Map<String, String> placeholders) {
        final var invocationPlaceholders = Map.copyOf(placeholders);
        final var arguments = new ArrayList<>(List.of(args));
        final var properties = new HashMap<String, String>();
        final var defaults = new HashMap<String, String>();
        final var home = Path.of(System.getProperty("user.home", "/home/user"), ".bundlebeerc");
        if (Files.exists(home)) {
            load(home, defaults);
        }
        // Legacy bootstrap consumes only the first config-file pair, wherever it occurs.
        final var option = arguments.indexOf("--config-file");
        if (option >= 0) {
            if (option + 1 == arguments.size()) {
                throw new IllegalArgumentException("Option '--config-file' requires a value");
            }
            load(Path.of(arguments.get(option + 1)), properties);
            arguments.remove(option);
            arguments.remove(option);
        } else {
            for (int i = 0; i < arguments.size(); i++) {
                if (arguments.get(i).startsWith("--config-file=")) {
                    load(Path.of(arguments.remove(i).substring("--config-file=".length())), properties);
                    break;
                }
            }
        }
        if (arguments.isEmpty()) {
            arguments.add("help");
        }
        final var command = arguments.get(0);
        final var dispatch = new ArrayList<>(legacyArgs(command, arguments));
        final var argsSource = new ArgsConfigSource(dispatch);
        final var argo = new ArgoSource();
        // Generated CLI lookups use dashed option names: the JVM source wrappers
        // normalize dotted aliases and keep JVM settings ahead of rc defaults.
        try (final var container = ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, Args.class, () -> new Args(dispatch)))
                .register(source(100, aliases(command, argsSource, true)))
                .register(source(200, aliases(command, overrides, false)))
                .register(source(300, aliases(command, argo, false)))
                .register(source(400, aliases(command, Map.copyOf(properties)::get, false)))
                .register(source(500, aliases(command, new SystemPropertiesSource(), false)))
                .register(source(600, aliases(command, new EnvironmentSource(), false)))
                .register(source(700, aliases(command, Map.copyOf(defaults)::get, false)))
                .start()) {
            try (final var configuration = container.lookup(Configuration.class)) {
                configuration.instance().get("ARGOCD_APP_PARAMETERS").ifPresent(value -> {
                    try (final var mapper = container.lookup(JsonMapper.class)) {
                        final List<ArgoParameter> parameters = mapper.instance()
                                .fromString(new Types.ParameterizedTypeImpl(List.class, ArgoParameter.class), value);
                        argo.values = Map.copyOf(parameters.stream()
                                .flatMap(parameter -> Stream.of(
                                        Map.entry(parameter.name(), parameter.string()),
                                        Map.entry(parameter.name().replace('.', '-'), parameter.string())))
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b)));
                    }
                });
            }
            try (final var metadata = container.lookup(ParameterExtractor.class)) {
                if (metadata.instance().commands().stream()
                        .noneMatch(candidate -> candidate.name().equals(command))) {
                    Logger.getLogger(BundleBee.class.getName()).warning("No command found for args: " + arguments);
                    dispatch.set(0, "help");
                }
            }
            try (final var cli = container.lookup(CliAwaiter.class)) {
                if (!invocationPlaceholders.isEmpty()) {
                    try (final var substitution = container.lookup(SubstitutorProducer.class)) {
                        substitution.instance().setInvocationPlaceholders(invocationPlaceholders);
                    }
                }
                cli.instance().await();
            }
        }
    }

    private ProvidedInstanceBean<ConfigurationSource> source(final int priority, final ConfigurationSource source) {
        return new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> source) {
            @Override
            public int priority() {
                return priority;
            }
        };
    }

    /** Compatibility names only; precedence is Fusion's ordered source lookup. */
    private ConfigurationSource aliases(
            final String command, final ConfigurationSource source, final boolean shortOptions) {
        return key -> {
            final var name = key.startsWith("--") ? key.substring(2) : key;
            final var dashed = name.replace('.', '-');
            final var commandPrefix = "bundlebee-" + command + "-";
            final var canonical = dashed.startsWith(commandPrefix)
                    ? "bundlebee." + command + "."
                            + dashed.substring(commandPrefix.length()).replace('-', '.')
                    : dashed.replace('-', '.');
            for (final var candidate : List.of(name, canonical, dashed)) {
                final var value = source.get(candidate);
                if (value != null) {
                    return value;
                }
            }
            if ("bundlebee-kube-kubeconfig".equals(dashed)) {
                return source.get("kubeconfig");
            }
            return shortOptions && dashed.startsWith(commandPrefix)
                    ? source.get(dashed.substring(commandPrefix.length()))
                    : null;
        };
    }

    private static final class ArgoSource implements ConfigurationSource {
        // The decoded map is immutable and installed before command/typed settings lookup.
        private Map<String, String> values = Map.of();

        @Override
        public String get(final String key) {
            return values.get(key);
        }
    }

    /**
     * Adapt legacy pairs to Fusion's equals-form input: values remain literal (even "--foo"),
     * repeated keys replace rather than concatenate, and boolean options still require a value.
     * Fusion remains responsible for configuration key normalization and lookup.
     */
    private List<String> legacyArgs(final String command, final List<String> arguments) {
        final var values = new HashMap<String, String>();
        if ("completion".equals(command)) {
            // Bash completion forwards incomplete/odd command lines. Only these three pairs
            // belong to BundleBee; everything else is intentionally ignored as in legacy Args.
            for (final var key : List.of("comp.point", "comp.line", "useLogger")) {
                var index = arguments.indexOf("--" + key);
                if (index < 0) {
                    index = arguments.indexOf("--" + key.replace('.', '-'));
                }
                if (index < 0 && "useLogger".equals(key)) {
                    index = arguments.indexOf("--bundlebee-completion-useLogger");
                    if (index < 0) {
                        index = arguments.indexOf("--bundlebee.completion.useLogger");
                    }
                }
                if (index >= 0 && index + 1 < arguments.size()) {
                    values.put(
                            "useLogger".equals(key) ? "bundlebee.completion.useLogger" : key, arguments.get(index + 1));
                }
            }
        } else {
            if (arguments.size() % 2 == 0) {
                throw new IllegalArgumentException(
                        "Invalid argument parity, syntax is: <command> --<arg1> <value> --<arg2> <value> ..., got:\n"
                                + String.join(" ", arguments));
            }
            values.putAll(IntStream.range(0, (arguments.size() - 1) / 2)
                    .mapToObj(index -> 1 + index * 2)
                    .collect(toMap(
                            index -> {
                                final var key = arguments.get(index);
                                return (key.startsWith("--") ? key.substring(2) : key).replace('.', '-');
                            },
                            index -> arguments.get(index + 1),
                            (a, b) -> b)));
        }
        return Stream.concat(
                        Stream.of(command),
                        values.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()))
                .toList();
    }

    private void load(final Path path, final Map<String, String> values) {
        final var properties = new Properties();
        try (final var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        values.putAll(properties.stringPropertyNames().stream()
                .flatMap(key -> Stream.of(
                        Map.entry(key, properties.getProperty(key)),
                        Map.entry(key.replace('.', '-'), properties.getProperty(key))))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b)));
    }

    @JsonModel
    public record ArgoParameter(String name, String string) {}
}
