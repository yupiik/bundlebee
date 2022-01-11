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

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.ConditionAwaiter;
import io.yupiik.bundlebee.core.service.VersioningService;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
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
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static io.yupiik.bundlebee.core.lang.CompletionFutures.chain;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class DeleteCommand implements CompletingExecutable {
    @Inject
    @Description("Alveolus name to rollback (in currently deployed version). " +
            "When set to `auto`, it will look up all manifests found in the classpath (it is not recommended until you perfectly know what you do). " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.delete.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.delete.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.delete.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("If set it will be added on REST calls to force a custom grace period (in seconds). Setting it to `0` enables to delete faster objects.")
    @ConfigProperty(name = "bundlebee.delete.gracePeriodSeconds", defaultValue = UNSET)
    private String gracePeriodSeconds;

    @Inject
    @Description("If an integer > 0, how long (ms) to await for the actual deletion of components, default does not await.")
    @ConfigProperty(name = "bundlebee.delete.awaitTimeout", defaultValue = UNSET)
    private String await;

    @Inject
    @Description("" +
            "For descriptors with `await` = `true` the max duration the test can last. It is per descriptor with await true and independent of `awaitTimeout`.")
    @ConfigProperty(name = "bundlebee.delete.descriptorAwaitTimeout", defaultValue = "60000")
    private long awaitTimeout;

    @Inject
    @Description("" +
            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. " +
            "Note that using this setting, location is set to `*` so only the name is matched.")
    @ConfigProperty(name = "bundlebee.delete.excludedDescriptors", defaultValue = "none")
    private String excludedDescriptors;

    @Inject
    @Description("" +
            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
    @ConfigProperty(name = "bundlebee.delete.excludedLocations", defaultValue = "none")
    private String excludedLocations;

    @Inject
    private KubeClient kube;

    @Inject
    private AlveolusHandler visitor;

    @Inject
    private ArchiveReader archives;

    @Inject
    private VersioningService versioningService;

    @Inject
    @BundleBee
    private ScheduledExecutorService scheduledExecutorService;

    @Inject
    private ConditionAwaiter conditionAwaiter;

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
        return "Delete an alveolus deployment by deleting all related descriptors.\n" +
                "// end of short description\n" +
                "`bundlebee.delete.propagationPolicy` can be set in descriptor(s) metadata to force default CLI behavior for this descriptor.";
    }

    @Override
    public CompletionStage<?> execute() {
        return internalDelete(from, manifest, alveolus, gracePeriodSeconds, await, archives.newCache());
    }

    public CompletionStage<?> internalDelete(final String from, final String manifest, final String alveolus,
                                             final String gracePeriodSeconds, final String await,
                                             final ArchiveReader.Cache cache) {
        int awaitValue = 0;
        try {
            awaitValue = Integer.parseInt(await);
        } catch (final NumberFormatException nfe) {
            // no-op
        }
        final int awaitTimeout = awaitValue;
        return visitor
                .findRootAlveoli(from, manifest, alveolus)
                .thenApply(alveoli -> alveoli.stream().map(it -> it.exclude(excludedLocations, excludedDescriptors)).collect(toList()))
                .thenCompose(alveoli -> all(
                        alveoli.stream()
                                .map(it -> doDelete(cache, it.getManifest(), it.getAlveolus(), gracePeriodSeconds, awaitTimeout))
                                .collect(toList()), toList(),
                        true));
    }

    public CompletionStage<?> doDelete(final ArchiveReader.Cache cache, final Manifest manifest, final Manifest.Alveolus it,
                                       final String gracePeriodSeconds, final int await) {
        final var toDelete = new ArrayList<AlveolusHandler.LoadedDescriptor>();
        return visitor.executeOnceOnAlveolus(
                "Deleting", manifest, it, null,
                (ctx, desc) -> {
                    synchronized (toDelete) { // it is concurrent but we mainly want owner order here so "ok"
                        toDelete.add(desc);
                    }
                    return completedFuture(true);
                },
                cache, desc -> conditionAwaiter.await(name(), desc, scheduledExecutorService, awaitTimeout), "deleted")
                .thenApply(done -> { // owner first
                    Collections.reverse(toDelete);
                    return toDelete;
                })
                .thenCompose(descs -> chain(
                        descs.stream()
                                .map(desc -> (Supplier<CompletionStage<?>>) () -> kube.delete(
                                        desc.getContent(), desc.getExtension(),
                                        UNSET.equals(gracePeriodSeconds) ? -1 : Integer.parseInt(gracePeriodSeconds))
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

    private CompletionStage<Boolean> testIfDeletedOrAwait(final List<AlveolusHandler.LoadedDescriptor> descriptors, final Instant end) {
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

                    final var status = "(" +
                            results.stream().filter(Boolean.TRUE::equals).count() + "/" + results.size() + ", expected " + descriptors.size() + ").";
                    if (Instant.now().isAfter(end)) {
                        throw new IllegalStateException("Deletion didn't complete in " + await + "ms, giving up " + status);
                    }
                    log.info("Waiting 5 more seconds before testing if all descriptors were deleted " + status);
                    scheduledExecutorService.schedule(() -> {
                        testIfDeletedOrAwait(descriptors, end).whenComplete((r, e) -> {
                            if (e != null) {
                                future.completeExceptionally(e);
                            } else {
                                future.complete(r);
                            }
                        });
                    }, 5, TimeUnit.SECONDS);
                    return future;
                });
    }
}
