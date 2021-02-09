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
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.lang.Tuple2;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.bundlebee.core.service.VersioningService;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class RollbackCommand implements Executable {
    @Inject
    @Description("Alveolus name to install as rollback.")
    @ConfigProperty(name = "bundlebee.rollback.previousAlveolus", defaultValue = "auto")
    private String previousAlveolus;

    @Inject
    @Description("Same as `manifest` but for the previous alveolus to install.")
    @ConfigProperty(name = "bundlebee.rollback.previousManifest", defaultValue = "skip")
    private String previousManifest;

    @Inject
    @Description("Same as `from` but for the previous alveolus to install.")
    @ConfigProperty(name = "bundlebee.rollback.previousFrom", defaultValue = "auto")
    private String previousFrom;

    @Inject
    @Description("Alveolus name to rollback (in currently deployed version). " +
            "When set to `auto`, it will look up all manifests found in the classpath (it is not recommended until you perfectly know what you do). " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.rollback.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.rollback.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.rollback.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("" +
            "If `true`, and previous alveolus is not defined on the CLI, we will query the release repository to find available versions. " +
            "If the alveolus name does not match `<groupId>:<artifactId>:<version>[:<type>:<classifier>]` pattern then a heuristic is used instead.")
    @ConfigProperty(name = "bundlebee.rollback.useMavenVersioning", defaultValue = "true")
    private boolean useMavenVersioning;

    @Inject
    private KubeClient kube;

    @Inject
    private VersioningService versioningService;

    @Inject
    private AlveolusHandler alveolusHandler;

    @Inject
    private Maven resolver;

    @Inject
    private ArchiveReader archives;

    @Inject
    private ApplyCommand apply;

    @Inject
    private DeleteCommand delete;

    @Override
    public String name() {
        return "rollback";
    }

    @Override
    public String description() {
        return "Rollback an alveolus deployment.\n" +
                "// end of short description\n\n" +
                "The strategy is the following one:\n" +
                "\n" +
                "* Load current alveolus (recursively) to list all descriptors in \"current\" version\n" +
                "* Find previous version if not explicit (by choosing the first previous on in the list of available version on maven repository)\n" +
                "* Run delete command for current version\n" +
                "* Run apply command for previous version\n" +
                "\n" +
                "IMPORTANT: this command only uses releases when it guesses the versions, it does not handles SNAPSHOTs. " +
                "If you want to rollback to a snapshot, ensure to configure `previous*` properties.\n" +
                "\n" +
                "TIP: this is equivalent to `apply` and `delete` commands successfully. For now it is recommended to do both manually.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var cache = archives.newCache();
        return alveolusHandler.findRootAlveoli(from, manifest, alveolus)
                .thenCompose(alveolus -> all(
                        alveolus.stream()
                                .map(it -> findPreviousVersion(it, cache)
                                        .thenApply(previous -> new Tuple2<>(it, previous)))
                                .map(versions -> versions.thenCompose(v -> rollback(cache, v)))
                                .collect(toList()),
                        toList(),
                        true));
    }

    private CompletionStage<?> rollback(final ArchiveReader.Cache cache, final Tuple2<Manifest.Alveolus, Object> v) {
        return delete.doDelete(cache, v.getFirst())
                .thenCompose(it -> apply.doApply(true, true, cache, v.getSecond()));
    }

    private CompletionStage<Manifest.Alveolus> findPreviousVersion(final Manifest.Alveolus alveolus,
                                                                   final ArchiveReader.Cache cache) {
        if ("auto".equals(previousFrom) && "skip".equals(previousManifest) && "auto".equals(previousAlveolus)) {
            return guessPreviousVersion(alveolus, cache);
        }
        return alveolusHandler
                // todo: can be found from alveolus.location too
                .findRootAlveoli(previousFrom, previousManifest, previousAlveolus)
                .thenApply(list -> {
                    if (list.size() != 1) {
                        throw new IllegalArgumentException("Ambiguous previous version, found: " + list.stream()
                                .map(Manifest.Alveolus::getName)
                                .collect(joining(", ")));
                    }
                    return list.iterator().next();
                });
    }

    private CompletionStage<Manifest.Alveolus> guessPreviousVersion(final Manifest.Alveolus alveolus,
                                                                    final ArchiveReader.Cache cache) {
        final var name = alveolus.getName();
        log.info(() -> "Looking for previous version of '" + name + "'" +
                (alveolus.getVersion() != null ? " (version=" + alveolus.getVersion() + ")" : ""));
        if (useMavenVersioning) {
            final var segments = name.split(":");
            if (segments.length > 2) {
                return resolver
                        .findAvailableVersions(segments[0], segments[1])
                        .thenCompose(versions -> {
                            final String previousVersion = matchPreviousVersion(alveolus, versions);
                            segments[2] = previousVersion;
                            return alveolusHandler.findRootAlveoli(findPreviousFrom(segments), manifest, String.join(":", segments));
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
        return tryToFindPreviousVersion(alveolus, previousVersionsOf(semanticVersion).iterator());
    }

    private CompletionStage<Manifest.Alveolus> tryToFindPreviousVersion(final Manifest.Alveolus alveolus,
                                                                        final Iterator<String> potentialVersionsIt) {
        final var result = new CompletableFuture<Manifest.Alveolus>();
        try {
            if (!potentialVersionsIt.hasNext()) {
                result.completeExceptionally(new IllegalArgumentException("Can't find previous version of " + alveolus));
            } else {
                final var version = potentialVersionsIt.next();
                log.finest(() -> "Testing version='" + version + "' for '" + alveolus.getName() + "'");
                alveolusHandler
                        .findRootAlveoli(previousFrom, previousManifest, alveolus.getName())
                        .whenComplete((list, error) -> {
                            if (error == null || list.isEmpty()) {
                                tryToFindPreviousVersion(alveolus, potentialVersionsIt).whenComplete((r, e) -> {
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
                                        .filter(it -> Objects.equals(versioningService.findVersion(it), version))
                                        .collect(toList());
                                if (selected.size() == 1) {
                                    result.complete(selected.iterator().next());
                                } else {
                                    result.completeExceptionally(new IllegalArgumentException(
                                            "Didn't find version '" + version + "' for '" + alveolus.getName() + "'"));
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
                previousVersions.add(semanticVersion.getMajor() + "." + semanticVersion.getMinor() + "." + (semanticVersion.getPatch() - 1));
            }
        } else {
            log.warning(() -> "Will test previous versions with and without patch version since it is missing in the deployed alveolus");
            previousVersions.add(semanticVersion.getMajor() + "." + semanticVersion.getMinor() + ".0");
            previousVersions.add(semanticVersion.getMajor() + "." + (semanticVersion.getMinor() - 1) + ".0");
            previousVersions.add(semanticVersion.getMajor() + "." + (semanticVersion.getMinor() - 1));
        }
        log.finest(() -> "Will test previous versions: " + previousVersions);
        return previousVersions;
    }

    private String matchPreviousVersion(final Manifest.Alveolus alveolus, final List<String> candicates) {
        return candicates.iterator().next(); // todo
    }

    private String findPreviousFrom(final String[] segments) {
        if (!"auto".equals(previousFrom)) {
            return previousFrom;
        }
        if ("auto".equals(from)) {
            return "auto";
        }
        final var fromSegments = from.split(":");
        if (fromSegments.length > 2) { // inherit from classifier etc
            fromSegments[2] = segments[2];
            return String.join(":", fromSegments);
        }
        log.finest(() -> "Can't determine previousFrom, using auto");
        return "auto"; // crossing fingers for now, should be unlikely
    }
}
