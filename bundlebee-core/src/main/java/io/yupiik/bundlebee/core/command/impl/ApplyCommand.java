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
import static io.yupiik.bundlebee.lang.CompletionFutures.handled;
import static java.util.Locale.ROOT;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.descriptor.State;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.ConditionAwaiter;
import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.logging.Logger;

@DefaultScoped
@Command(name = "apply", description = "Apply/deploy a set of descriptors from a root one.")
public class ApplyCommand extends BaseLabelEnricherCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(ApplyCommand.class.getName());

    public ApplyCommand(
            final Options options,
            final KubeClient kube,
            final KubernetesClient api,
            final ArchiveReader archives,
            final ConditionAwaiter conditionAwaiter,
            final ScheduledExecutorService scheduledExecutorService,
            final JsonMapper jsonb,
            final io.yupiik.bundlebee.core.service.LabelSanitizerService labelSanitizerService,
            final io.yupiik.bundlebee.core.service.VersioningService versioningService,
            final AlveolusHandler visitor,
            final NamespaceProvider namespaceProvider) {
        super(labelSanitizerService, versioningService, visitor);
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.injectTimestamp = options.injectTimestamp();
        this.injectBundleBeeMetadata = options.injectBundleBeeMetadata();
        this.useChainInsteadOfAll = options.useChainInsteadOfAll();
        this.skipGet = options.skipGet();
        this.trackState = options.trackState();
        this.skipStaledResourceDeletion = options.skipStaledResourceDeletion();
        this.awaitTimeout = options.awaitTimeout();
        this.excludedDescriptors = options.excludedDescriptors();
        this.excludedLocations = options.excludedLocations();
        this.kube = kube;
        this.api = api;
        this.namespaceProvider = namespaceProvider;
        this.archives = archives;
        this.conditionAwaiter = conditionAwaiter;
        this.scheduledExecutorService = scheduledExecutorService;
        this.jsonb = jsonb;
    }

    private final String alveolus;
    private final String manifest;
    private final String from;
    private final boolean injectTimestamp;
    private final boolean injectBundleBeeMetadata;
    private final boolean useChainInsteadOfAll;
    private final boolean skipGet;
    private final boolean trackState;
    private final boolean skipStaledResourceDeletion;
    private final long awaitTimeout;
    private final String excludedDescriptors;
    private final String excludedLocations;
    private final KubeClient kube;
    private final KubernetesClient api;
    private final NamespaceProvider namespaceProvider;
    private final ArchiveReader archives;
    private final ConditionAwaiter conditionAwaiter;
    private final ScheduledExecutorService scheduledExecutorService;
    private final JsonMapper jsonb;

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
        return execute(null);
    }

    public CompletionStage<?> execute(final String executionId) {
        return internalApply(
                from, manifest, alveolus, injectTimestamp, injectBundleBeeMetadata, archives.newCache(), executionId);
    }

    public CompletionStage<?> internalApply(
            final String from,
            final String manifest,
            final String alveolus,
            final boolean injectTimestamp,
            final boolean injectBundleBeeMetadata,
            final ArchiveReader.Cache cache) {
        return internalApply(from, manifest, alveolus, injectTimestamp, injectBundleBeeMetadata, cache, null);
    }

    private CompletionStage<?> internalApply(
            final String from,
            final String manifest,
            final String alveolus,
            final boolean injectTimestamp,
            final boolean injectBundleBeeMetadata,
            final ArchiveReader.Cache cache,
            final String executionId) {
        final Collection<State.Resource> state =
                trackState && !"auto".equals(alveolus) ? new ConcurrentLinkedQueue<>() : null;
        final var stateName = state == null ? null : alveolus + "-bbs";
        return (state == null ? completedFuture(new State(1, List.of())) : loadState(stateName))
                .thenCompose(previous -> visitor.findRootAlveoli(from, manifest, alveolus, executionId)
                        .thenApply(alveoli -> alveoli.stream()
                                .map(it -> it.exclude(excludedLocations, excludedDescriptors))
                                .collect(toList()))
                        .thenCompose(alveoli -> useChainInsteadOfAll
                                ? chain(
                                                alveoli.stream()
                                                        .map(it -> (Supplier<CompletionStage<?>>) () -> doApply(
                                                                injectTimestamp,
                                                                injectBundleBeeMetadata,
                                                                cache,
                                                                it,
                                                                state,
                                                                executionId))
                                                        .iterator(),
                                                true)
                                        .thenApply(ignored -> (Object) Boolean.TRUE)
                                : all(
                                                alveoli.stream()
                                                        .map(it -> handled(() -> doApply(
                                                                injectTimestamp,
                                                                injectBundleBeeMetadata,
                                                                cache,
                                                                it,
                                                                state,
                                                                executionId)))
                                                        .collect(toList()),
                                                toList(),
                                                true)
                                        .thenApply(ignored -> (Object) Boolean.TRUE))
                        .handle((value, error) -> new Result<>(value, error))
                        .thenCompose(outcome -> state == null
                                ? report(outcome.error)
                                : finishState(stateName, previous, state, outcome.error)));
    }

    private CompletionStage<State> loadState(final String name) {
        return kube.getResource(Map.of(
                        "apiVersion",
                        "v1",
                        "kind",
                        "Secret",
                        "metadata",
                        Map.of("namespace", namespaceProvider.namespace(), "name", name)))
                .thenApply(response -> {
                    if (response.statusCode() == 404) {
                        log.info("No previous state");
                        return new State(1, List.of());
                    }
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Can't load apply state: HTTP " + response.statusCode());
                    }
                    log.info("Using previous state");
                    final var secret = jsonb.fromString(StateSecret.class, response.body());
                    if (secret == null
                            || secret.data() == null
                            || !secret.data().containsKey("state")) {
                        return new State(1, List.of());
                    }
                    final var previous = jsonb.fromString(
                            State.class,
                            new String(Base64.getDecoder().decode(secret.data().get("state")), StandardCharsets.UTF_8));
                    if (previous == null || previous.version() != 1) {
                        throw new IllegalArgumentException("Unsupported apply state version");
                    }
                    return previous;
                });
    }

    private CompletionStage<Void> finishState(
            final String name,
            final State previous,
            final Collection<State.Resource> outcomes,
            final Throwable applyFailure) {
        final var resources = new LinkedHashMap<String, State.Resource>();
        previous.resources().stream()
                .filter(resource -> resource.path() != null)
                .forEach(resource -> resources.put(resource.path(), resource));
        final var attempted = outcomes.stream().map(State.Resource::path).collect(toSet());
        if (applyFailure == null) {
            resources.replaceAll((path, resource) -> attempted.contains(path)
                    ? resource
                    : new State.Resource(path, State.Status.PENDING_DELETION, null));
        }
        outcomes.forEach(resource -> resources.put(resource.path(), resource));
        final var checkpoint = new State(1, List.copyOf(resources.values()));
        if (applyFailure != null || skipStaledResourceDeletion) {
            return saveAndReport(name, checkpoint, applyFailure);
        }
        // Never forget the old inventory before destructive operations. A failed checkpoint aborts cleanup.
        return saveState(name, checkpoint).thenCompose(ignored -> {
            final var stale = checkpoint.resources().stream()
                    .filter(resource -> !attempted.contains(resource.path()))
                    .toList();
            if (stale.isEmpty()) {
                return completedFuture(null);
            }
            return all(stale.stream().map(this::cleanup).collect(toList()), toList(), true)
                    .thenCompose(results -> {
                        Throwable failure = null;
                        for (final var result : results) {
                            resources.remove(result.path());
                            if (result.resource() != null) {
                                resources.put(result.path(), result.resource());
                            }
                            if (result.error() != null) {
                                if (failure == null) {
                                    failure = result.error();
                                } else {
                                    failure.addSuppressed(result.error());
                                }
                            }
                        }
                        return saveAndReport(name, new State(1, List.copyOf(resources.values())), failure);
                    });
        });
    }

    private CompletionStage<Void> saveState(final String name, final State state) {
        final var secret = Map.<String, Object>of(
                "apiVersion",
                "v1",
                "kind",
                "Secret",
                "metadata",
                Map.of("namespace", namespaceProvider.namespace(), "name", name),
                "data",
                Map.of(
                        "state",
                        Base64.getEncoder().encodeToString(jsonb.toString(state).getBytes(StandardCharsets.UTF_8))));
        return handled(() -> kube.doApply(secret, secret, Map.of(), false)).thenApply(ignored -> null);
    }

    private CompletionStage<Void> saveAndReport(final String name, final State state, final Throwable failure) {
        return saveState(name, state)
                .handle((ignored, saveFailure) -> {
                    if (failure != null) {
                        if (saveFailure != null) {
                            failure.addSuppressed(saveFailure);
                        }
                        return failure;
                    }
                    return saveFailure;
                })
                .thenCompose(this::report);
    }

    private CompletionStage<Void> report(final Throwable failure) {
        return failure == null ? completedFuture(null) : CompletableFuture.failedFuture(failure);
    }

    private CompletionStage<Cleanup> cleanup(final State.Resource resource) {
        final URI target;
        try {
            target = cleanupTarget(resource.path());
        } catch (final RuntimeException failure) {
            return completedFuture(new Cleanup(
                    resource.path(), new State.Resource(resource.path(), State.Status.DELETE_FAILED, null), failure));
        }
        return handled(() -> kube.delete(target.toASCIIString(), null).thenCompose(response -> {
                    if (response.statusCode() == 404) {
                        return completedFuture(new Cleanup(resource.path(), null, null));
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return completedFuture(cleanupFailure(resource.path(), response.statusCode()));
                    }
                    // Kubernetes can acknowledge DELETE while finalizers still retain the resource.
                    return api.sendAsync(
                                    HttpRequest.newBuilder(target).GET().build(),
                                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                            .thenApply(confirmation -> {
                                if (confirmation.statusCode() == 404) {
                                    return new Cleanup(resource.path(), null, null);
                                }
                                if (confirmation.statusCode() == 200) {
                                    return new Cleanup(
                                            resource.path(),
                                            new State.Resource(
                                                    resource.path(),
                                                    State.Status.PENDING_DELETION,
                                                    response.statusCode()),
                                            null);
                                }
                                return cleanupFailure(resource.path(), confirmation.statusCode());
                            });
                }))
                .exceptionally(failure -> new Cleanup(
                        resource.path(),
                        new State.Resource(resource.path(), State.Status.DELETE_FAILED, null),
                        failure));
    }

    private Cleanup cleanupFailure(final String path, final int status) {
        return new Cleanup(
                path,
                new State.Resource(path, State.Status.DELETE_FAILED, status),
                new IllegalStateException("Apply state cleanup failed: HTTP " + status));
    }

    private URI cleanupTarget(final String path) {
        final var base = api.base();
        final var candidate = URI.create(path);
        final var target = candidate.isAbsolute() ? candidate : base.resolve(candidate);
        final var basePort =
                base.getPort() < 0 ? ("https".equalsIgnoreCase(base.getScheme()) ? 443 : 80) : base.getPort();
        final var targetPort =
                target.getPort() < 0 ? ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80) : target.getPort();
        final var prefix = base.getRawPath().endsWith("/") ? base.getRawPath() : base.getRawPath() + '/';
        if (target.getUserInfo() != null
                || target.getQuery() != null
                || target.getFragment() != null
                || !Objects.equals(base.getScheme(), target.getScheme())
                || base.getHost() == null
                || !base.getHost().equalsIgnoreCase(target.getHost())
                || basePort != targetPort
                || !target.getRawPath().startsWith(prefix)
                || !target.normalize().equals(target)
                || target.getRawPath().substring(prefix.length()).contains("%")
                || target.getRawPath().contains("\\")) {
            throw new IllegalArgumentException("Refusing apply state cleanup outside the configured Kubernetes API");
        }
        return target;
    }

    private record Cleanup(String path, State.Resource resource, Throwable error) {}

    public CompletionStage<?> doApply(
            final boolean injectTimestamp,
            final boolean injectBundleBeeMetadata,
            final ArchiveReader.Cache cache,
            final AlveolusHandler.ManifestAndAlveolus it,
            final Collection<State.Resource> state) {
        return doApply(injectTimestamp, injectBundleBeeMetadata, cache, it, state, null);
    }

    private CompletionStage<?> doApply(
            final boolean injectTimestamp,
            final boolean injectBundleBeeMetadata,
            final ArchiveReader.Cache cache,
            final AlveolusHandler.ManifestAndAlveolus it,
            final Collection<State.Resource> state,
            final String executionId) {
        final var labels = createLabels(it.getAlveolus(), injectTimestamp, injectBundleBeeMetadata);
        return visitor.executeOnceOnAlveolus(
                "Deploying",
                it.getManifest(),
                it.getAlveolus(),
                null,
                (ctx, desc) ->
                        kube.forDescriptorWithOriginal("Applying", desc.getContent(), desc.getExtension(), item -> {
                            if (state == null) {
                                return kube.doApply(item.getRaw(), item.getPrepared(), labels, skipGet)
                                        .thenApply(ignored -> (Object) Boolean.TRUE);
                            }
                            final var json = item.getPrepared();
                            final var metadata = json.get("metadata") instanceof Map<?, ?> value ? value : Map.of();
                            final var name = (String) metadata.get("name");
                            final var namespace = metadata.containsKey("namespace")
                                    ? (String) metadata.get("namespace")
                                    : namespaceProvider.namespace();
                            final var kindLowerCased = ((String) json.get("kind")).toLowerCase(ROOT) + 's';
                            final var path = kube.toBaseUri(json, kindLowerCased, namespace) + '/' + name;
                            try {
                                return kube.doApply(item.getRaw(), item.getPrepared(), labels, skipGet)
                                        .whenComplete((response, failure) -> state.add(new State.Resource(
                                                path, failure == null ? null : State.Status.APPLY_FAILED, null)))
                                        .thenApply(ignored -> (Object) Boolean.TRUE);
                            } catch (final RuntimeException failure) {
                                state.add(new State.Resource(path, State.Status.APPLY_FAILED, null));
                                return CompletableFuture.<Object>failedFuture(failure);
                            }
                        }),
                cache,
                desc -> conditionAwaiter.await(name(), desc, scheduledExecutorService, awaitTimeout),
                "deployed",
                executionId);
    }

    public CompletionStage<?> doApply(
            final boolean injectTimestamp,
            final boolean injectBundleBeeMetadata,
            final ArchiveReader.Cache cache,
            final AlveolusHandler.ManifestAndAlveolus it) {
        return doApply(injectTimestamp, injectBundleBeeMetadata, cache, it, null);
    }

    @JsonModel
    public record StateSecret(Map<String, String> data) {}

    private static class Result<R> {
        private final R value;
        private final Throwable error;

        public Result(final R value, final Throwable error) {
            this.value = value;
            this.error = error;
        }
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.apply.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.apply.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to deploy (a file path or inline). This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.apply.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.apply.injectTimestamp",
                    defaultValue = "true",
                    documentation =
                            "If `true`, a `bundlebee.timestamp` label will be injected into the descriptors with current date before applying the descriptor.")
            boolean injectTimestamp,

            @Property(
                    value = "bundlebee.apply.injectBundleBeeMetadata",
                    defaultValue = "true",
                    documentation = "If `true`, `bundlebee.*` labels will be injected into the descriptors. "
                            + "This is required for rollback command to work.")
            boolean injectBundleBeeMetadata,

            @Property(
                    value = "bundlebee.apply.useChainInsteadOfAll",
                    defaultValue = "false",
                    documentation =
                            "If `true`, each descriptor installation awaits previous ones instead of being concurrent. "
                                    + "Enable an easier debugging for errors.")
            boolean useChainInsteadOfAll,

            @Property(
                    value = "bundlebee.apply.skipGet",
                    defaultValue = "false",
                    documentation =
                            "If `true`, the alveolus is installed without checking it already exists (assuming it doesn't) so some merge or sanitization logic is bypassed.")
            boolean skipGet,

            @Property(
                    value = "bundlebee.apply.trackState",
                    defaultValue = "false",
                    documentation =
                            "If `true`, a secret named based on the alveolus name (`$name-bbs`) is stored and is used by the client to track the internal state of the installation. "
                                    + "Note that it requires the client to have the permission to create a `Secret` even if the alveolus doesn't have any. "
                                    + "Note that it doesn't work with `alveolus=auto` mode. "
                                    + "The secret is stored in the globally configured namespace (default one if you do use a `kubeconfig` file).")
            boolean trackState,

            @Property(
                    value = "bundlebee.apply.skipStaledResourceDeletion",
                    defaultValue = "false",
                    documentation =
                            "If `true` and `trackState` is `true`, staled resources are not deleted but just logged.")
            boolean skipStaledResourceDeletion,

            @Property(
                    value = "bundlebee.apply.descriptorAwaitTimeout",
                    defaultValue = "60000L",
                    documentation = "For descriptors with `await` = `true` the max duration the test can last.")
            long awaitTimeout,

            @Property(
                    value = "bundlebee.apply.excludedDescriptors",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude descriptors from the command line. `none` to ignore. Value is comma separated. "
                                    + "Note that using this setting, location is set to `*` so only the name is matched.")
            String excludedDescriptors,

            @Property(
                    value = "bundlebee.apply.excludedLocations",
                    defaultValue = "\"none\"",
                    documentation =
                            "Enables to exclude locations (descriptor is set to `*`) from the command line. `none` to ignore. Value is comma separated.")
            String excludedLocations) {}
}
