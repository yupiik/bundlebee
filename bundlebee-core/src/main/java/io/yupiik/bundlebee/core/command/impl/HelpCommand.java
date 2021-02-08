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
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;

@Log
@Dependent
public class HelpCommand implements Executable {
    @Any
    @Inject
    private Instance<Executable> executables;

    @Inject
    private BeanManager beanManager;

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Print help.";
    }

    @Override
    public CompletionStage<?> execute() {
        log.info("\n" +
                "BundleBee\n" +
                "\n" +
                "  BundleBee is a light Kubernetes package manager. Available commands:\n" +
                "\n" +
                executables.stream()
                        .map(executable -> {
                            final var desc = executable.description()
                                    .replace("// end of short description\n", "")
                                    .replaceAll("^", "        ")
                                    .trim();
                            return "" +
                                    "  - " + executable.name() + ": " +
                                    Character.toLowerCase(desc.charAt(0)) + desc.substring(1) + "\n" +
                                    findParameters(executable).map(p -> "" +
                                            "    " + p.getName() +
                                            (p.getDefaultValue() != null ? " (default: " + p.getDefaultValue() + ")" : "") + ": " +
                                            p.getDescription())
                                            .sorted()
                                            .collect(joining("\n"));
                        })
                        .sorted()
                        .collect(joining("\n\n", "", "\n")));
        return completedFuture(null);
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
                    return new Parameter(
                            "--" + prefix.matcher(name).replaceFirst(""),
                            ConfigProperty.UNCONFIGURED_VALUE.equals(annotation.defaultValue()) ? null : annotation.defaultValue(),
                            annotated.getAnnotation(Description.class).value());
                });
    }

    @Data
    private static class Parameter {
        private final String name;
        private final String defaultValue;
        private final String description;
    }
}
