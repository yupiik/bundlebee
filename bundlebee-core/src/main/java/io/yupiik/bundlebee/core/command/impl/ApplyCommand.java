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
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.ConditionAwaiter;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static io.yupiik.bundlebee.core.lang.CompletionFutures.chain;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class ApplyCommand extends BaseLabelEnricherCommand implements CompletingExecutable {
    @Inject
    @Description("Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.apply.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to deploy (a file path or inline). This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.apply.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.apply.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("If `true`, a `bundlebee.timestamp` label will be injected into the descritors with current date before applying the descriptor.")
    @ConfigProperty(name = "bundlebee.apply.injectTimestamp", defaultValue = "true")
    private boolean injectTimestamp;

    @Inject
    @Description("" +
            "If `true`, `bundlebee.*` labels will be injected into the descriptors. " +
            "This is required for rollback command to work.")
    @ConfigProperty(name = "bundlebee.apply.injectBundleBeeMetadata", defaultValue = "true")
    private boolean injectBundleBeeMetadata;

    @Inject
    @Description("" +
            "If `true`, each descriptor installation awaits previous ones instead of being concurrent. " +
            "Enable an easier debugging for errors.")
    @ConfigProperty(name = "bundlebee.apply.useChainInsteadOfAll", defaultValue = "false")
    private boolean useChainInsteadOfAll;

    @Inject
    @Description("" +
            "For descriptors with `await` = `true` the max duration the test can last.")
    @ConfigProperty(name = "bundlebee.apply.descriptorAwaitTimeout", defaultValue = "60000")
    private long awaitTimeout;

    @Inject
    @Description("" +
            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. " +
            "Note that using this setting, location is set to `*` so only the name is matched.")
    @ConfigProperty(name = "bundlebee.apply.excludedDescriptors", defaultValue = "none")
    private String excludedDescriptors;

    @Inject
    @Description("" +
            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
    @ConfigProperty(name = "bundlebee.apply.excludedLocations", defaultValue = "none")
    private String excludedLocations;

    @Inject
    private KubeClient kube;

    @Inject
    private ArchiveReader archives;

    @Inject
    private ConditionAwaiter conditionAwaiter;

    @Inject
    @BundleBee
    private ScheduledExecutorService scheduledExecutorService;

    @Override
    public String name() {
        return "apply";
    }

    @Override
    public String description() {
        return "Apply/deploy a set of descriptors from a root one.";
    }

    @Override
    public CompletionStage<?> execute() {
        return internalApply(from, manifest, alveolus, injectTimestamp, injectBundleBeeMetadata, archives.newCache());
    }

    public CompletionStage<?> internalApply(final String from, final String manifest, final String alveolus,
                                            final boolean injectTimestamp, final boolean injectBundleBeeMetadata,
                                            final ArchiveReader.Cache cache) {
        return visitor
                .findRootAlveoli(from, manifest, alveolus)
                .thenApply(alveoli -> alveoli.stream().map(it -> it.exclude(excludedLocations, excludedDescriptors)).collect(toList()))
                .thenCompose(alveoli -> useChainInsteadOfAll ?
                        chain(alveoli.stream()
                                .map(it -> (Supplier<CompletionStage<?>>) () -> doApply(injectTimestamp, injectBundleBeeMetadata, cache, it))
                                .iterator(), true) :
                        all(
                                alveoli.stream()
                                        .map(it -> doApply(injectTimestamp, injectBundleBeeMetadata, cache, it))
                                        .collect(toList()), toList(),
                                true)
                                .thenApply(ignored -> null));
    }

    public CompletionStage<?> doApply(final boolean injectTimestamp, final boolean injectBundleBeeMetadata,
                                      final ArchiveReader.Cache cache, final AlveolusHandler.ManifestAndAlveolus it) {
        final var labels = createLabels(it.getAlveolus(), injectTimestamp, injectBundleBeeMetadata);
        return visitor.executeOnceOnAlveolus(
                "Deploying", it.getManifest(), it.getAlveolus(), null,
                (ctx, desc) -> kube.apply(desc.getContent(), desc.getExtension(), labels),
                cache,
                desc -> conditionAwaiter.await(name(), desc, scheduledExecutorService, awaitTimeout),
                "deployed");
    }
}
