/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.json.JsonReaderFactory;
import javax.json.JsonWriterFactory;
import javax.json.spi.JsonProvider;
import javax.json.stream.JsonGenerator;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static io.yupiik.bundlebee.core.lang.CompletionFutures.chain;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class ProcessCommand extends BaseLabelEnricherCommand implements CompletingExecutable {
    @Inject
    @Description("Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.process.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to deploy (a file path or inline). This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.process.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.process.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("If `true`, a `bundlebee.timestamp` label will be injected into the descritors with current date before applying the descriptor.")
    @ConfigProperty(name = "bundlebee.process.injectTimestamp", defaultValue = "true")
    private boolean injectTimestamp;

    @Inject
    @Description("" +
            "If `true`, `bundlebee.*` labels will be injected into the descriptors. " +
            "This is required for rollback command to work.")
    @ConfigProperty(name = "bundlebee.process.injectBundleBeeMetadata", defaultValue = "true")
    private boolean injectBundleBeeMetadata;

    @Inject
    @Description("" +
            "If `true`, each descriptor installation awaits previous ones instead of being concurrent. " +
            "Enable an easier debugging for errors.")
    @ConfigProperty(name = "bundlebee.process.useChainInsteadOfAll", defaultValue = "false")
    private boolean useChainInsteadOfAll;

    @Inject
    @Description("" +
            "For descriptors with `await` = `true` the max duration the test can last.")
    @ConfigProperty(name = "bundlebee.process.descriptorAwaitTimeout", defaultValue = "60000")
    private long awaitTimeout;

    @Inject
    @Description("" +
            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. " +
            "Note that using this setting, location is set to `*` so only the name is matched.")
    @ConfigProperty(name = "bundlebee.process.excludedDescriptors", defaultValue = "none")
    private String excludedDescriptors;

    @Inject
    @Description("" +
            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
    @ConfigProperty(name = "bundlebee.process.excludedLocations", defaultValue = "none")
    private String excludedLocations;

    @Inject
    @Description("If set, represents where to dump processed descriptors.")
    @ConfigProperty(name = "bundlebee.process.output", defaultValue = UNSET)
    private String output;

    @Inject
    private KubeClient kube;

    @Inject
    private ArchiveReader archives;

    @Inject
    @BundleBee
    private JsonProvider provider;

    @Override
    public String name() {
        return "process";
    }

    @Override
    public String description() {
        return "Process all descriptors - as in an apply command - from a root descriptor. " +
                "If `output` is set, it dumps the descriptors in this directory. " +
                "Don't forget to set `--kubeconfig explicit` to ignore kube setup.";
    }

    @Override
    public CompletionStage<?> execute() {
        return doExecute(from, manifest, alveolus, injectTimestamp, injectBundleBeeMetadata, archives.newCache());
    }

    public CompletionStage<?> doExecute(final String from, final String manifest, final String alveolus,
                                        final boolean injectTimestamp, final boolean injectBundleBeeMetadata,
                                        final ArchiveReader.Cache cache) {
        final var out = output == null || UNSET.equals(output) ? null : Path.of(output);
        final var jsonReaderFactory = provider.createReaderFactory(Map.of());
        final var jsonWriterFactory = provider.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
        final BiConsumer<String, JsonObject> onDescriptor = out == null ?
                (name, content) -> log.info(() -> name + ":\n" + format(content, jsonReaderFactory, jsonWriterFactory)) :
                (name, content) -> {
                    final var target = out.resolve(name);
                    log.info(() -> "Dumping '" + target + "'");
                    try {
                        Files.writeString(target, format(content, jsonReaderFactory, jsonWriterFactory));
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                };
        return visitor
                .findRootAlveoli(from, manifest, alveolus)
                .thenApply(alveoli -> alveoli.stream().map(it -> it.exclude(excludedLocations, excludedDescriptors)).collect(toList()))
                .thenCompose(alveoli -> useChainInsteadOfAll ?
                        chain(alveoli.stream()
                                .map(it -> (Supplier<CompletionStage<?>>) () -> doExecute(injectTimestamp, injectBundleBeeMetadata, cache, it, onDescriptor))
                                .iterator(), true) :
                        all(
                                alveoli.stream()
                                        .map(it -> doExecute(injectTimestamp, injectBundleBeeMetadata, cache, it, onDescriptor))
                                        .collect(toList()), toList(),
                                true)
                                .thenApply(ignored -> null));
    }

    public CompletionStage<?> doExecute(final boolean injectTimestamp, final boolean injectBundleBeeMetadata,
                                        final ArchiveReader.Cache cache, final AlveolusHandler.ManifestAndAlveolus it,
                                        final BiConsumer<String, JsonObject> onDescriptor) {
        final var labels = createLabels(it.getAlveolus(), injectTimestamp, injectBundleBeeMetadata);
        return visitor.executeOnceOnAlveolus(
                "Processing", it.getManifest(), it.getAlveolus(), null,
                (ctx, desc) -> kube.forDescriptor("Processing", desc.getContent(), desc.getExtension(), json -> {
                    final var processed = labels.isEmpty() ? json : kube.injectMetadata(json, labels);
                    onDescriptor.accept(desc.getConfiguration().getName(), processed);
                    return completedFuture(processed);
                }),
                cache,
                desc -> completedFuture(null), "processed");
    }

    private String format(final JsonObject content,
                          final JsonReaderFactory jsonReaderFactory,
                          final JsonWriterFactory jsonWriterFactory) {
        final var out = new StringWriter();
        try (final var reader = jsonReaderFactory.createReader(new StringReader(content.toString()));
             final var writer = jsonWriterFactory.createWriter(out)) {
            writer.write(reader.read());
        }
        return out.toString();
    }
}
