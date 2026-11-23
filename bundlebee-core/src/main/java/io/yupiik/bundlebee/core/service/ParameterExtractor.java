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

import static java.util.stream.Collectors.joining;

import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Presentation adapter for Fusion CLI's generated parameters; never inspects command classes.
 */
@ApplicationScoped
public class ParameterExtractor {
    // Injection retains descriptor lookup handles for this application-scoped bean's lifetime.
    private final List<CliCommand<? extends Runnable>> definitions;
    private final JsonMapper mapper;

    public ParameterExtractor(final List<CliCommand<? extends Runnable>> definitions, final JsonMapper mapper) {
        this.mapper = mapper;
        this.definitions = definitions;
    }

    public List<CliCommand<? extends Runnable>> commands() {
        return definitions;
    }

    public Stream<CliCommand.Parameter> sharedParameters() {
        return commands().stream()
                .filter(command -> "help".equals(command.name()))
                .flatMap(command -> command.parameters().stream())
                .filter(parameter -> !parameter.configName().startsWith("bundlebee.help."));
    }

    public Stream<Parameter> toParameters(final CliCommand<?> command) {
        return command.parameters().stream().map(parameter -> toParameter(command, parameter));
    }

    public Parameter toParameter(final CliCommand<?> command, final CliCommand.Parameter parameter) {
        final var prefix = "--bundlebee-" + command.name() + "-";
        final var name = parameter.cliName().startsWith(prefix)
                ? "--" + parameter.cliName().substring(prefix.length())
                : parameter.configName().startsWith("bundlebee.")
                                || List.of("comp.line", "comp.point").contains(parameter.configName())
                        ? "--" + parameter.configName()
                        : parameter.cliName();
        return new Parameter(name, defaultValue(parameter), parameter.description());
    }

    /**
     * Fusion exports Java default expressions, not MicroProfile raw default strings.
     */
    public String defaultValue(final CliCommand.Parameter parameter) {
        final var expression = parameter.defaultValue();
        if (expression == null || expression.isEmpty() || "null".equals(expression)) {
            return null;
        }
        if (expression.startsWith("\"")) {
            return mapper.fromString(String.class, expression);
        }
        if (expression.startsWith("Boolean.parseBoolean(")) {
            return mapper.fromString(
                    String.class, expression.substring("Boolean.parseBoolean(".length(), expression.length() - 1));
        }
        if (expression.startsWith("java.util.List.of(") || expression.startsWith("java.util.Set.of(")) {
            final var content = expression.substring(expression.indexOf('(') + 1, expression.length() - 1);
            final List<String> values =
                    mapper.fromString(new Types.ParameterizedTypeImpl(List.class, String.class), '[' + content + ']');
            return values.stream().map(String::valueOf).collect(joining(","));
        }
        if ("long".equals(parameter.type()) && expression.endsWith("L")) {
            return expression.substring(0, expression.length() - 1);
        }
        if (!List.of("boolean", "int", "long", "java.lang.String").contains(parameter.type())
                && expression.contains(".")) {
            return expression.substring(expression.lastIndexOf('.') + 1);
        }
        return expression;
    }

    /**
     * Shared generator input derived exclusively from Fusion's descriptors.
     * The returned data remains usable after the caller closes its lookup and container.
     */
    public Map<String, Object> metadata() {
        final var commands = commands().stream()
                .map(command -> Map.<String, Object>of(
                        "name",
                        command.name(),
                        "description",
                        command.description(),
                        "hidden",
                        "true".equals(command.metadata().get("bundlebee.hidden")),
                        "options",
                        command.parameters().stream()
                                .filter(parameter -> !"help".equals(command.name())
                                        || parameter.configName().startsWith("bundlebee.help."))
                                .map(this::option)
                                .toList()))
                .toList();
        return Map.of(
                "commands",
                commands,
                "globalOptions",
                sharedParameters().map(this::option).toList());
    }

    private Map<String, Object> option(final CliCommand.Parameter parameter) {
        final var option = new LinkedHashMap<String, Object>();
        option.put("name", parameter.configName());
        option.put("cliName", parameter.cliName());
        option.put("description", parameter.description());
        final var type = parameter.type();
        option.put("type", type != null && type.contains("<") ? type.substring(0, type.indexOf('<')) : type);
        option.put("defaultValue", defaultValue(parameter));
        return option;
    }

    public static class Parameter {
        private final String name;
        private final String defaultValue;
        private final String description;

        public Parameter(final String name, final String defaultValue, final String description) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.description = description;
        }

        public String getName() {
            return this.name;
        }

        public String getDefaultValue() {
            return this.defaultValue;
        }

        public String getDescription() {
            return this.description;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Parameter other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(getName(), other.getName())
                    && Objects.equals(getDefaultValue(), other.getDefaultValue())
                    && Objects.equals(getDescription(), other.getDescription());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof ParameterExtractor.Parameter;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getName(), getDefaultValue(), getDescription());
        }

        @Override
        public String toString() {
            return "ParameterExtractor.Parameter(name=" + this.getName() + ", defaultValue=" + this.getDefaultValue()
                    + ", description=" + this.getDescription() + ")";
        }
    }
}
