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
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = "process",
        description = "Process all descriptors - as in an apply command - from a root descriptor. "
                + "If `output` is set, it dumps the descriptors in this directory. "
                + "Don\'t forget to set `--kubeconfig explicit` to ignore kube setup.")
public class ProcessCommand extends BaseLabelEnricherCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(ProcessCommand.class.getName());

    public ProcessCommand(
            final Options options,
            JsonMapper jsonb,
            KubeClient kube,
            ArchiveReader archives,
            io.yupiik.bundlebee.core.service.LabelSanitizerService labelSanitizerService,
            io.yupiik.bundlebee.core.service.VersioningService versioningService,
            io.yupiik.bundlebee.core.service.AlveolusHandler visitor) {
        super(labelSanitizerService, versioningService, visitor);
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.injectTimestamp = options.injectTimestamp();
        this.injectBundleBeeMetadata = options.injectBundleBeeMetadata();
        this.useChainInsteadOfAll = options.useChainInsteadOfAll();
        this.awaitTimeout = options.awaitTimeout();
        this.excludedDescriptors = options.excludedDescriptors();
        this.excludedLocations = options.excludedLocations();
        this.output = options.output();
        this.concat = options.concat();
        this.kube = kube;
        this.jsonb = jsonb;
        this.archives = archives;
    }

    private final String alveolus;
    private final String manifest;
    private final String from;
    private final boolean injectTimestamp;
    private final boolean injectBundleBeeMetadata;
    private final boolean useChainInsteadOfAll;
    private final long awaitTimeout;
    private final String excludedDescriptors;
    private final String excludedLocations;
    private final String output;
    private final boolean concat;
    private final KubeClient kube;
    private final JsonMapper jsonb;
    private final ArchiveReader archives;

    @Override
    public String name() {
        return "process";
    }

    @Override
    public String description() {
        return "Process all descriptors - as in an apply command - from a root descriptor. "
                + "If `output` is set, it dumps the descriptors in this directory. "
                + "Don\'t forget to set `--kubeconfig explicit` to ignore kube setup.";
    }

    @Override
    public CompletionStage<?> execute() {
        return doExecute(from, manifest, alveolus, injectTimestamp, injectBundleBeeMetadata, archives.newCache());
    }

    public CompletionStage<?> doExecute(
            final String from,
            final String manifest,
            final String alveolus,
            final boolean injectTimestamp,
            final boolean injectBundleBeeMetadata,
            final ArchiveReader.Cache cache) {
        final var out = output == null || UNSET.equals(output) || "stdout".equals(output) ? null : Path.of(output);
        final var collector = new ArrayList<String>();
        final BiConsumer<String, Map<String, Object>> onDescriptor = out == null
                ? ("stdout".equals(output)
                        ? (name, content) -> System.out.println(jsonb.toString(content))
                        : (name, content) -> log.info(() -> name + ":\n" + format(content)))
                : (name, content) -> {
                    final var formatted = format(content);
                    if (concat) {
                        synchronized (collector) {
                            collector.add(formatted);
                        }
                        return;
                    }
                    final var target = out.resolve(name);
                    log.info(() -> "Dumping \'" + target + "\'");
                    try {
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.writeString(target, formatted);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                };
        return visitor.findRootAlveoli(from, manifest, alveolus, null)
                .thenApply(alveoli -> alveoli.stream()
                        .map(it -> it.exclude(excludedLocations, excludedDescriptors))
                        .collect(toList()))
                .thenCompose(alveoli -> useChainInsteadOfAll
                        ? chain(
                                alveoli.stream()
                                        .map(it -> (Supplier<CompletionStage<?>>) () -> doExecute(
                                                injectTimestamp, injectBundleBeeMetadata, cache, it, onDescriptor))
                                        .iterator(),
                                true)
                        : all(
                                        alveoli.stream()
                                                .map(it -> doExecute(
                                                        injectTimestamp,
                                                        injectBundleBeeMetadata,
                                                        cache,
                                                        it,
                                                        onDescriptor))
                                                .collect(toList()),
                                        toList(),
                                        true)
                                .thenApply(ignored -> null))
                .thenApply(it -> {
                    if (out != null && concat) {
                        try {
                            if (out.getParent() != null) {
                                Files.createDirectories(out.getParent());
                            }
                            Files.writeString(out, String.join("\n---\n", collector));
                        } catch (final IOException ioe) {
                            throw new IllegalStateException(ioe);
                        }
                    }
                    return it;
                });
    }

    public CompletionStage<?> doExecute(
            final boolean injectTimestamp,
            final boolean injectBundleBeeMetadata,
            final ArchiveReader.Cache cache,
            final AlveolusHandler.ManifestAndAlveolus it,
            final BiConsumer<String, Map<String, Object>> onDescriptor) {
        final var labels = createLabels(it.getAlveolus(), injectTimestamp, injectBundleBeeMetadata);
        return visitor.executeOnceOnAlveolus(
                "Processing",
                it.getManifest(),
                it.getAlveolus(),
                null,
                (ctx, desc) -> kube.forDescriptor("Processing", desc.getContent(), desc.getExtension(), json -> {
                    final var processed = labels.isEmpty() ? json : kube.injectMetadata(json, labels);
                    onDescriptor.accept(desc.getConfiguration().name(), processed);
                    return completedFuture(processed);
                }),
                cache,
                desc -> completedFuture(null),
                "processed",
                null);
    }

    private String format(final Map<String, Object> content) {
        return new io.yupiik.fusion.json.pretty.PrettyJsonMapper(jsonb).toString(content);
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.process.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.process.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to deploy (a file path or inline). This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.process.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.process.injectTimestamp",
                    defaultValue = "true",
                    documentation =
                            "If `true`, a `bundlebee.timestamp` label will be injected into the descritors with current date before applying the descriptor.")
            boolean injectTimestamp,

            @Property(
                    value = "bundlebee.process.injectBundleBeeMetadata",
                    defaultValue = "true",
                    documentation = "If `true`, `bundlebee.*` labels will be injected into the descriptors. "
                            + "This is required for rollback command to work.")
            boolean injectBundleBeeMetadata,

            @Property(
                    value = "bundlebee.process.useChainInsteadOfAll",
                    defaultValue = "false",
                    documentation =
                            "If `true`, each descriptor installation awaits previous ones instead of being concurrent. "
                                    + "Enable an easier debugging for errors.")
            boolean useChainInsteadOfAll,

            @Property(
                    value = "bundlebee.process.descriptorAwaitTimeout",
                    defaultValue = "60000L",
                    documentation = "For descriptors with `await` = `true` the max duration the test can last.")
            long awaitTimeout,

            @Property(
                    value = "bundlebee.process.excludedDescriptors",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. "
                                    + "Note that using this setting, location is set to `*` so only the name is matched.")
            String excludedDescriptors,

            @Property(
                    value = "bundlebee.process.excludedLocations",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
            String excludedLocations,

            @Property(
                    value = "bundlebee.process.output",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If set, represents where to dump processed descriptors. Note that `stdout` enables to log on `System.out` (java `stdout`) directly - useful for ArgoCD for example. In this case formatting and `concat` are skipped.")
            String output,

            @Property(
                    value = "bundlebee.process.concat",
                    defaultValue = "Boolean.parseBoolean(\"" + UNSET + "\")",
                    documentation = "Should all descriptors be concatenated in a single file - output.")
            boolean concat) {}
}
