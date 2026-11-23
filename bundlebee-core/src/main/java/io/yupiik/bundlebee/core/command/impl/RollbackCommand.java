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
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.lang.Tuple2;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.bundlebee.core.service.VersioningService;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "rollback",
        description =
                "Rollback an alveolus deployment.\n" + "// end of short description\n\n"
                        + "The strategy is the following one:\n" + "\n"
                        + "* Load current alveolus (recursively) to list all descriptors in \"current\" version\n"
                        + "* Find previous version if not explicit (by choosing the first previous on in the list of available version on maven repository)\n"
                        + "* Run delete command for current version\n" + "* Run apply command for previous version\n"
                        + "\n"
                        + "IMPORTANT: this command only uses releases when it guesses the versions, it does not handles SNAPSHOTs. "
                        + "If you want to rollback to a snapshot, ensure to configure `previous*` properties.\n" + "\n"
                        + "TIP: this is equivalent to `apply` and `delete` commands successfully. For now it is recommended to do both manually.")
public class RollbackCommand implements CompletingExecutable {
    private static final Pattern COLON = Pattern.compile(":");
    private final Logger log = Logger.getLogger(RollbackCommand.class.getName());

    public RollbackCommand(
            final Options options,
            KubeClient kube,
            VersioningService versioningService,
            AlveolusHandler alveolusHandler,
            Maven resolver,
            ArchiveReader archives,
            ApplyCommand apply,
            DeleteCommand delete,
            ScheduledExecutorService scheduledExecutorService) {
        this.previousAlveolus = options.previousAlveolus();
        this.previousManifest = options.previousManifest();
        this.previousFrom = options.previousFrom();
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.gracePeriodSeconds = options.gracePeriodSeconds();
        this.await = options.await();
        this.pause = options.pause();
        this.useMavenVersioning = options.useMavenVersioning();
        this.kube = kube;
        this.versioningService = versioningService;
        this.alveolusHandler = alveolusHandler;
        this.resolver = resolver;
        this.archives = archives;
        this.apply = apply;
        this.delete = delete;
        this.scheduledExecutorService = scheduledExecutorService;
    }

