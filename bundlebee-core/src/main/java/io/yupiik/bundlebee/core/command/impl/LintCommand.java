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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

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
    private AlveolusHandler visitor;

    @Inject
    private ArchiveReader archives;

    @Inject
    private KubeClient k8s;

    @Inject
    @Any
    private Instance<LintingCheck> checks;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "failLevel":
                return Stream.of(LintError.LintLevel.values()).map(LintError.LintLevel::name);
            case "alveolus":
                return visitor.findCompletionAlveoli(options);
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
        return "Do common validations on descriptors. As of today mainly cpu/memory resources definition.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var result = new LintErrors();
        return visit(result).thenRun(() -> postProcess(result));
    }

    private void doLint(final AlveolusHandler.AlveolusContext ctx,
                        final String descriptor,
                        final JsonObject desc,
                        final LintErrors result,
                        final List<LintingCheck> checks) {
        if (ignoredAlveoli.contains(ctx.getAlveolus().getName()) || ignoredDescriptors.contains(descriptor)) {
            log.finest(() -> "Ignoring '" + descriptor + "' from alveolus '" + ctx.getAlveolus().getName() + "'");
            return;
        }

        log.finest(() -> "Linting " + ctx.getAlveolus().getName() + ": " + desc);
        final var ld = new LintingCheck.LintableDescriptor(descriptor, desc);
        result.errors.addAll(checks.stream()
                .filter(c -> c.accept(ld))
                .flatMap(c -> c.validate(ld))
                .map(it -> new DecoratedLintError(it, ctx.getAlveolus().getName(), descriptor))
                .collect(toList()));
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
                    log.log(level == LintError.LintLevel.ERROR ? Level.SEVERE : Level.parse(level.name()), i.format());
                });
    }

    private CompletionStage<? extends List<?>> visit(final LintErrors result) {
        final var cache = archives.newCache();
        final var checks = this.checks.stream()
                .filter(it -> {
                    final var clazz = it.getClass();
                    return !(clazz.getPackageName().endsWith(".builtin") ?
                            (ignoredRules.contains(clazz.getSimpleName()) || ignoredRules.contains(it.name())) :
                            ignoredRules.contains(it.name()));
                })
                .collect(toList());
        return visitor
                .findRootAlveoli(from, manifest, alveolus)
                .thenCompose(alveoli -> all(
                        alveoli.stream()
                                .map(it -> visitor.executeOnceOnAlveolus(
                                        null, it.getManifest(), it.getAlveolus(), null,
                                        (ctx, desc) -> k8s.forDescriptor("Linting", desc.getContent(), desc.getExtension(), json -> {
                                            doLint(ctx, desc.getConfiguration().getName(), json, result, checks);
                                            return completedFuture(true);
                                        }),
                                        cache, null, "inspected"))
                                .collect(toList()), toList(),
                        true));
    }

    private static class LintErrors extends RuntimeException {
        private final List<DecoratedLintError> errors = new ArrayList<>();

        public LintErrors() {
            super("There are linting errors");
        }

        @Override
        public String getMessage() {
            return super.getMessage() + ": " + (errors.isEmpty() ? "no." : errors.stream()
                    .map(e -> "- [" + e.getError().getLevel().name() + "]" + e.format())
                    .sorted()
                    .collect(joining("\n", "\n", "\n")));
        }
    }

    @Data
    private static class DecoratedLintError {
        private final LintError error;
        private final String aveolus;
        private final String descriptor;

        public String format() {
            return "[" + getAveolus() + "][" + getDescriptor() + "] " + getError().getMessage();
        }
    }
}
