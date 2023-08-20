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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.bundlebee.core.command.impl.lint.LintingCheck;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import lombok.Data;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static javax.json.JsonValue.EMPTY_JSON_ARRAY;

@Log
@Dependent
public class LintCommand implements CompletingExecutable {
    @Inject
    @Description("Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.lint.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.lint.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.lint.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("If `true`, an exception is throw if there is at least one error.")
    @ConfigProperty(name = "bundlebee.lint.failLevel", defaultValue = "ERROR")
    private LintError.LintLevel failLevel;

    @Inject
    @Description("Comma separated list of alveoli names to ignore.")
    @ConfigProperty(name = "bundlebee.lint.ignoredAlveoli", defaultValue = "-")
    private List<String> ignoredAlveoli;

    @Inject
    @Description("Comma separated list of descriptors to ignore.")
    @ConfigProperty(name = "bundlebee.lint.ignoredDescriptors", defaultValue = "-")
    private List<String> ignoredDescriptors;

    @Inject
    @Description("Comma separated list of rules to ignore (simple class name for built-in ones and check name for the others).")
    @ConfigProperty(name = "bundlebee.lint.ignoredRules", defaultValue = "-")
    private List<String> ignoredRules;

    @Inject
    @Description("Comma separated list of rules to use (others being ignored). `all` means use all discovered rules.")
    @ConfigProperty(name = "bundlebee.lint.forcedRules", defaultValue = "all")
    private List<String> forcedRules;

    @Inject
    @Description("Should remediation be shown (it is verbose so skipped by default).")
    @ConfigProperty(name = "bundlebee.lint.showRemediation", defaultValue = "false")
    private boolean showRemediation;

    @Inject
    private AlveolusHandler visitor;

    @Inject
    private ArchiveReader archives;

    @Inject
    private KubeClient k8s;

    @Inject
    @Any
    private Instance<LintingCheck> checks;

    private List<String> ruleNames;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "failLevel":
                return Stream.of(LintError.LintLevel.values()).map(LintError.LintLevel::name);
            case "alveolus":
                return visitor.findCompletionAlveoli(options);
            case "ignoredRules":
                if (ruleNames == null) {
                    ruleNames = this.checks.stream()
                            .map(LintingCheck::name)
                            .collect(toList());
                }
                return ruleNames.stream();
            default:
                return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "lint";
    }

    @Override
    public String description() {
        return "Do common validations on descriptors. As of today mainly cpu/memory resources definition.\n" +
                "// end of short description\n\n" +
                "include::content/_partials/generated/documentation/lint.checks.adoc[leveloffset=+1]\n";
    }

    @Override
    public CompletionStage<?> execute() {
        final var result = new LintErrors();
        return visit(result).thenRun(() -> postProcess(result));
    }

    private CompletionStage<List<DecoratedLintError>> doLint(final AlveolusHandler.AlveolusContext ctx,
                                                             final String descriptor,
                                                             final JsonObject desc,
                                                             final List<LintingCheck> checks,
                                                             final Manifest manifest) {
        if (ignoredAlveoli.contains(ctx.getAlveolus().getName()) || ignoredDescriptors.contains(descriptor)) {
            log.finest(() -> "Ignoring '" + descriptor + "' from alveolus '" + ctx.getAlveolus().getName() + "'");
            return completedFuture(List.of());
        }

        final var ignored = ofNullable(desc.getJsonArray("$bundlebeeIgnoredLintingRules")).orElse(EMPTY_JSON_ARRAY);

        log.finest(() -> "Linting " + ctx.getAlveolus().getName() + ": " + desc);
        final var ld = new LintingCheck.LintableDescriptor(ctx.getAlveolus().getName(), descriptor, desc);
        return all(checks.stream()
                        // excluded validations from the manifest
                        .filter(c -> manifest.getIgnoredLintingRules() == null || manifest.getIgnoredLintingRules().stream()
                                .noneMatch(r -> Objects.equals(r.getName(), c.name())))
                        // excluded validations from the descriptor itself in $bundlebeeIgnoredLintingRules attribute
                        .filter(c -> ignored.isEmpty() || ignored.stream()
                                .filter(it -> it.getValueType() == JsonValue.ValueType.STRING)
                                .map(it -> ((JsonString) it).getString())
                                .noneMatch(excluded -> Objects.equals(excluded, c.name())))
                        .filter(c -> c.accept(ld))
                        .map(c -> c.validate(ld)
                                .thenApply(errors -> errors
                                        .map(it -> new DecoratedLintError(it, ctx.getAlveolus().getName(), descriptor, c.remediation()))
                                        .collect(toList())))
                        .collect(toList()),
                mergeLists(),
                true);
    }

