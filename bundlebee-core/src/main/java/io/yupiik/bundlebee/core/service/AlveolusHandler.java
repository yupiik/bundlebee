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
package io.yupiik.bundlebee.core.service;

import static io.yupiik.bundlebee.lang.CompletionFutures.all;
import static io.yupiik.bundlebee.lang.CompletionFutures.chain;
import static io.yupiik.bundlebee.lang.CompletionFutures.handled;
import static java.util.Collections.list;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.configuration.ThreadLocalConfigSource;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import io.yupiik.bundlebee.helm.HelmChartDownloader;
import io.yupiik.bundlebee.helm.HelmRenderer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.patch.GenericJsonPatch;
import io.yupiik.fusion.json.patch.JsonPatchOperation;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class AlveolusHandler {
    private final Logger log = Logger.getLogger(AlveolusHandler.class.getName());

    public AlveolusHandler(
            JsonMapper jsonb,
            ManifestReader manifestReader,
            Substitutor substitutor,
            Yaml2JsonConverter yaml2json,
            ArchiveReader archives,
            ConditionEvaluator conditionEvaluator,
            Configuration config,
            RequirementService requirementService,
            HelmRenderer helmRenderer,
            HelmChartDownloader helmChartDownloader,
            Emitter onPrepareDescriptorEvent,
            ThreadLocalConfigSource threadLocalConfigSource) {
        this.jsonb = jsonb;
        this.manifestReader = manifestReader;
        this.substitutor = substitutor;
        this.yaml2json = yaml2json;
        this.archives = archives;
        this.conditionEvaluator = conditionEvaluator;
        this.config = config;
        this.requirementService = requirementService;
        this.helmRenderer = helmRenderer;
        this.helmChartDownloader = helmChartDownloader;
        this.onPrepareDescriptorEvent = onPrepareDescriptorEvent;
        this.threadLocalConfigSource = threadLocalConfigSource;
    }

    private final JsonMapper jsonb;
    private final ManifestReader manifestReader;
    private final Substitutor substitutor;
    private final Yaml2JsonConverter yaml2json;
    private final ArchiveReader archives;
    private final ConditionEvaluator conditionEvaluator;
    private final Configuration config;
    private final RequirementService requirementService;
    private final HelmRenderer helmRenderer;
    private final HelmChartDownloader helmChartDownloader;
    private final Emitter onPrepareDescriptorEvent;
    private final ThreadLocalConfigSource threadLocalConfigSource;

    public CompletionStage<Manifest> findManifest(final String from, final String manifest, final String id) {
        if (!"skip".equals(manifest)) {
            return completedFuture(readManifest(manifest, id));
        }
        if (from == null || "auto".equals(from)) {
            // can't do
            return completedFuture(null);
        }
        return archives.newCache().loadArchive(from, id).thenApply(ArchiveReader.Archive::getManifest);
    }

    public CompletionStage<List<ManifestAndAlveolus>> findRootAlveoli(
            final String from, final String manifest, final String alveolus, final String id) {
        return doFindRootAlveoli(from, manifest, alveolus, id).thenApply(a -> {
            // only check root one since we assume it enforces dependencies ones if any +
            // we want to be able to bypass it if needed
            a.forEach(it -> requirementService.checkRequirements(it.getManifest()));
            return a;
        });
    }

    private CompletionStage<List<ManifestAndAlveolus>> doFindRootAlveoli(
            final String from, final String manifest, final String alveolus, final String id) {
        if (!"skip".equals(manifest)) {
            final var mf = readManifest(manifest, id);
            if (mf.alveoli() == null) {
                throw new IllegalArgumentException("No alveoli in manifest \'" + manifest + "\'");
            }
            final String matchedAlveolus;
            if (mf.alveoli().size() == 1 && "auto".equals(alveolus)) {
                matchedAlveolus = mf.alveoli().iterator().next().name();
            } else {
                matchedAlveolus = alveolus;
            }
            return mf.alveoli().stream()
                    .filter(it -> Objects.equals(matchedAlveolus, it.name()))
                    .findFirst()
                    .map(it -> new ManifestAndAlveolus(mf, it))
                    .map(List::of)
                    .map(CompletableFuture::completedFuture)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Didn\'t find alveolus \'" + matchedAlveolus + "\' in \'" + manifest + "\'"));
        }
        if ("auto".equals(alveolus)) {
            log.info("Auto scanning the classpath, this can be dangerous if you don\'t fully control your classpath, "
                    + "ensure to set a particular alveolus if you doubt about this behavior");
            return completedFuture(findManifestsFromClasspath(id)
                    .flatMap(m -> ofNullable(m.alveoli()).stream()
                            .flatMap(it -> it.stream().map(a -> new ManifestAndAlveolus(m, a))))
                    .collect(toList()));
        }
        if (from == null || "auto".equals(from)) {
            return completedFuture(List.of(findAlveolusInClasspath(alveolus, id)));
        }
        return findAlveolus(from, alveolus, archives.newCache(), id).thenApply(List::of);
    }

    public CompletionStage<?> executeOnceOnAlveolus(
            final String prefixOnVisitLog,
            final Manifest manifest,
            final Manifest.Alveolus alveolus,
            final Function<AlveolusContext, CompletionStage<?>> onAlveolusUser,
            final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
            final ArchiveReader.Cache cache,
            final Function<LoadedDescriptor, CompletionStage<Void>> awaiter,
            final String alreadyHandledMarker,
            final String id) {
        final var alreadyDone = new HashSet<String>();
        return executeOnAlveolus(
                prefixOnVisitLog,
                manifest,
                alveolus,
                onAlveolusUser,
                (ctx, desc) -> {
                    if (!alreadyDone.add(desc.getConfiguration().name() + '|' + desc.getContent())) {
                        log.info(() ->
                                desc.getConfiguration().name() + " already " + alreadyHandledMarker + ", skipping");
                        return completedFuture(false);
                    }
                    return onDescriptor.apply(ctx, desc);
                },
                cache,
                awaiter,
                id);
    }

    public CompletionStage<?> executeOnAlveolus(
            final String prefixOnVisitLog,
            final Manifest manifest,
            final Manifest.Alveolus alveolus,
            final Function<AlveolusContext, CompletionStage<?>> onAlveolusUser,
            final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
            final ArchiveReader.Cache cache,
            final Function<LoadedDescriptor, CompletionStage<Void>> awaiter,
            final String id) {
        final var ref = new AtomicReference<Function<AlveolusContext, CompletionStage<?>>>();
        final Function<AlveolusContext, CompletionStage<?>> internalOnAlveolus = ctx -> this.onAlveolus(
                prefixOnVisitLog,
                manifest,
                ctx.alveolus,
                ctx.patches,
                ctx.excludes,
                ctx.cache,
                ref.get(),
                onDescriptor,
                awaiter,
                ctx.placeholders,
                id);
        final Function<AlveolusContext, CompletionStage<?>> combinedOnAlveolus = onAlveolusUser == null
                ? internalOnAlveolus
                : ctx -> onAlveolusUser.apply(ctx).thenCompose(i -> internalOnAlveolus.apply(ctx));
        ref.set(combinedOnAlveolus);
        return handled(() -> combinedOnAlveolus
                .apply(new AlveolusContext(manifest, alveolus, Map.of(), Map.of(), List.of(), cache, id))
                .thenApply(result -> (Object) result));
    }

    private Manifest readManifest(final String manifest, final String id) {
        if (manifest.startsWith("{")) {
            return manifestReader.readManifest(
                    null,
                    () -> new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)),
                    s -> {
                        throw new IllegalArgumentException(
                                "Relative references foriddden for in memory manifest.json: \'" + s + "\'");
                    },
                    id);
        }
        final var path = Paths.get(manifest);
        return manifestReader.readManifest(
                path.toAbsolutePath().getParent().getParent().normalize().toString(),
                () -> {
                    try {
                        return Files.newInputStream(path);
                    } catch (final IOException e) {
                        throw new IllegalArgumentException(e);
                    }
                },
                s -> resolveManifestReference(path, s),
                id);
    }

    private InputStream resolveManifestReference(final Path path, final String name) {
        try {
            final var abs = Path.of(name);
            return Files.newInputStream(
                    Files.exists(abs) ? abs : path.getParent().resolve(name));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ManifestAndAlveolus findAlveolusInClasspath(final String alveolus, final String id) {
        final var manifests = findManifestsFromClasspath(id).collect(toList());
        return manifests.stream()
                .flatMap(m -> ofNullable(m.alveoli()).stream()
                        .flatMap(a -> a.stream().map(it -> new ManifestAndAlveolus(m, it))))
                .filter(it -> alveolus.equals(it.getAlveolus().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No alveolus named \'" + alveolus + "\' found"));
    }

    public Stream<Manifest> findManifestsFromClasspath(final String id) {
        final var classLoader = Thread.currentThread().getContextClassLoader();
        try {
            return // materialize it to not leak manifest I/O outside of the method
            // materialize it to not leak manifest I/O outside of the method
            list(classLoader.getResources("bundlebee/manifest.json")).stream()
                    .map(manifest -> manifestReader.readManifest(
                            null,
                            () -> {
                                try {
                                    return manifest.openStream();
                                } catch (final IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            },
                            s -> s.startsWith("/")
                                    ? classLoader.getResourceAsStream(s)
                                    : classLoader.getResourceAsStream("bundlebee/" + s),
                            id))
                    .distinct()
                    .collect(toList())
                    .stream();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletionStage<?> onAlveolus(
            final String prefixOnVisitLog,
            final Manifest manifest,
            final Manifest.Alveolus from,
            final Map<Predicate<String>, Manifest.Patch> patches,
            final Collection<Manifest.DescriptorRef> excludes,
            final ArchiveReader.Cache cache,
            final Function<AlveolusContext, CompletionStage<?>> onAlveolus,
            final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
            final Function<LoadedDescriptor, CompletionStage<Void>> awaiter,
            final Map<String, String> placeholders,
            final String id) {
        if (prefixOnVisitLog != null) {
            log.info(() -> prefixOnVisitLog + " \'" + from.name() + "\'");
        }
        final var currentExcludes = from.excludedDescriptors() == null
                ? excludes
                : Stream.concat(
                                excludes.stream(),
                                from.excludedDescriptors() != null
                                        ? from.excludedDescriptors().stream()
                                        : Stream.empty())
                        .distinct()
                        .collect(toList());
        final var currentPatches =
                from.patches() == null || from.patches().isEmpty() ? patches : mergePatches(patches, from.patches());
        final var currentPlaceholders =
                from.placeholders() == null || from.placeholders().isEmpty()
                        ? placeholders
                        : mergePlaceholders(placeholders, from.placeholders());
        final var dependencies = ofNullable(from.dependencies()).orElseGet(List::of);
        final var dependenciesTasks = // note we can't filter it *here* even if all descriptors are excluded because it
                // can have depdencies
                dependencies.stream().map(it -> (Supplier<CompletionStage<?>>) () -> {
                    if (!conditionEvaluator.test(it.includeIf())) {
                        return completedFuture(null);
                    }
                    if (it.location() == null) {
                        if (manifest.alveoli() != null) {
                            // prefer in the same manifest first
                            final var found = manifest.alveoli().stream()
                                    .filter(a -> Objects.equals(it.name(), a.name()))
                                    .findFirst()
                                    .orElse(null);
                            if (found != null) {
                                return onAlveolus.apply(new AlveolusContext(
                                        manifest,
                                        found,
                                        currentPatches,
                                        currentPlaceholders,
                                        currentExcludes,
                                        cache,
                                        id));
                            }
                        }
                        final var alveolus = findAlveolusInClasspath(it.name(), id);
                        return onAlveolus.apply(new AlveolusContext(
                                alveolus.getManifest(),
                                alveolus.getAlveolus(),
                                currentPatches,
                                currentPlaceholders,
                                currentExcludes,
                                cache,
                                id));
                    }
                    return findAlveolus(it.location(), it.name(), cache, id)
                            .thenCompose(alveolus -> onAlveolus.apply(new AlveolusContext(
                                    manifest,
                                    alveolus.getAlveolus(),
                                    currentPatches,
                                    currentPlaceholders,
                                    currentExcludes,
                                    cache,
                                    id)));
                });
        if (from.chainDependencies()) {
            return chain(dependenciesTasks.iterator(), true)
                    .thenCompose(ready -> all(
                                    selectDescriptors(from, excludes).collect(toList()).stream()
                                            .map(desc -> handled(() -> findDescriptor(desc, cache, id)))
                                            .collect(toList()),
                                    toList(),
                                    true)
                            .thenCompose(descriptors -> afterDependencies(
                                    manifest,
                                    from,
                                    patches,
                                    excludes,
                                    cache,
                                    onDescriptor,
                                    awaiter,
                                    currentPlaceholders,
                                    currentPatches,
                                    descriptors,
                                    id)));
        }
        return all(
                        dependenciesTasks
                                .map(task -> handled(() -> task.get().thenApply(ignored -> 1)))
                                .collect(toList()),
                        counting(),
                        true)
                .thenCompose(ready -> all(
                                selectDescriptors(from, excludes).collect(toList()).stream()
                                        .map(desc -> handled(() -> findDescriptor(desc, cache, id)))
                                        .collect(toList()),
                                toList(),
                                true)
                        .thenCompose(descriptors -> afterDependencies(
                                manifest,
                                from,
                                patches,
                                excludes,
                                cache,
                                onDescriptor,
                                awaiter,
                                currentPlaceholders,
                                currentPatches,
                                descriptors,
                                id)));
    }

    private CompletionStage<?> afterDependencies(
            final Manifest manifest,
            final Manifest.Alveolus from,
            final Map<Predicate<String>, Manifest.Patch> patches,
            final Collection<Manifest.DescriptorRef> excludes,
            final ArchiveReader.Cache cache,
            final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
            final Function<LoadedDescriptor, CompletionStage<Void>> awaiter,
            final Map<String, String> placeholders,
            final Map<Predicate<String>, Manifest.Patch> currentPatches,
            final List<LoadedDescriptor> descriptors,
            final String id) {
        log.finest(() -> "Applying " + descriptors);
        final Collection<Collection<LoadedDescriptor>> rankedDescriptors = rankDescriptors(descriptors);
        CompletionStage<?> promise = completedFuture(true);
        for (final var descs : rankedDescriptors) {
            promise = promise.thenCompose(ignored -> {
                if (awaiter == null) {
                    return prepareDescriptors(
                            manifest,
                            from,
                            patches,
                            placeholders,
                            excludes,
                            cache,
                            onDescriptor,
                            currentPatches,
                            descs,
                            id);
                }
                final var filteredDescs = new ArrayList<LoadedDescriptor>();
                final var descriptorApply = prepareDescriptors(
                        manifest,
                        from,
                        patches,
                        placeholders,
                        excludes,
                        cache,
                        (ctx, desc) -> {
                            synchronized (filteredDescs) {
                                filteredDescs.add(desc);
                            }
                            return onDescriptor.apply(ctx, desc);
                        },
                        currentPatches,
                        descs,
                        id);
                return descriptorApply.thenCompose(result -> {
                    final Collection<CompletionStage<Void>> awaiters = filteredDescs.stream()
                            .map(desc -> handled(() -> awaiter.apply(desc)))
                            .collect(toList());
                    return all(awaiters, counting(), true).thenApply(it -> result);
                });
            });
        }
        return promise;
    }

    private Collection<Collection<LoadedDescriptor>> rankDescriptors(final List<LoadedDescriptor> descriptors) {
        final Collection<Collection<LoadedDescriptor>> rankedDescriptors = new ArrayList<>(1);
        Collection<LoadedDescriptor> current = new ArrayList<>();
        for (final LoadedDescriptor desc : descriptors) {
            current.add(desc);
            if (desc.getConfiguration().await()) {
                rankedDescriptors.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            rankedDescriptors.add(current);
        }
        return rankedDescriptors;
    }

    private CompletionStage<Long> prepareDescriptors(
            final Manifest manifest,
            final Manifest.Alveolus from,
            final Map<Predicate<String>, Manifest.Patch> patches,
            final Map<String, String> placeholders,
            final Collection<Manifest.DescriptorRef> excludes,
            final ArchiveReader.Cache cache,
            final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
            final Map<Predicate<String>, Manifest.Patch> currentPatches,
            final Collection<LoadedDescriptor> descs,
            final String id) {
        return all(
                descs.stream()
                        .map(it -> handled(() -> {
                            onPrepareDescriptorEvent.emit(new OnPrepareDescriptor(
                                    id, from.name(), it.getConfiguration().name(), it.getContent(), placeholders));
                            return onDescriptor.apply(
                                    new AlveolusContext(manifest, from, patches, placeholders, excludes, cache, id),
                                    prepare(from, it, currentPatches, placeholders, id));
                        }))
                        .collect(toList()),
                counting(),
                true);
    }

    private Stream<Manifest.Descriptor> selectDescriptors(
            final Manifest.Alveolus from, final Collection<Manifest.DescriptorRef> excludes) {
        return ofNullable(from.descriptors()).orElseGet(List::of).stream()
                .filter(desc -> conditionEvaluator.test(desc.includeIf())
                        && excludes.stream()
                                .noneMatch(
                                        d -> matches(d.location(), desc.location()) && matches(d.name(), desc.name())));
    }

    private boolean matches(final String expected, final String actual) {
        return Objects.equals(expected, actual) || expected == null || "*".equals(expected);
    }

    private CompletionStage<ManifestAndAlveolus> findAlveolus(
            final String from, final String alveolus, final ArchiveReader.Cache cache, final String id) {
        return cache.loadArchive(from, id)
                .thenApply(archive -> requireNonNull(
                                requireNonNull(archive.getManifest(), "No manifest found in " + from)
                                        .alveoli(),
                                "No alveolus in manifest of " + from)
                        .stream()
                        .filter(it -> Objects.equals(it.name(), alveolus))
                        .map(it -> it.descriptors() == null
                                ? it
                                : it.withDescriptors(it.descriptors().stream()
                                        .map(descriptor -> descriptor.location() == null
                                                ? descriptor.withLocation(from)
                                                : descriptor)
                                        .collect(toList())))
                        .findFirst()
                        .map(it -> new ManifestAndAlveolus(archive.getManifest(), it))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No alveolus \'" + alveolus + "\' found, available in \'" + from + "\': "
                                        + archive.getManifest().alveoli().stream()
                                                .map(Manifest.Alveolus::name)
                                                .collect(joining(",")))));
    }

    private CompletionStage<LoadedDescriptor> findDescriptor(
            final Manifest.Descriptor desc, final ArchiveReader.Cache cache, final String id) {
        final var type = ofNullable(desc.type()).orElse("kubernetes");
        if ("helm".equals(type)) {
            return handleHelmDescriptor(desc, id);
        }
        final var resource = String.join("/", "bundlebee", type, desc.name() + findExtension(desc.name(), type));
        return handled(() -> ofNullable(
                        Thread.currentThread().getContextClassLoader().getResource(resource))
                .map(url -> {
                    try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                        return new LoadedDescriptor(
                                desc,
                                reader.lines().collect(joining("\n")),
                                extractExtension(resource),
                                url.toExternalForm(),
                                resource);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(CompletableFuture::completedStage)
                .orElseGet(() -> {
                    if (desc.location() == null || desc.location().isBlank()) {
                        throw new IllegalArgumentException(
                                "No location for descriptor: >" + desc + "< so it will not be downloadable.");
                    }
                    return cache.loadArchive(desc.location(), id).thenApply(archive -> {
                        final var content = archive.getDescriptors().get(resource);
                        if (content == null) {
                            throw new IllegalStateException(
                                    "No descriptor \'" + resource + "\' found in \'" + desc.location() + "\'");
                        }
                        return new LoadedDescriptor(
                                desc,
                                content,
                                extractExtension(resource),
                                Files.isDirectory(archive.getLocation())
                                        ? archive.getLocation()
                                                .resolve(resource)
                                                .toUri()
                                                .toASCIIString()
                                        : archive.getLocation().toUri().toASCIIString() + "!" + resource,
                                resource);
                    });
                }));
    }

    private CompletionStage<LoadedDescriptor> handleHelmDescriptor(final Manifest.Descriptor desc, final String id) {
        final var helmConfig = desc.helm();
        if (helmConfig == null || helmConfig.chart() == null) {
            throw new IllegalArgumentException(
                    "Helm descriptor \'" + desc.name() + "\' requires a \'helm.chart\' path");
        }
        final var chart = helmConfig.chart();
        // Check if chart is a URI
        if (chart.startsWith("http://")
                || chart.startsWith("https://")
                || chart.startsWith("oci://")
                || chart.startsWith("file://")) {
            return helmChartDownloader
                    .resolve(chart, helmConfig.username(), helmConfig.password())
                    .thenCompose(chartPath -> renderHelm(desc, helmConfig, chartPath));
        }
        // Local path - resolve relative to working directory
        final var localPath = Path.of(chart);
        final var chartPath = localPath.isAbsolute()
                ? localPath
                : Path.of("").toAbsolutePath().resolve(chart);
        return renderHelm(desc, helmConfig, chartPath);
    }

    private CompletionStage<LoadedDescriptor> renderHelm(
            final Manifest.Descriptor desc, final Manifest.HelmConfig helmConfig, final Path chartPath) {
        return helmRenderer
                .render(
                        chartPath.toString(),
                        Map.of(),
                        helmConfig.releaseName(),
                        helmConfig.releaseNamespace(),
                        helmConfig.resolveDependencies(),
                        helmConfig.ignoredDescriptors())
                .thenApply(rendered -> {
                    final var content = String.join("\n---\n", rendered);
                    return new LoadedDescriptor(
                            desc, content, "yaml", chartPath.toUri().toASCIIString(), "helm:" + helmConfig.chart());
                });
    }

    private Map<Predicate<String>, Manifest.Patch> mergePatches(
            final Map<Predicate<String>, Manifest.Patch> patches, final List<Manifest.Patch> newPatches) {
        final var result = new HashMap<>(patches);
        newPatches.forEach(p -> result.put(toPredicate(p.descriptorName()), p));
        return result;
    }

    private Map<String, String> mergePlaceholders(
            final Map<String, String> current, final Map<String, String> newOnes) {
        if (newOnes == null || newOnes.isEmpty()) {
            return current;
        }
        if (current.isEmpty()) {
            return newOnes;
        }
        return Stream.concat(current.entrySet().stream(), newOnes.entrySet().stream())
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b));
    }

    private Predicate<String> toPredicate(final String descriptorName) {
        if (descriptorName.contains("*")) {
            return "*".equals(descriptorName)
                    ? s -> true
                    : Pattern.compile(descriptorName).asMatchPredicate();
        }
        if (descriptorName.startsWith("regex:")) {
            // other advanced regex, this prefix forces regex mode
            return Pattern.compile(descriptorName.substring("regex:".length())).asMatchPredicate();
        }
        return descriptorName::equals;
    }

    private LoadedDescriptor prepare(
            final Manifest.Alveolus alveolus,
            final LoadedDescriptor desc,
            final Map<Predicate<String>, Manifest.Patch> patches,
            final Map<String, String> placeholders,
            final String id) {
        return threadLocalConfigSource.withContext(placeholders, () -> doPrepare(alveolus, desc, patches, id));
    }

    private LoadedDescriptor doPrepare(
            final Manifest.Alveolus alveolus,
            final LoadedDescriptor desc,
            final Map<Predicate<String>, Manifest.Patch> patches,
            final String id) {
        var content = desc.getContent();
        final var descPatches = // extensions can be implicit so ensure we support both
                patches.entrySet().stream()
                        .filter(e -> e.getKey().test(desc.configuration.name())
                                || e.getKey().test(desc.configuration.name() + "." + desc.extension))
                        .map(Map.Entry::getValue)
                        .collect(toList());
        boolean alreadyInterpolated = false;
        if (!descPatches.isEmpty()) {
            for (final Manifest.Patch patch : descPatches) {
                if (patch.interpolate()) {
                    content = substitutor.replace(alveolus, desc, content, id);
                }
                if (patch.patch() == null) {
                    continue;
                }
                if (patch.includeIf() != null
                        && patch.includeIf().conditions() != null
                        && !patch.includeIf().conditions().isEmpty()
                        && !conditionEvaluator.test(patch.includeIf())) {
                    continue;
                }
                for (final var operation : patch.patch()) {
                    if (!(operation instanceof Map<?, ?> fields)
                            || !(fields.get("op") instanceof String)
                            || !(fields.get("path") instanceof String)
                            || (fields.get("from") != null && !(fields.get("from") instanceof String))) {
                        throw new IllegalArgumentException("Invalid JSON patch operation for \'"
                                + desc.getConfiguration().name() + "\'");
                    }
                }
                final String operations;
                if (patch.interpolate()) {
                    // interpolate patch too, if not desired the patch can be split in 2
                    operations = substitutor.replace(jsonb.toString(patch.patch()), id);
                } else {
                    operations = jsonb.toString(patch.patch());
                }
                final List<JsonPatchOperation> array = jsonb.fromString(
                        new Types.ParameterizedTypeImpl(List.class, JsonPatchOperation.class), operations);
                for (final var operation : array) {
                    if (operation == null
                            || operation.op() == null
                            || operation.path() == null
                            || ((operation.op() == JsonPatchOperation.Operation.copy
                                            || operation.op() == JsonPatchOperation.Operation.move)
                                    && operation.from() == null)) {
                        throw new IllegalArgumentException("Invalid JSON patch operation for \'"
                                + desc.getConfiguration().name() + "\'");
                    }
                }
                final var jsonPatch = new GenericJsonPatch(array);
                try {
                    final var structure = loadJsonStructure(desc, content);
                    content = jsonb.toString(jsonPatch.apply(structure));
                } catch (final IllegalArgumentException je) {
                    if (!desc.getConfiguration().interpolate()) {
                        throw new IllegalStateException(
                                "Can\'t patch \'" + desc.getConfiguration().name() + "\': " + je.getMessage(), je);
                    }
                    log.finest(
                            () -> "Trying to interpolate the descriptor before patching it since it failed without: \'"
                                    + desc.getConfiguration().name() + "\'");
                    content = substitutor.replace(alveolus, desc, content, id);
                    alreadyInterpolated = true;
                    content = jsonb.toString(jsonPatch.apply(loadJsonStructure(desc, content)));
                }
            }
        }
        if (!alreadyInterpolated && desc.getConfiguration().interpolate()) {
            content = substitutor.replace(alveolus, desc, content, id);
        }
        return new LoadedDescriptor(
                desc.getConfiguration(), content, desc.getExtension(), desc.getUri(), desc.getResource());
    }

    private Object loadJsonStructure(final LoadedDescriptor desc, final String content) {
        return "json".equals(desc.getExtension())
                ? jsonb.fromString(Object.class, content.trim())
                : yaml2json.convert(Object.class, content.trim());
    }

    private String extractExtension(final String resource) {
        return of(resource)
                .map(res -> {
                    final int dot = res.lastIndexOf('.');
                    return dot > 0 ? res.substring(dot + 1) : "yaml";
                })
                .map(ext -> "yml".equalsIgnoreCase(ext) ? "yaml" : ext)
                .map(ext -> ext.toLowerCase(ROOT))
                .orElse("yaml");
    }

    private String findExtension(final String name, final String type) {
        if ("kubernetes".equals(type)) {
            if (name.endsWith(".yaml")
                    || name.endsWith("yml")
                    || name.endsWith(".json")
                    || name.endsWith(".hb")
                    || name.endsWith(".handlebars")) {
                return "";
            }
            // yaml is the most common even if we would like json....
            return ".yaml";
        }
        if ("helm".equals(type)) {
            // helm descriptors don't use file extensions
            return "";
        }
        throw new IllegalArgumentException("Unsupported type: \'" + type + "\'");
    }

    public Stream<String> findCompletionAlveoli(final Map<String, String> options) {
        try {
            return findManifest(
                            ofNullable(options.get("from")).orElse("auto"),
                            ofNullable(options.get("manifest")).orElse("skip"),
                            null)
                    .exceptionally(e -> null)
                    .thenApply(m -> {
                        if (m == null || m.alveoli() == null) {
                            return Stream.<String>empty();
                        }
                        return m.alveoli().stream().map(Manifest.Alveolus::name).sorted();
                    })
                    .toCompletableFuture()
                    .get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
        }
        // no-op
        return Stream.empty();
    }

    public static class LoadedDescriptor {
        private final Manifest.Descriptor configuration;
        private final String content;
        private final String extension;
        private final String uri;
        private final String resource;

        public LoadedDescriptor(
                final Manifest.Descriptor configuration,
                final String content,
                final String extension,
                final String uri,
                final String resource) {
            this.configuration = configuration;
            this.content = content;
            this.extension = extension;
            this.uri = uri;
            this.resource = resource;
        }

        public Manifest.Descriptor getConfiguration() {
            return this.configuration;
        }

        public String getContent() {
            return this.content;
        }

        public String getExtension() {
            return this.extension;
        }

        public String getUri() {
            return this.uri;
        }

        public String getResource() {
            return this.resource;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            return o instanceof AlveolusHandler.LoadedDescriptor other
                    && other.canEqual(this)
                    && Objects.equals(getConfiguration(), other.getConfiguration())
                    && Objects.equals(getContent(), other.getContent())
                    && Objects.equals(getExtension(), other.getExtension())
                    && Objects.equals(getUri(), other.getUri())
                    && Objects.equals(getResource(), other.getResource());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof AlveolusHandler.LoadedDescriptor;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getConfiguration(), getContent(), getExtension(), getUri(), getResource());
        }

        @Override
        public String toString() {
            return "AlveolusHandler.LoadedDescriptor(configuration=" + this.getConfiguration() + ", content="
                    + this.getContent() + ", extension=" + this.getExtension() + ", uri=" + this.getUri()
                    + ", resource=" + this.getResource() + ")";
        }
    }

    public static class AlveolusContext {
        private final Manifest manifest;
        private final Manifest.Alveolus alveolus;
        private final Map<Predicate<String>, Manifest.Patch> patches;
        private final Map<String, String> placeholders;
        private final Collection<Manifest.DescriptorRef> excludes;
        private final ArchiveReader.Cache cache;
        private final String id;

        public AlveolusContext(
                final Manifest manifest,
                final Manifest.Alveolus alveolus,
                final Map<Predicate<String>, Manifest.Patch> patches,
                final Map<String, String> placeholders,
                final Collection<Manifest.DescriptorRef> excludes,
                final ArchiveReader.Cache cache,
                final String id) {
            this.manifest = manifest;
            this.alveolus = alveolus;
            this.patches = patches;
            this.placeholders = placeholders;
            this.excludes = excludes;
            this.cache = cache;
            this.id = id;
        }

        public Manifest getManifest() {
            return this.manifest;
        }

        public Manifest.Alveolus getAlveolus() {
            return this.alveolus;
        }

        public Map<Predicate<String>, Manifest.Patch> getPatches() {
            return this.patches;
        }

        public Map<String, String> getPlaceholders() {
            return this.placeholders;
        }

        public Collection<Manifest.DescriptorRef> getExcludes() {
            return this.excludes;
        }

        public ArchiveReader.Cache getCache() {
            return this.cache;
        }

        public String getId() {
            return this.id;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            return o instanceof AlveolusHandler.AlveolusContext other
                    && other.canEqual(this)
                    && Objects.equals(getManifest(), other.getManifest())
                    && Objects.equals(getAlveolus(), other.getAlveolus())
                    && Objects.equals(getPatches(), other.getPatches())
                    && Objects.equals(getPlaceholders(), other.getPlaceholders())
                    && Objects.equals(getExcludes(), other.getExcludes())
                    && Objects.equals(getCache(), other.getCache())
                    && Objects.equals(getId(), other.getId());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof AlveolusHandler.AlveolusContext;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    getManifest(), getAlveolus(), getPatches(), getPlaceholders(), getExcludes(), getCache(), getId());
        }

        @Override
        public String toString() {
            return "AlveolusHandler.AlveolusContext(manifest=" + this.getManifest() + ", alveolus=" + this.getAlveolus()
                    + ", patches=" + this.getPatches() + ", placeholders=" + this.getPlaceholders() + ", excludes="
                    + this.getExcludes() + ", cache=" + this.getCache() + ", id=" + this.getId() + ")";
        }
    }

    public static class ManifestAndAlveolus {
        private static final Pattern COMMA = Pattern.compile(",");
        private final Manifest manifest;
        private final Manifest.Alveolus alveolus;

        public ManifestAndAlveolus exclude(final String excludedLocations, final String excludedDescriptors) {
            if ("none".equals(excludedDescriptors) && "none".equals(excludedLocations)) {
                return this;
            }
            final var alveolus = getAlveolus()
                    .withExcludedDescriptors(Stream.concat(
                                    getAlveolus().excludedDescriptors() == null
                                            ? Stream.empty()
                                            : getAlveolus().excludedDescriptors().stream(),
                                    Stream.concat(
                                            "none".equals(excludedDescriptors)
                                                    ? Stream.empty()
                                                    : Stream.of(COMMA.split(excludedDescriptors))
                                                            .map(it -> new Manifest.DescriptorRef(it, "*")),
                                            "none".equals(excludedLocations)
                                                    ? Stream.empty()
                                                    : Stream.of(COMMA.split(excludedLocations))
                                                            .map(it -> new Manifest.DescriptorRef("*", it))))
                            .collect(toList()));
            return new AlveolusHandler.ManifestAndAlveolus(getManifest(), alveolus);
        }

        public ManifestAndAlveolus(final Manifest manifest, final Manifest.Alveolus alveolus) {
            this.manifest = manifest;
            this.alveolus = alveolus;
        }

        public Manifest getManifest() {
            return this.manifest;
        }

        public Manifest.Alveolus getAlveolus() {
            return this.alveolus;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            return o instanceof AlveolusHandler.ManifestAndAlveolus other
                    && other.canEqual(this)
                    && Objects.equals(getManifest(), other.getManifest())
                    && Objects.equals(getAlveolus(), other.getAlveolus());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof AlveolusHandler.ManifestAndAlveolus;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getManifest(), getAlveolus());
        }

        @Override
        public String toString() {
            return "AlveolusHandler.ManifestAndAlveolus(manifest=" + this.getManifest() + ", alveolus="
                    + this.getAlveolus() + ")";
        }
    }
}