    private final String previousAlveolus;
    private final String previousManifest;
    private final String previousFrom;
    private final String alveolus;
    private final String manifest;
    private final String from;
    private final String gracePeriodSeconds;
    private final String await;
    private final long pause;
    private final boolean useMavenVersioning;
    private final KubeClient kube;
    private final VersioningService versioningService;
    private final AlveolusHandler alveolusHandler;
    private final Maven resolver;
    private final ArchiveReader archives;
    private final ApplyCommand apply;
    private final DeleteCommand delete;
    private final ScheduledExecutorService scheduledExecutorService;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "useMavenVersioning":
                return Stream.of("false", "true");
            case "alveolus":
                return alveolusHandler.findCompletionAlveoli(options);
            default:
                return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "rollback";
    }

    @Override
    public String description() {
        return "Rollback an alveolus deployment.\n" + "// end of short description\n\n"
                + "The strategy is the following one:\n" + "\n"
                + "* Load current alveolus (recursively) to list all descriptors in \"current\" version\n"
                + "* Find previous version if not explicit (by choosing the first previous on in the list of available version on maven repository)\n"
                + "* Run delete command for current version\n" + "* Run apply command for previous version\n" + "\n"
                + "IMPORTANT: this command only uses releases when it guesses the versions, it does not handles SNAPSHOTs. "
                + "If you want to rollback to a snapshot, ensure to configure `previous*` properties.\n" + "\n"
                + "TIP: this is equivalent to `apply` and `delete` commands successfully. For now it is recommended to do both manually.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var cache = archives.newCache();
        return alveolusHandler
                .findRootAlveoli(from, manifest, alveolus, null)
                .thenCompose(alveolus -> all(
                        alveolus.stream()
                                .map(it -> findPreviousVersion(it, cache)
                                        .thenApply(previous -> new Tuple2<>(it, previous)))
                                .map(versions -> versions.thenCompose(v -> rollback(cache, v)))
                                .collect(toList()),
                        toList(),
                        true));
    }

    private CompletionStage<?> rollback(
            final ArchiveReader.Cache cache,
            final Tuple2<AlveolusHandler.ManifestAndAlveolus, AlveolusHandler.ManifestAndAlveolus> v) {
        return delete.doDelete(
                        cache,
                        v.first().getManifest(),
                        v.first().getAlveolus(),
                        gracePeriodSeconds,
                        Integer.parseInt(await))
                .thenCompose(it -> {
                    if (pause <= 0) {
                        return completedFuture(it);
                    }
                    final var promise = new CompletableFuture<>();
                    scheduledExecutorService.schedule(() -> promise.complete(it), pause, MILLISECONDS);
                    return promise;
                })
                .thenCompose(it -> apply.doApply(true, true, cache, v.second()));
    }

    private CompletionStage<AlveolusHandler.ManifestAndAlveolus> findPreviousVersion(
            final AlveolusHandler.ManifestAndAlveolus alveolus, final ArchiveReader.Cache cache) {
        if ("auto".equals(previousFrom) && "skip".equals(previousManifest) && "auto".equals(previousAlveolus)) {
            return guessPreviousVersion(alveolus.getAlveolus(), cache);
        }
        return // todo: can be found from alveolus.location too
        alveolusHandler
                .findRootAlveoli(previousFrom, previousManifest, previousAlveolus, null)
                .thenApply(list -> {
                    if (list.size() != 1) {
                        throw new IllegalArgumentException("Ambiguous previous version, found: "
                                + list.stream()
                                        .map(AlveolusHandler.ManifestAndAlveolus::getAlveolus)
                                        .map(Manifest.Alveolus::name)
                                        .collect(joining(", ")));
                    }
                    return list.iterator().next();
                });
    }

    private CompletionStage<AlveolusHandler.ManifestAndAlveolus> guessPreviousVersion(
            final Manifest.Alveolus alveolus, final ArchiveReader.Cache cache) {
        final var name = alveolus.name();
        log.info(() -> "Looking for previous version of \'" + name + "\'"
                + (alveolus.version() != null ? " (version=" + alveolus.version() + ")" : ""));
        if (useMavenVersioning) {
            final var segments = COLON.split(name);
            if (segments.length > 2) {
                return resolver.findAvailableVersions(segments[0], segments[1])
                        .thenCompose(versions -> {
                            final String previousVersion = matchPreviousVersion(alveolus, versions);
                            segments[2] = previousVersion;
                            return alveolusHandler.findRootAlveoli(
                                    findPreviousFrom(segments), manifest, String.join(":", segments), null);
                        })
                        .thenApply(alveoli -> {
                            if (alveoli.size() != 1) {
                                throw new IllegalArgumentException("Ambiguous previous  alveolus, found: " + alveoli);
                            }
                            return alveoli.iterator().next();
                        });
            }
        }
        // try previous versions (a bit)
        final var semanticVersion = versioningService.toSemanticVersion(versioningService.findVersion(alveolus));
        return tryToFindPreviousVersion(
                alveolus, previousVersionsOf(semanticVersion).iterator());
    }

    private CompletionStage<AlveolusHandler.ManifestAndAlveolus> tryToFindPreviousVersion(
            final Manifest.Alveolus alveolus, final Iterator<String> potentialVersionsIt) {
        final var result = new CompletableFuture<AlveolusHandler.ManifestAndAlveolus>();
        try {
            if (!potentialVersionsIt.hasNext()) {
                result.completeExceptionally(
                        new IllegalArgumentException("Can\'t find previous version of " + alveolus));
            } else {
                final var version = potentialVersionsIt.next();
                log.finest(() -> "Testing version=\'" + version + "\' for \'" + alveolus.name() + "\'");
                alveolusHandler
                        .findRootAlveoli(previousFrom, previousManifest, alveolus.name(), null)
                        .whenComplete((list, error) -> {
                            if (error == null || list.isEmpty()) {
                                tryToFindPreviousVersion(alveolus, potentialVersionsIt)
                                        .whenComplete((r, e) -> {
                                            if (e != null) {
                                                result.completeExceptionally(e);
                                            } else {
                                                result.complete(r);
                                            }
                                        });
                                return;
                            }
                            if (list.size() == 1) {
                                result.complete(list.iterator().next());
                            } else {
                                final var selected = list.stream()
                                        .filter(it -> Objects.equals(
                                                versioningService.findVersion(it.getAlveolus()), version))
                                        .collect(toList());
                                if (selected.size() == 1) {
                                    result.complete(selected.iterator().next());
                                } else {
                                    result.completeExceptionally(new IllegalArgumentException("Didn\'t find version \'"
                                            + version + "\' for \'" + alveolus.name() + "\'"));
                                }
                            }
                        });
            }
        } catch (final RuntimeException re) {
            result.completeExceptionally(re);
        }
        return result;
    }

    private List<String> previousVersionsOf(final VersioningService.SemanticVersion semanticVersion) {
        final List<String> previousVersions = new ArrayList<>();
        if (semanticVersion.isHasPatch()) {
            if (semanticVersion.getPatch() == 0) {
                log.warning(() -> "Will potentially rollback to a too old version since patch version is 0");
                previousVersions.add(semanticVersion.getMajor() + "." + (semanticVersion.getMinor() - 1) + ".0");
            } else {
                previousVersions.add(semanticVersion.getMajor() + "." + semanticVersion.getMinor() + "."
                        + (semanticVersion.getPatch() - 1));
            }
        } else {
            log.warning(
                    () ->
                            "Will test previous versions with and without patch version since it is missing in the deployed alveolus");
            previousVersions.add(semanticVersion.getMajor() + "." + semanticVersion.getMinor() + ".0");
            previousVersions.add(semanticVersion.getMajor() + "." + (semanticVersion.getMinor() - 1) + ".0");
            previousVersions.add(semanticVersion.getMajor() + "." + (semanticVersion.getMinor() - 1));
        }
        log.finest(() -> "Will test previous versions: " + previousVersions);
        return previousVersions;
    }

    private String matchPreviousVersion(final Manifest.Alveolus alveolus, final List<String> candicates) {
        // todo
        return candicates.iterator().next();
    }

    private String findPreviousFrom(final String[] segments) {
        if (!"auto".equals(previousFrom)) {
            return previousFrom;
        }
        if ("auto".equals(from)) {
            return "auto";
        }
        final var fromSegments = COLON.split(from);
        if (fromSegments.length > 2) {
            // inherit from classifier etc
            fromSegments[2] = segments[2];
            return String.join(":", fromSegments);
        }
        log.finest(() -> "Can\'t determine previousFrom, using auto");
        // crossing fingers for now, should be unlikely
        return "auto";
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.rollback.previousAlveolus",
                    defaultValue = "\"auto\"",
                    documentation = "Alveolus name to install as rollback.")
            String previousAlveolus,

            @Property(
                    value = "bundlebee.rollback.previousManifest",
                    defaultValue = "\"skip\"",
                    documentation = "Same as `manifest` but for the previous alveolus to install.")
            String previousManifest,

            @Property(
                    value = "bundlebee.rollback.previousFrom",
                    defaultValue = "\"auto\"",
                    documentation = "Same as `from` but for the previous alveolus to install.")
            String previousFrom,

            @Property(
                    value = "bundlebee.rollback.alveolus",
                    defaultValue = "\"auto\"",
                    documentation = "Alveolus name to rollback (in currently deployed version). "
                            + "When set to `auto`, it will look up all manifests found in the classpath (it is not recommended until you perfectly know what you do). "
                            + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                            + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.rollback.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.rollback.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.rollback.gracePeriodSeconds",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If set it will be added on REST calls to force a custom grace period (in seconds). Setting it to `0` enables to delete faster objects.")
            String gracePeriodSeconds,

            @Property(
                    value = "bundlebee.rollback.awaitTimeout",
                    defaultValue = "\"120000\"",
                    documentation =
                            "If an integer > 0, how long (ms) to await for the actual deletion of components - before redeploying.")
            String await,

            @Property(
                    value = "bundlebee.rollback.pause",
                    defaultValue = "0L",
                    documentation =
                            "A pause (in ms) between delete and apply phases, this can some environment to release all resources related to deleted components.")
            long pause,

            @Property(
                    value = "bundlebee.rollback.useMavenVersioning",
                    defaultValue = "true",
                    documentation =
                            "If `true`, and previous alveolus is not defined on the CLI, we will query the release repository to find available versions. "
                                    + "If the alveolus name does not match `<groupId>:<artifactId>:<version>[:<type>:<classifier>]` pattern then a heuristic is used instead.")
            boolean useMavenVersioning) {}
}
