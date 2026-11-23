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

import static io.yupiik.bundlebee.lang.CompletionFutures.all;
import static java.util.Comparator.comparing;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.bundlebee.core.command.impl.lint.LintingCheck;
import io.yupiik.bundlebee.core.command.model.sarif.Artifact;
import io.yupiik.bundlebee.core.command.model.sarif.CompleteLocation;
import io.yupiik.bundlebee.core.command.model.sarif.Location;
import io.yupiik.bundlebee.core.command.model.sarif.Result;
import io.yupiik.bundlebee.core.command.model.sarif.Rule;
import io.yupiik.bundlebee.core.command.model.sarif.Run;
import io.yupiik.bundlebee.core.command.model.sarif.Sarif;
import io.yupiik.bundlebee.core.command.model.sarif.Text;
import io.yupiik.bundlebee.core.command.model.sarif.Tool;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collector;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "lint",
        description = "Do common validations on descriptors. As of today mainly cpu/memory resources definition.\n"
                + "// end of short description\n\n"
                + "include::content/_partials/generated/documentation/lint.checks.adoc[leveloffset=+1]\n")
public class LintCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(LintCommand.class.getName());

    public LintCommand(
            final Options options,
            AlveolusHandler visitor,
            ArchiveReader archives,
            KubeClient k8s,
            Components components,
            JsonMapper jsonb) {
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.failLevel = options.failLevel();
        this.ignoredAlveoli = options.ignoredAlveoli();
        this.ignoredDescriptors = options.ignoredDescriptors();
        this.ignoredRules = options.ignoredRules();
        this.forcedRules = options.forcedRules();
        this.showRemediation = options.showRemediation();
        this.output = options.output();
        this.visitor = visitor;
        this.archives = archives;
        this.k8s = k8s;
        this.checks = components.values();
        this.jsonb = jsonb;
    }

    private final String alveolus;
    private final String manifest;
    private final String from;
    private final LintError.LintLevel failLevel;
    private final List<String> ignoredAlveoli;
    private final List<String> ignoredDescriptors;
    private final List<String> ignoredRules;
    private final List<String> forcedRules;
    private final boolean showRemediation;
    private final String output;
    private final AlveolusHandler visitor;
    private final ArchiveReader archives;
    private final KubeClient k8s;
    private final List<LintingCheck> checks;
    private final JsonMapper jsonb;
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
                    ruleNames = this.checks.stream().map(LintingCheck::name).collect(toList());
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
        return "Do common validations on descriptors. As of today mainly cpu/memory resources definition.\n"
                + "// end of short description\n\n"
                + "include::content/_partials/generated/documentation/lint.checks.adoc[leveloffset=+1]\n";
    }

    @Override
    public CompletionStage<?> execute() {
        final var result = new LintErrors();
        final var checks = this.checks.stream()
                .filter(it -> {
                    final var clazz = it.getClass();
                    return !(clazz.getPackageName().endsWith(".builtin")
                            ? (ignoredRules.contains(clazz.getSimpleName()) || ignoredRules.contains(it.name()))
                            : ignoredRules.contains(it.name()));
                })
                .filter(it -> (forcedRules.size() == 1 && "all".equals(forcedRules.get(0)))
                        || forcedRules.contains(it.name()))
                .collect(toList());
        final var artifacts = new ConcurrentHashMap<String, Artifact>();
        return visit(result, checks, artifacts)
                .thenRun(() -> handleOutput(result, checks, artifacts))
                .thenRun(() -> postProcess(result));
    }

    private void handleOutput(
            final LintErrors result,
            final List<LintingCheck> checks,
            final ConcurrentHashMap<String, Artifact> artifacts) {
        if ("false".equals(output)) {
            return;
        }
        final var out = Path.of(output);
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            final var sortedArtifacts = artifacts.values().stream()
                    .sorted(comparing(a -> a.location().uri()))
                    .collect(toList());
            final var rules = checks.stream()
                    .sorted(comparing(LintingCheck::name))
                    .map(c -> new Rule(
                            c.name(),
                            new Text(c.description()),
                            "https://www.yupiik.io/bundlebee/commands/lint.configuration.html#_"
                                    + c.name().replace('-', '_'),
                            Map.of()))
                    .collect(toList());
            final var indexedRules = rules.isEmpty()
                    ? Map.<String, Integer>of()
                    : IntStream.range(0, rules.size())
                            .boxed()
                            .collect(toMap(id -> rules.get(id).id(), identity()));
            final var indexedArtifacts = sortedArtifacts.isEmpty()
                    ? Map.<String, Integer>of()
                    : IntStream.range(0, sortedArtifacts.size())
                            .boxed()
                            .collect(toMap(
                                    id -> sortedArtifacts.get(id).location().uri(), identity()));
            final var sarif = new Sarif(
                    "2.1.0",
                    "http://json.schemastore.org/sarif-2.1.0-rtm.4",
                    List.of(new Run(
                            new Tool(new Tool.Driver("Yupiik Bundlebee", "https://yupiik.io/bundlebee/", rules)),
                            sortedArtifacts,
                            result.errors.stream()
                                    .map(e -> {
                                        final var uri = artifacts
                                                .get(e.getDescriptor())
                                                .location()
                                                .uri();
                                        return new Result(
                                                e.getError().getLevel().getSarifLevel(),
                                                new Text(e.getError().getMessage()),
                                                e.getRuleName(),
                                                indexedRules.getOrDefault(e.getRuleName(), null),
                                                List.of(new CompleteLocation(new CompleteLocation.PhysicalLocation(
                                                        uri, indexedArtifacts.getOrDefault(uri, null)))));
                                    })
                                    .collect(toList()))));
            Files.writeString(out, jsonb.toString(sarif));
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    private CompletionStage<List<DecoratedLintError>> doLint(
            final AlveolusHandler.AlveolusContext ctx,
            final String descriptor,
            final Map<String, Object> desc,
            final List<LintingCheck> checks,
            final Manifest manifest) {
        if (ignoredAlveoli.contains(ctx.getAlveolus().name()) || ignoredDescriptors.contains(descriptor)) {
            log.finest(() -> "Ignoring \'" + descriptor + "\' from alveolus \'"
                    + ctx.getAlveolus().name() + "\'");
            return completedFuture(List.of());
        }
        final var ignored = desc.get("$bundlebeeIgnoredLintingRules") instanceof List<?> rules ? rules : List.of();
        log.finest(() -> "Linting " + ctx.getAlveolus().name() + ": " + desc);
        final var ld = new LintingCheck.LintableDescriptor(ctx.getAlveolus().name(), descriptor, desc);
        return all( // excluded validations from the manifest
                // excluded validations from the descriptor itself in $bundlebeeIgnoredLintingRules attribute
                checks.stream()
                        .filter(c -> manifest.ignoredLintingRules() == null
                                || manifest.ignoredLintingRules().stream()
                                        .noneMatch(r -> Objects.equals(r.name(), c.name())))
                        .filter(c -> ignored.isEmpty()
                                || ignored.stream()
                                        .filter(String.class::isInstance)
                                        .map(String.class::cast)
                                        .noneMatch(excluded -> Objects.equals(excluded, c.name())))
                        .filter(c -> c.accept(ld))
                        .map(c -> c.validate(ld)
                                .thenApply(errors -> errors.map(it -> new DecoratedLintError(
                                                it, ctx.getAlveolus().name(), descriptor, c.remediation(), c.name()))
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
                },
                (a, b) -> {
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
                    final int diff = b.getError().getLevel().getLevel()
                            - a.getError().getLevel().getLevel();
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
                        log.log(
                                logLevel,
                                message
                                        + (i.getRemediation() != null
                                                        && !i.getRemediation().isBlank()
                                                ? "\n -> " + i.getRemediation()
                                                : ""));
                    }
                });
    }

    private CompletionStage<List<DecoratedLintError>> visit(
            final LintErrors result, final List<LintingCheck> checks, final Map<String, Artifact> artifacts) {
        if (forcedRules.contains("none")) {
            return completedFuture(List.of());
        }
        final var cache = archives.newCache();
        if (!(forcedRules.size() == 1 && "all".equals(forcedRules.get(0))) && forcedRules.size() != checks.size()) {
            throw new IllegalArgumentException("Didn\'t find all requested rules: "
                    + forcedRules.stream()
                            .filter(it -> checks.stream().noneMatch(c -> Objects.equals(c.name(), it)))
                            .collect(joining(", ", "", " missing.")));
        }
        return visitor.findRootAlveoli(from, manifest, alveolus, null)
                .thenCompose(alveoli -> all(
                        alveoli.stream()
                                .map(it -> {
                                    final var allLints =
                                            new CopyOnWriteArrayList<CompletionStage<List<DecoratedLintError>>>();
                                    return visitor.executeOnceOnAlveolus(
                                                    null,
                                                    it.getManifest(),
                                                    it.getAlveolus(),
                                                    null,
                                                    (ctx, desc) -> {
                                                        artifacts.put(
                                                                desc.getConfiguration()
                                                                        .name(),
                                                                new Artifact(new Location(desc.getUri())));
                                                        return k8s.forDescriptor(
                                                                "Linting",
                                                                desc.getContent(),
                                                                desc.getExtension(),
                                                                json -> {
                                                                    final var promise = doLint(
                                                                            ctx,
                                                                            desc.getConfiguration()
                                                                                    .name(),
                                                                            json,
                                                                            checks,
                                                                            it.getManifest());
                                                                    allLints.add(promise);
                                                                    return promise;
                                                                });
                                                    },
                                                    cache,
                                                    null,
                                                    "inspected",
                                                    null)
                                            .thenCompose(ready -> all(allLints, mergeLists(), true));
                                })
                                .collect(toList()),
                        mergeLists(),
                        true))
                .thenCompose(errors -> {
                    result.errors.addAll(errors);
                    return all(
                            checks.stream()
                                    .map(c -> c.afterAll()
                                            .thenApply(afterAllErrors -> afterAllErrors
                                                    .map(e -> new DecoratedLintError(
                                                            e,
                                                            e.getAlveolus(),
                                                            e.getDescriptor(),
                                                            c.remediation(),
                                                            c.name()))
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
            return super.getMessage()
                    + (errors.isEmpty()
                            ? ": no."
                            : (":"
                                    + errors.stream()
                                            .map(e -> "- ["
                                                    + e.getError().getLevel().name() + "]" + e.format())
                                            .sorted()
                                            .collect(joining("\n", "\n", "\n"))));
        }
    }

    private static class DecoratedLintError {
        private final LintError error;
        private final String aveolus;
        private final String descriptor;
        private final String remediation;
        private final String ruleName;

        public String format() {
            return "[" + getAveolus() + "][" + getDescriptor() + "] "
                    + getError().getMessage();
        }

        public DecoratedLintError(
                final LintError error,
                final String aveolus,
                final String descriptor,
                final String remediation,
                final String ruleName) {
            this.error = error;
            this.aveolus = aveolus;
            this.descriptor = descriptor;
            this.remediation = remediation;
            this.ruleName = ruleName;
        }

        public LintError getError() {
            return this.error;
        }

        public String getAveolus() {
            return this.aveolus;
        }

        public String getDescriptor() {
            return this.descriptor;
        }

        public String getRemediation() {
            return this.remediation;
        }

        public String getRuleName() {
            return this.ruleName;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof DecoratedLintError other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getError(), other.getError())
                    && Objects.equals(this.getAveolus(), other.getAveolus())
                    && Objects.equals(this.getDescriptor(), other.getDescriptor())
                    && Objects.equals(this.getRemediation(), other.getRemediation())
                    && Objects.equals(this.getRuleName(), other.getRuleName());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof LintCommand.DecoratedLintError;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    this.getError(),
                    this.getAveolus(),
                    this.getDescriptor(),
                    this.getRemediation(),
                    this.getRuleName());
        }

        @Override
        public String toString() {
            return "LintCommand.DecoratedLintError(error=" + this.getError() + ", aveolus=" + this.getAveolus()
                    + ", descriptor=" + this.getDescriptor() + ", remediation=" + this.getRemediation() + ", ruleName="
                    + this.getRuleName() + ")";
        }
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.lint.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.lint.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.lint.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.lint.failLevel",
                    defaultValue = "io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.ERROR",
                    documentation = "If `true`, an exception is throw if there is at least one error.")
            LintError.LintLevel failLevel,

            @Property(
                    value = "bundlebee.lint.ignoredAlveoli",
                    defaultValue = "java.util.List.of(\"-\")",
                    documentation = "Comma separated list of alveoli names to ignore.")
            List<String> ignoredAlveoli,

            @Property(
                    value = "bundlebee.lint.ignoredDescriptors",
                    defaultValue = "java.util.List.of(\"-\")",
                    documentation = "Comma separated list of descriptors to ignore.")
            List<String> ignoredDescriptors,

            @Property(
                    value = "bundlebee.lint.ignoredRules",
                    defaultValue = "java.util.List.of(\"-\")",
                    documentation =
                            "Comma separated list of rules to ignore (simple class name for built-in ones and check name for the others).")
            List<String> ignoredRules,

            @Property(
                    value = "bundlebee.lint.forcedRules",
                    defaultValue = "java.util.List.of(\"all\")",
                    documentation =
                            "Comma separated list of rules to use (others being ignored). `all` means use all discovered rules and `none` skip them all (useful as a toggle/bypass mode).")
            List<String> forcedRules,

            @Property(
                    value = "bundlebee.lint.showRemediation",
                    defaultValue = "false",
                    documentation = "Should remediation be shown (it is verbose so skipped by default).")
            boolean showRemediation,

            @Property(
                    value = "bundlebee.lint.output",
                    defaultValue = "\"false\"",
                    documentation =
                            "If not `false`, the path of the output in link:https://github.com/microsoft/sarif-tutorials[SARIF] format (JSON).")
            String output) {}

    @DefaultScoped
    public record Components(List<LintingCheck> values) {}
}
