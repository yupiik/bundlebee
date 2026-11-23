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
import static io.yupiik.bundlebee.lang.CompletionFutures.chain;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.ConditionAwaiter;
import io.yupiik.bundlebee.core.service.VersioningService;
import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "delete",
        description =
                "Delete an alveolus deployment by deleting all related descriptors.\n" + "// end of short description\n"
                        + "`bundlebee.delete.propagationPolicy` can be set in descriptor(s) metadata to force default CLI behavior for this descriptor.")
public class DeleteCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(DeleteCommand.class.getName());

    public DeleteCommand(
            final Options options,
            final KubeClient kube,
            final AlveolusHandler visitor,
            final ArchiveReader archives,
            final VersioningService versioningService,
            final ScheduledExecutorService scheduledExecutorService,
            final ConditionAwaiter conditionAwaiter,
            final NamespaceProvider api) {
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.gracePeriodSeconds = options.gracePeriodSeconds();
        this.await = options.await();
        this.awaitTimeout = options.awaitTimeout();
        this.deleteState = options.deleteState();
        this.excludedDescriptors = options.excludedDescriptors();
        this.excludedLocations = options.excludedLocations();
        this.kube = kube;
        this.visitor = visitor;
        this.archives = archives;
        this.versioningService = versioningService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.conditionAwaiter = conditionAwaiter;
        this.api = api;
    }

    private final String alveolus;
    private final String manifest;
    private final String from;
    private final String gracePeriodSeconds;
    private final String await;
    private final long awaitTimeout;
    private final boolean deleteState;
    private final String excludedDescriptors;
    private final String excludedLocations;
    private final KubeClient kube;
    private final AlveolusHandler visitor;
    private final ArchiveReader archives;
    private final VersioningService versioningService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ConditionAwaiter conditionAwaiter;
    private final NamespaceProvider api;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        if ("alveolus".equals(optionName)) {
            return visitor.findCompletionAlveoli(options);
        }
        return Stream.empty();
    }

    @Override
    public String name() {
        return "delete";
    }

    @Override
    public String description() {
        return "Delete an alveolus deployment by deleting all related descriptors.\n" + "// end of short description\n"
                + "`bundlebee.delete.propagationPolicy` can be set in descriptor(s) metadata to force default CLI behavior for this descriptor.";
    }

    @Override
    public CompletionStage<?> execute() {
        return execute(null);
    }

    public CompletionStage<?> execute(final String executionId) {
        return internalDelete(from, manifest, alveolus, gracePeriodSeconds, await, archives.newCache(), executionId);
    }

    public CompletionStage<?> internalDelete(
            final String from,
            final String manifest,
            final String alveolus,
            final String gracePeriodSeconds,
            final String await,
            final ArchiveReader.Cache cache) {
        return internalDelete(from, manifest, alveolus, gracePeriodSeconds, await, cache, null);
    }

    private CompletionStage<?> internalDelete(
            final String from,
            final String manifest,
            final String alveolus,
            final String gracePeriodSeconds,
            final String await,
            final ArchiveReader.Cache cache,
            final String executionId) {
        int awaitValue = 0;
        try {
            awaitValue = Integer.parseInt(await);
        } catch (final NumberFormatException nfe) {
        }
        // no-op
        final int awaitTimeout = awaitValue;
        return visitor.findRootAlveoli(from, manifest, alveolus, executionId)
                .thenApply(alveoli -> alveoli.stream()
                        .map(it -> it.exclude(excludedLocations, excludedDescriptors))
                        .collect(toList()))
                .thenCompose(alveoli -> all(
                        alveoli.stream()
                                .map(it -> doDelete(
                                        cache,
                                        it.getManifest(),
                                        it.getAlveolus(),
                                        gracePeriodSeconds,
                                        awaitTimeout,
                                        executionId))
                                .collect(toList()),
                        toList(),
                        true))
                .thenCompose(r -> deleteState(alveolus, r));
    }

    private CompletionStage<?> deleteState(final String alveolus, final List<?> r) {
        if (!deleteState || "auto".equals(alveolus)) {
            return completedFuture(null);
        }
        final var secretName = alveolus + "-bbs";
        final var fakeSecret = Map.<String, Object>of(
                "apiVersion",
                "v1",
                "kind",
                "Secret",
                "metadata",
                Map.of("namespace", api.namespace(), "name", secretName));
        return kube.getResource(fakeSecret).thenCompose(res -> {
            if (res.statusCode() != 200) {
                return completedFuture(res);
            }
            log.info("Cleaning up alveolus state");
            return kube.delete(kube.toBaseUri(fakeSecret, "secrets", api.namespace()) + '/' + secretName, null);
        });
    }

    public CompletionStage<?> doDelete(
            final ArchiveReader.Cache cache,
            final Manifest manifest,
            final Manifest.Alveolus it,
            final String gracePeriodSeconds,
            final int await) {
        return doDelete(cache, manifest, it, gracePeriodSeconds, await, null);
    }

    private CompletionStage<?> doDelete(
            final ArchiveReader.Cache cache,
            final Manifest manifest,
            final Manifest.Alveolus it,
            final String gracePeriodSeconds,
            final int await,
            final String executionId) {
        final var toDelete = new ArrayList<AlveolusHandler.LoadedDescriptor>();
        return
        // it is concurrent but we mainly want owner order here so "ok"
        // owner first
        visitor.executeOnceOnAlveolus(
                        "Deleting",
                        manifest,
                        it,
                        null,
                        (ctx, desc) -> {
                            synchronized (toDelete) {
                                toDelete.add(desc);
                            }
                            return completedFuture(true);
                        },
                        cache,
                        desc -> {
                            if (!desc.getConfiguration().awaitOnDelete()) {
                                return completedFuture(null);
                            }
                            return conditionAwaiter.await(name(), desc, scheduledExecutorService, awaitTimeout);
                        },
                        "deleted",
                        executionId)
                .thenApply(done -> {
                    Collections.reverse(toDelete);
                    return toDelete;
                })
                .thenCompose(descs -> chain(
                        descs.stream()
                                .map(desc -> (Supplier<CompletionStage<?>>) () -> kube.delete(
                                                desc.getContent(),
                                                desc.getExtension(),
                                                UNSET.equals(gracePeriodSeconds)
                                                        ? -1
                                                        : Integer.parseInt(gracePeriodSeconds))
                                        .thenApply(ignored -> desc))
                                .collect(toList())
                                .iterator(),
                        true))
                .thenCompose(result -> {
                    if (await <= 0 || toDelete.isEmpty()) {
                        return completedFuture(null);
                    }
                    return testIfDeletedOrAwait(toDelete, Instant.now().plusMillis(await));
                });
    }

    private CompletionStage<Boolean> testIfDeletedOrAwait(
            final List<AlveolusHandler.LoadedDescriptor> descriptors, final Instant end) {
        return all(
                        descriptors.stream()
                                .map(it -> kube.exists(it.getContent(), it.getExtension()))
                                .collect(toList()),
                        toList(),
                        true)
                .exceptionally(e -> List.of())
                .thenCompose(results -> {
                    final CompletableFuture<Boolean> future = new CompletableFuture<>();
                    if (results.size() == descriptors.size() && results.stream().allMatch(Boolean.FALSE::equals)) {
                        future.complete(true);
                        return future;
                    }
                    final var status =
                            "(" + results.stream().filter(Boolean.TRUE::equals).count() + "/" + results.size()
                                    + ", expected " + descriptors.size() + ").";
                    if (Instant.now().isAfter(end)) {
                        throw new IllegalStateException(
                                "Deletion didn\'t complete in " + await + "ms, giving up " + status);
                    }
                    log.info("Waiting 5 more seconds before testing if all descriptors were deleted " + status);
                    scheduledExecutorService.schedule(
                            () -> {
                                testIfDeletedOrAwait(descriptors, end).whenComplete((r, e) -> {
                                    if (e != null) {
                                        future.completeExceptionally(e);
                                    } else {
                                        future.complete(r);
                                    }
                                });
                            },
                            5,
                            TimeUnit.SECONDS);
                    return future;
                });
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.delete.alveolus",
                    defaultValue = "\"auto\"",
                    documentation = "Alveolus name to rollback (in currently deployed version). "
                            + "When set to `auto`, it will look up all manifests found in the classpath (it is not recommended until you perfectly know what you do). "
                            + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                            + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.delete.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.delete.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.delete.gracePeriodSeconds",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If set it will be added on REST calls to force a custom grace period (in seconds). Setting it to `0` enables to delete faster objects.")
            String gracePeriodSeconds,

            @Property(
                    value = "bundlebee.delete.awaitTimeout",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If an integer > 0, how long (ms) to await for the actual deletion of components, default does not await.")
            String await,

            @Property(
                    value = "bundlebee.delete.descriptorAwaitTimeout",
                    defaultValue = "60000L",
                    documentation =
                            "For descriptors with `await` = `true` the max duration the test can last. It is per descriptor with await true and independent of `awaitTimeout`.")
            long awaitTimeout,

            @Property(
                    value = "bundlebee.apply.trackState",
                    defaultValue = "false",
                    documentation =
                            "If `true` and a secret named based on the alveolus name (`$name-bbs`) exists, then it will be deleted.")
            boolean deleteState,

            @Property(
                    value = "bundlebee.delete.excludedDescriptors",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. "
                                    + "Note that using this setting, location is set to `*` so only the name is matched.")
            String excludedDescriptors,

            @Property(
                    value = "bundlebee.delete.excludedLocations",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
            String excludedLocations) {}
}