    private Collector<List<DecoratedLintError>, List<DecoratedLintError>, List<DecoratedLintError>> mergeLists() {
        return Collector.of(
                ArrayList::new,
                (a, it) -> {
                    synchronized (a) {
                        a.addAll(it);
                    }
                }, (a, b) -> {
                    a.addAll(b);
                    return a;
                });
    }

    private void postProcess(final LintErrors result) {
        if (result.errors.isEmpty()) {
            log.info(() -> "No linting error.");
            return;
        }

        if (result.errors.stream().anyMatch(e -> e.getError().getLevel().getLevel() >= failLevel.getLevel())) {
            throw result;
        }

        log.info("There are linting errors:");
        result.errors.stream()
                .sorted((a, b) -> {
                    final int diff = b.getError().getLevel().getLevel() - a.getError().getLevel().getLevel();
                    if (diff == 0) {
                        return a.getError().getMessage().compareTo(b.getError().getMessage());
                    }
                    return diff;
                })
                .forEach(i -> {
                    final var level = i.getError().getLevel();
                    final var logLevel = level == LintError.LintLevel.ERROR ? Level.SEVERE : Level.parse(level.name());
                    final var message = i.format();
                    if (!showRemediation) {
                        log.log(logLevel, message);
                    } else {
                        log.log(logLevel, message + (i.getRemediation() != null && !i.getRemediation().isBlank() ? "\n -> " + i.getRemediation() : ""));
                    }
                });
    }

    private CompletionStage<List<DecoratedLintError>> visit(final LintErrors result) {
        final var cache = archives.newCache();
        final var checks = this.checks.stream()
                .filter(it -> {
                    final var clazz = it.getClass();
                    return !(clazz.getPackageName().endsWith(".builtin") ?
                            (ignoredRules.contains(clazz.getSimpleName()) || ignoredRules.contains(it.name())) :
                            ignoredRules.contains(it.name()));
                })
                .filter(it -> (forcedRules.size() == 1 && "all".equals(forcedRules.get(0))) || forcedRules.contains(it.name()))
                .collect(toList());
        if (!(forcedRules.size() == 1 && "all".equals(forcedRules.get(0))) && forcedRules.size() != checks.size()) {
            throw new IllegalArgumentException("Didn't find all requested rules: " + forcedRules.stream()
                    .filter(it -> checks.stream().noneMatch(c -> Objects.equals(c.name(), it)))
                    .collect(joining(", ", "", " missing.")));
        }
        return visitor
                .findRootAlveoli(from, manifest, alveolus)
                .thenCompose(alveoli -> all(
                        alveoli.stream()
                                .map(it -> {
                                    final var allLints = new CopyOnWriteArrayList<CompletionStage<List<DecoratedLintError>>>();
                                    visitor.executeOnceOnAlveolus(
                                            null, it.getManifest(), it.getAlveolus(), null,
                                            (ctx, desc) -> k8s.forDescriptor(
                                                    "Linting", desc.getContent(), desc.getExtension(),
                                                    json -> {
                                                        final var promise = doLint(ctx, desc.getConfiguration().getName(), json, checks, it.getManifest());
                                                        allLints.add(promise);
                                                        return promise;
                                                    }),
                                            cache, null, "inspected");
                                    return all(allLints, mergeLists(), true);
                                })
                                .collect(toList()),
                        mergeLists(),
                        true))
                .thenCompose(errors -> {
                    result.errors.addAll(errors);
                    return all(
                            checks.stream()
                                    .map(c -> c.afterAll().thenApply(afterAllErrors -> afterAllErrors
                                            .map(e -> new DecoratedLintError(e, e.getAlveolus(), e.getDescriptor(), c.remediation()))
                                            .collect(toList())))
                                    .collect(toList()),
                            mergeLists(),
                            true);
                })
                .thenApply(errors -> {
                    result.errors.addAll(errors);
                    return result.errors;
                });
    }

    private static class LintErrors extends RuntimeException {
        private final List<DecoratedLintError> errors = new ArrayList<>();

        public LintErrors() {
            super("Linting errors");
        }

        @Override
        public String getMessage() {
            return super.getMessage() + (errors.isEmpty() ? ": no." : (":" + errors.stream()
                    .map(e -> "- [" + e.getError().getLevel().name() + "]" + e.format())
                    .sorted()
                    .collect(joining("\n", "\n", "\n"))));
        }
    }

    @Data
    private static class DecoratedLintError {
        private final LintError error;
        private final String aveolus;
        private final String descriptor;
        private final String remediation;

        public String format() {
            return "[" + getAveolus() + "][" + getDescriptor() + "] " + getError().getMessage();
        }
    }
}
