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
package io.yupiik.bundlebee.operator.handler;

import io.yupiik.bundlebee.core.command.impl.ApplyCommand;
import io.yupiik.bundlebee.core.command.impl.DeleteCommand;
import io.yupiik.bundlebee.core.lang.SubstitutorProducer;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class CommandRunner {
    private static final Pattern OPTION = Pattern.compile("--[a-zA-Z][a-zA-Z0-9_.-]*");

    private final ParameterExtractor metadata;
    private final SubstitutorProducer substitution;
    private final Configuration configuration;

    public CommandRunner(
            final ParameterExtractor metadata,
            final SubstitutorProducer substitution,
            final Configuration configuration) {
        this.metadata = metadata;
        this.substitution = substitution;
        this.configuration = configuration;
    }

    public void run(final String[] args) {
        runInvocation(args.clone());
    }

    private void runInvocation(final String[] args) {
        if (args.length == 0 || !List.of("apply", "delete").contains(args[0]) || args.length % 2 != 1) {
            throw new IllegalArgumentException("Operator arguments must be apply/delete option/value pairs");
        }
        // The launcher's config-file bootstrap scans values too, before command dispatch.
        // Never construct ArgsConfigSource from untrusted input: fusion-properties* loads files.
        for (final var arg : args) {
            if (arg == null || "--config-file".equals(arg) || arg.startsWith("--config-file=")) {
                throw new IllegalArgumentException("Config-file loading is not allowed in operator resource arguments");
            }
        }
        {
            final var values = new HashMap<String, String>();
            final var placeholders = new HashMap<String, String>();
            final var command = metadata.commands().stream()
                    .filter(candidate -> candidate.name().equals(args[0]))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing operator command metadata"));
            final var prefix = "bundlebee-" + command.name() + "-";
            for (int i = 1; i < args.length; i += 2) {
                final var option = args[i];
                if (!OPTION.matcher(option).matches()) {
                    throw new IllegalArgumentException("Operator arguments require --option value pairs");
                }
                final var name = option.substring(2).replace('.', '-');
                final var selectedParameter = command.parameters().stream()
                        .filter(parameter -> parameter.configName().startsWith("bundlebee." + command.name() + "."))
                        .filter(candidate -> {
                            final var canonical = candidate.configName().replace('.', '-');
                            return name.equals(canonical)
                                    || name.equals(canonical.substring(prefix.length()))
                                    || option.replace('.', '-')
                                            .equals(candidate.cliName().replace('.', '-'));
                        })
                        .findFirst();
                if (selectedParameter.isPresent()) {
                    values.put(selectedParameter.get().configName(), args[i + 1]);
                    values.put(command.keyMapper().apply(selectedParameter.get().configName()), args[i + 1]);
                } else {
                    final var reserved = name.startsWith("bundlebee-")
                            || name.startsWith("no-bundlebee-")
                            || name.startsWith("fusion-")
                            || name.equals("config-file")
                            || name.equals("kubeconfig")
                            || name.startsWith("user-")
                            || name.startsWith("java-")
                            || name.startsWith("javax-")
                            || name.startsWith("jdk-")
                            || name.equals("ARGOCD_APP_PARAMETERS")
                            || metadata.commands().stream()
                                    .anyMatch(candidate -> candidate.parameters().stream()
                                            .anyMatch(parameter -> name.equals(parameter
                                                            .configName()
                                                            .replace('.', '-'))
                                                    || option.replace('.', '-')
                                                            .equals(parameter
                                                                    .cliName()
                                                                    .replace('.', '-'))
                                                    || (parameter
                                                                    .configName()
                                                                    .startsWith("bundlebee." + candidate.name() + ".")
                                                            && name.equals(parameter
                                                                    .configName()
                                                                    .substring(("bundlebee." + candidate.name() + ".")
                                                                            .length())
                                                                    .replace('.', '-')))));
                    if (reserved) {
                        throw new IllegalArgumentException(
                                "Option '" + option + "' is reserved for configuration or another command");
                    }
                    // Preserve the exact recipe key: unlike CLI configuration this channel has no aliases.
                    placeholders.put(option.substring(2), args[i + 1]);
                }
            }
            final var executionId = UUID.randomUUID().toString();
            final var options = Map.copyOf(values);
            final Configuration perExecution =
                    key -> Optional.ofNullable(options.get(key)).or(() -> {
                        final var canonical = command.parameters().stream()
                                .filter(parameter -> command.keyMapper()
                                        .apply(parameter.configName())
                                        .equals(key))
                                .map(parameter -> parameter.configName())
                                .findFirst()
                                .orElse(key);
                        return configuration.get(canonical);
                    });
            substitution.setInvocationPlaceholders(executionId, placeholders);
            final var dependents = new ArrayList<Instance<?>>();
            try {
                final Instance<? extends Runnable> instance;
                try {
                    instance = command.create(perExecution, dependents);
                } catch (final RuntimeException | Error failure) {
                    for (int index = dependents.size() - 1; index >= 0; index--) {
                        try {
                            dependents.get(index).close();
                        } catch (final RuntimeException closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                    throw failure;
                }
                try (instance) {
                    final var executable = instance.instance();
                    if (executable instanceof ApplyCommand apply) {
                        apply.execute(executionId).toCompletableFuture().join();
                    } else if (executable instanceof DeleteCommand delete) {
                        delete.execute(executionId).toCompletableFuture().join();
                    } else {
                        throw new IllegalStateException("Unsupported operator command: " + executable);
                    }
                }
            } finally {
                substitution.clearInvocationPlaceholders(executionId);
            }
        }
    }
}
