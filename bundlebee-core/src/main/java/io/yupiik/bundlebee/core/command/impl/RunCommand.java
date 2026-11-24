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
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = "run",
        description =
                "Tries to run the recipe as plain forks on the host. In this mode, Bundlebee is used as the orchestrator. "
                        + "It is highly recommended to only do that with application matching the `bundlebee` command environment.")
public class RunCommand extends BaseLabelEnricherCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(RunCommand.class.getName());

    public RunCommand(
            final Options options,
            KubeClient kube,
            ArchiveReader archives,
            io.yupiik.bundlebee.core.service.LabelSanitizerService labelSanitizerService,
            io.yupiik.bundlebee.core.service.VersioningService versioningService,
            io.yupiik.bundlebee.core.service.AlveolusHandler visitor) {
        super(labelSanitizerService, versioningService, visitor);
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.useChainInsteadOfAll = options.useChainInsteadOfAll();
        this.awaitTimeout = options.awaitTimeout();
        this.excludedDescriptors = options.excludedDescriptors();
        this.excludedLocations = options.excludedLocations();
        this.kube = kube;
        this.archives = archives;
    }

    private final String alveolus;
    private final String manifest;
    private final String from;
    private final boolean useChainInsteadOfAll;
    private final long awaitTimeout;
    private final String excludedDescriptors;
    private final String excludedLocations;
    private final KubeClient kube;
    private final ArchiveReader archives;

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String description() {
        return "Tries to run the recipe as plain forks on the host. In this mode, Bundlebee is used as the orchestrator. "
                + "It is highly recommended to only do that with application matching the `bundlebee` command environment.";
    }

    // $ mvnDebug bundlebee:run -Dbundlebee.version=1.0.14-SNAPSHOT -Dbundlebee.run.alveolus=all
    private void doRun(final List<Map<String, Object>> collector) {
        final var grouped = collector.stream().collect(groupingBy(it -> (String) it.get("kind")));
        final var configs = grouped.remove("ConfigMap");
        final var deployments = grouped.remove("Deployment");
        final var crons = grouped.remove("CronJob");
        final var jobs = grouped.remove("Job");
        final var services = grouped.remove("Service");
        final var persistentVolumes = grouped.remove("PersistentVolume");
        final var persistentVolumeClaims = grouped.remove("PersistentVolumeClaim");
        if (!grouped.isEmpty()) {
            throw new IllegalStateException("Run command is only able to handle ConfigMap and Deployment: " + grouped);
        }
        // TBD:
        // 1. extract the containers (using commons-compress in optional mode?)
        // 2. for each deployment/cronjob/job resolve the configs/volumes, mount what is needed + compute the
        // env+command
        // 3. execute/schedule what is needed for 2
        // 4. run 2 with healthcheck guard
        // 5. setup a global proxy to handle services
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean hidden() {
        // not yet implemented
        return true;
    }

    @Override
    public CompletionStage<?> execute() {
        return doExecute(from, manifest, alveolus, archives.newCache());
    }

    public CompletionStage<?> doExecute(
            final String from, final String manifest, final String alveolus, final ArchiveReader.Cache cache) {
        final var collector = new ArrayList<Map<String, Object>>();
        return visitor.findRootAlveoli(from, manifest, alveolus, null)
                .thenApply(alveoli -> alveoli.stream()
                        .map(it -> it.exclude(excludedLocations, excludedDescriptors))
                        .collect(toList()))
                .thenCompose(alveoli -> useChainInsteadOfAll
                        ? chain(
                                alveoli.stream()
                                        .map(it -> (Supplier<CompletionStage<?>>) () -> doExecute(cache, it, collector))
                                        .iterator(),
                                true)
                        : all(
                                        alveoli.stream()
                                                .map(it -> doExecute(cache, it, collector))
                                                .collect(toList()),
                                        toList(),
                                        true)
                                .thenApply(ignored -> null))
                .thenRun(() -> doRun(collector));
    }

    public CompletionStage<?> doExecute(
            final ArchiveReader.Cache cache,
            final AlveolusHandler.ManifestAndAlveolus it,
            final List<Map<String, Object>> collector) {
        return visitor.executeOnceOnAlveolus(
                "Running",
                it.getManifest(),
                it.getAlveolus(),
                null,
                (ctx, desc) -> kube.forDescriptor("Running", desc.getContent(), desc.getExtension(), json -> {
                    synchronized (collector) {
                        collector.add(json);
                    }
                    System.out.println(json);
                    return completedFuture(json);
                }),
                cache,
                desc -> completedFuture(null),
                "ran",
                null);
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.run.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.run.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to deploy (a file path or inline). This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.run.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.run.useChainInsteadOfAll",
                    defaultValue = "false",
                    documentation =
                            "If `true`, each descriptor installation awaits previous ones instead of being concurrent. "
                                    + "Enable an easier debugging for errors.")
            boolean useChainInsteadOfAll,

            @Property(
                    value = "bundlebee.run.descriptorAwaitTimeout",
                    defaultValue = "60000L",
                    documentation = "For descriptors with `await` = `true` the max duration the test can last.")
            long awaitTimeout,

            @Property(
                    value = "bundlebee.run.excludedDescriptors",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. "
                                    + "Note that using this setting, location is set to `*` so only the name is matched.")
            String excludedDescriptors,

            @Property(
                    value = "bundlebee.run.excludedLocations",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
            String excludedLocations) {}
}
