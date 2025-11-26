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

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.State;
import io.yupiik.bundlebee.core.kube.HttpKubeClient;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.ConditionAwaiter;
import lombok.AllArgsConstructor;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import javax.json.spi.JsonProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static io.yupiik.bundlebee.core.lang.CompletionFutures.chain;
import static java.util.Locale.ROOT;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

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
    @Description("If `true`, a `bundlebee.timestamp` label will be injected into the descriptors with current date before applying the descriptor.")
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
    @Description("If `true`, the alveolus is installed without checking it already exists (assuming it doesn't) so some merge or sanitization logic is bypassed.")
    @ConfigProperty(name = "bundlebee.apply.skipGet", defaultValue = "false")
    private boolean skipGet;

    @Inject
    @Description("If `true`, a secret named based on the alveolus name (`$name-bbs`) is stored and is used by the client to track the internal state of the installation. " +
            "Note that it requires the client to have the permission to create a `Secret` even if the alveolus doesn't have any. " +
            "Note that it doesn't work with `alveolus=auto` mode. " +
            "The secret is stored in the globally configured namespace (default one if you do use a `kubeconfig` file).")
    @ConfigProperty(name = "bundlebee.apply.trackState", defaultValue = "false")
    private boolean trackState;

    @Inject
    @Description("If `true` and `trackState` is `true`, staled resources are not deleted but just logged.")
    @ConfigProperty(name = "bundlebee.apply.skipStaledResourceDeletion", defaultValue = "false")
    private boolean skipStaledResourceDeletion;

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
    private HttpKubeClient api;

    @Inject
    private ArchiveReader archives;

    @Inject
    private ConditionAwaiter conditionAwaiter;

    @Inject
    @BundleBee
    private ScheduledExecutorService scheduledExecutorService;

    @Inject
    @BundleBee
    private JsonProvider json;

    @Inject
    @BundleBee
    private Jsonb jsonb;

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
        final var state = trackState && !"auto".equals(alveolus) ? new State() : null;
        final var stateName = state == null ? null : (alveolus + "-bbs");

        CompletionStage<State> result = completedFuture(new State());
        if (state != null) {
            result = result.thenCompose(i -> kube
                    .getResource(json.createObjectBuilder()
                            .add("apiVersion", "v1")
                            .add("kind", "Secret")
                            .add("metadata", json.createObjectBuilder()
                                    .add("namespace", api.getNamespace())
                                    .add("name", stateName))
                            .build())
                    .thenApply(r -> {
                        if (r.statusCode() != 200) {
                            log.info("No previous state");
                            return i;
                        }

                        log.info("Using previous state");
                        try {
                            final var data = jsonb.fromJson(r.body(), JsonObject.class);
                            if (!data.containsKey("state")) {
                                return i;
                            }
                            final var stateValue = Base64.getDecoder().decode(data.getString("state"));
                            return jsonb.fromJson(new String(stateValue, StandardCharsets.UTF_8), State.class);
                        } catch (final RuntimeException re) {
                            log.log(SEVERE, re, () -> "Can't read previous state: " + re.getMessage() + ", ignoring");
                            return i;
                        }
                    }));
        }
        return result.thenCompose(originalState -> visitor
                .findRootAlveoli(from, manifest, alveolus, null)
                .thenApply(alveoli -> alveoli.stream().map(it -> it.exclude(excludedLocations, excludedDescriptors)).collect(toList()))
                .thenCompose(alveoli -> useChainInsteadOfAll ?
                        chain(alveoli.stream()
                                .map(it -> (Supplier<CompletionStage<?>>) () -> doApply(
                                        injectTimestamp, injectBundleBeeMetadata, cache, it, state))
                                .iterator(), true) :
                        all(
                                alveoli.stream()
                                        .map(it -> doApply(injectTimestamp, injectBundleBeeMetadata, cache, it, state))
                                        .collect(toList()), toList(),
                                true)
                                .thenApply(ignored -> null))
                .thenApply(r -> new Result<>(r, null))
                .exceptionally(e -> new Result<>(null, e))
                .thenComposeAsync(r -> {
                    CompletionStage<Object> base = completedFuture(r.value);
                    if (state != null) {
                        if (r.error != null) {
                            // TODO: rollback what was applied, what if some data were created, do we want to loose them?
                            log.warning("Some error occurred, state didn't rolled back what was applied yet");
                        } else {
                            // save the secret
                            final var secret = json.createObjectBuilder()
                                    .add("apiVersion", "v1")
                                    .add("kind", "Secret")
                                    .add("metadata", json.createObjectBuilder()
                                            .add("namespace", api.getNamespace())
                                            .add("name", stateName))
                                    .add("data", json.createObjectBuilder()
                                            .add("state", Base64.getEncoder().encodeToString(jsonb.toJson(state).getBytes(StandardCharsets.UTF_8))))
                                    .build();
                            base = base.thenCompose(i -> kube.doApply(secret, secret, Map.of(), false)
                                    .thenApply(ig -> ig));

                            // diff if some resources were existing and are now no more there to drop them
                            if (originalState != null && originalState.getResources() != null) {
                                final var currentResources = state.getResources().stream().map(State.Resource::getPath).collect(toSet());
                                final var resourcesToDrop = originalState.getResources().stream()
                                        .map(State.Resource::getPath)
                                        .filter(Objects::nonNull)
                                        .filter(Predicate.not(currentResources::contains))
                                        .collect(toList());
                                if (!resourcesToDrop.isEmpty()) {
                                    if (skipStaledResourceDeletion) {
                                        log.info(() -> "Skipping staled resources deletion for: " + resourcesToDrop);
                                    } else {
                                        log.info(() -> "Detected staled resources, will delete them: " + resourcesToDrop);
                                        base = base.thenCompose(i -> all(
                                                resourcesToDrop.stream()
                                                        .map(rtd -> kube.delete(
                                                                URI.create("https://kubernetes.api").resolve(rtd).toASCIIString(),
                                                                null /* use default one */))
                                                        .collect(toList()),
                                                toList(),
                                                true)
                                                .thenApply(it -> it));
                                    }
                                }
                            }
                        }
                    }
                    if (r.error != null) {
                        return base.thenCompose(i -> {
                            final var oops = new CompletableFuture<>();
                            oops.completeExceptionally(r.error);
                            return oops;
                        });
                    }
                    return base.thenApply(i -> r.value);
                }));
    }

    public CompletionStage<?> doApply(final boolean injectTimestamp, final boolean injectBundleBeeMetadata,
                                      final ArchiveReader.Cache cache, final AlveolusHandler.ManifestAndAlveolus it,
                                      final State state) {
        final var labels = createLabels(it.getAlveolus(), injectTimestamp, injectBundleBeeMetadata);
        return visitor.executeOnceOnAlveolus(
                "Deploying", it.getManifest(), it.getAlveolus(), null,
                (ctx, desc) -> kube
                        .forDescriptorWithOriginal(
                                "Applying", desc.getContent(), desc.getExtension(),
                                item -> {
                                    if (state != null) {
                                        final var json = item.getPrepared();
                                        final var metadata = json.getJsonObject("metadata");
                                        final var name = metadata.getString("name");
                                        final var namespace = metadata.containsKey("namespace") ?
                                                metadata.getString("namespace") : api.getNamespace();
                                        final var kindLowerCased = json.getString("kind").toLowerCase(ROOT) + 's';
                                        state.getResources().add(new State.Resource(kube.toBaseUri(json, kindLowerCased, namespace) + '/' + name));
                                    }
                                    return kube.doApply(item.getRaw(), item.getPrepared(), labels, false);
                                }),
                cache,
                desc -> conditionAwaiter.await(name(), desc, scheduledExecutorService, awaitTimeout),
                "deployed", null);
    }

    public CompletionStage<?> doApply(final boolean injectTimestamp, final boolean injectBundleBeeMetadata,
                                      final ArchiveReader.Cache cache, final AlveolusHandler.ManifestAndAlveolus it) {
        return doApply(injectTimestamp, injectBundleBeeMetadata, cache, it, null);
    }

    @AllArgsConstructor
    private static class Result<R> {
        private final R value;
        private final Throwable error;
    }
}
