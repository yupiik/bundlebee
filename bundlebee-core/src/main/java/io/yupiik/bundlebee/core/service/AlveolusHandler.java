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

import io.yupiik.bundlebee.core.configuration.ThreadLocalConfigSource;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import lombok.Data;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.Config;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonStructure;
import javax.json.bind.Jsonb;
import javax.json.spi.JsonProvider;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static io.yupiik.bundlebee.core.lang.CompletionFutures.chain;
import static io.yupiik.bundlebee.core.lang.CompletionFutures.handled;
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
import static java.util.stream.StreamSupport.stream;

@Log
@ApplicationScoped
public class AlveolusHandler {
    @Inject
    @BundleBee
    private JsonProvider jsonProvider;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    private ManifestReader manifestReader;

    @Inject
    private Substitutor substitutor;

    @Inject
    private Yaml2JsonConverter yaml2json;

    @Inject
    private ArchiveReader archives;

    @Inject
    private ConditionEvaluator conditionEvaluator;

    @Inject
    private Config config;

    @Inject
    private RequirementService requirementService;

    @Inject
    private Event<OnPrepareDescriptor> onPrepareDescriptorEvent;

    private ThreadLocalConfigSource threadLocalConfigSource;

    @PostConstruct
    private void init() {
        threadLocalConfigSource = stream(config.getConfigSources().spliterator(), false)
                .filter(ThreadLocalConfigSource.class::isInstance)
                .map(ThreadLocalConfigSource.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No ThreadLocalConfigSource found"));
    }

    /**
     * @deprecated prefer the flavor with the explicit id as parameter.
     */
    @Deprecated
    public CompletionStage<Manifest> findManifest(final String from, final String manifest) {
        return findManifest(from, null);
    }

    public CompletionStage<Manifest> findManifest(final String from, final String manifest, final String id) {
        if (!"skip".equals(manifest)) {
            return completedFuture(readManifest(manifest, id));
        }
        if (from == null || "auto".equals(from)) { // can't do
            return completedFuture(null);
        }
        return archives.newCache().loadArchive(from, id).thenApply(ArchiveReader.Archive::getManifest);
    }

    /**
     * @deprecated ensure to pass an id or explicitly {@code null}.
     */
    @Deprecated
    public CompletionStage<List<ManifestAndAlveolus>> findRootAlveoli(final String from, final String manifest,
                                                                      final String alveolus) {
        return findRootAlveoli(from, manifest, alveolus, null);
    }

    public CompletionStage<List<ManifestAndAlveolus>> findRootAlveoli(final String from, final String manifest,
                                                                      final String alveolus, final String id) {
        return doFindRootAlveoli(from, manifest, alveolus, id)
                .thenApply(a -> {
                    // only check root one since we assume it enforces dependencies ones if any +
                    // we want to be able to bypass it if needed
                    a.forEach(it -> requirementService.checkRequirements(it.getManifest()));
                    return a;
                });
    }

    private CompletionStage<List<ManifestAndAlveolus>> doFindRootAlveoli(final String from,
                                                                         final String manifest,
                                                                         final String alveolus,
                                                                         final String id) {
        if (!"skip".equals(manifest)) {
            final var mf = readManifest(manifest, id);
            if (mf.getAlveoli() == null) {
                throw new IllegalArgumentException("No alveoli in manifest '" + manifest + "'");
            }
            final String matchedAlveolus;
            if (mf.getAlveoli().size() == 1 && "auto".equals(alveolus)) {
                matchedAlveolus = mf.getAlveoli().iterator().next().getName();
            } else {
                matchedAlveolus = alveolus;
            }
            return mf.getAlveoli().stream()
                    .filter(it -> Objects.equals(matchedAlveolus, it.getName()))
                    .findFirst()
                    .map(it -> new ManifestAndAlveolus(mf, it))
                    .map(List::of)
                    .map(CompletableFuture::completedFuture)
                    .orElseThrow(() -> new IllegalArgumentException("Didn't find alveolus '" + matchedAlveolus + "' in '" + manifest + "'"));
        }
        if ("auto".equals(alveolus)) {
            log.info("" +
                    "Auto scanning the classpath, this can be dangerous if you don't fully control your classpath, " +
                    "ensure to set a particular alveolus if you doubt about this behavior");
            return completedFuture(findManifestsFromClasspath(id)
                    .flatMap(m -> ofNullable(m.getAlveoli()).stream().flatMap(it -> it.stream().map(a -> new ManifestAndAlveolus(m, a))))
                    .collect(toList()));
        }
        if (from == null || "auto".equals(from)) {
            return completedFuture(List.of(findAlveolusInClasspath(alveolus, id)));
        }
        return findAlveolus(from, alveolus, archives.newCache(), id).thenApply(List::of);
    }

    public CompletionStage<?> executeOnceOnAlveolus(final String prefixOnVisitLog, final Manifest manifest, final Manifest.Alveolus alveolus,
                                                    final Function<AlveolusContext, CompletionStage<?>> onAlveolusUser,
                                                    final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
                                                    final ArchiveReader.Cache cache, final Function<LoadedDescriptor, CompletionStage<Void>> awaiter,
                                                    final String alreadyHandledMarker, final String id) {
        final var alreadyDone = new HashSet<String>();
        return executeOnAlveolus(prefixOnVisitLog, manifest, alveolus, onAlveolusUser, (ctx, desc) -> {
            if (!alreadyDone.add(desc.getConfiguration().getName() + '|' + desc.getContent())) {
                log.info(() -> desc.getConfiguration().getName() + " already " + alreadyHandledMarker + ", skipping");
                return completedFuture(false);
            }
            return onDescriptor.apply(ctx, desc);
        }, cache, awaiter, id);
    }

    /**
     * @deprecated prefer the flavor with the explicit id as parameter.
     */
    @Deprecated
    public CompletionStage<?> executeOnAlveolus(final String prefixOnVisitLog, final Manifest manifest, final Manifest.Alveolus alveolus,
                                                final Function<AlveolusContext, CompletionStage<?>> onAlveolusUser,
                                                final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
                                                final ArchiveReader.Cache cache, final Function<LoadedDescriptor, CompletionStage<Void>> awaiter) {
        return executeOnAlveolus(prefixOnVisitLog, manifest, alveolus, onAlveolusUser, onDescriptor, cache, awaiter, null);
    }

    public CompletionStage<?> executeOnAlveolus(final String prefixOnVisitLog, final Manifest manifest, final Manifest.Alveolus alveolus,
                                                final Function<AlveolusContext, CompletionStage<?>> onAlveolusUser,
                                                final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
                                                final ArchiveReader.Cache cache, final Function<LoadedDescriptor, CompletionStage<Void>> awaiter,
                                                final String id) {
        final var ref = new AtomicReference<Function<AlveolusContext, CompletionStage<?>>>();
        final Function<AlveolusContext, CompletionStage<?>> internalOnAlveolus = ctx ->
                this.onAlveolus(prefixOnVisitLog, manifest, ctx.alveolus, ctx.patches, ctx.excludes, ctx.cache, ref.get(), onDescriptor, awaiter, ctx.placeholders, id);
        final Function<AlveolusContext, CompletionStage<?>> combinedOnAlveolus = onAlveolusUser == null ?
                internalOnAlveolus :
                ctx -> onAlveolusUser.apply(ctx).thenCompose(i -> internalOnAlveolus.apply(ctx));
        ref.set(combinedOnAlveolus);
        return combinedOnAlveolus.apply(new AlveolusContext(manifest, alveolus, Map.of(), Map.of(), List.of(), cache, id));
    }

    /**
     * @deprecated prefer the flavor with the explicit id as parameter.
     */
    @Deprecated
    private Manifest readManifest(final String manifest) {
        return readManifest(manifest, null);
    }

    private Manifest readManifest(final String manifest, final String id) {
        if (manifest.startsWith("{")) {
            return manifestReader.readManifest(
                    null,
                    () -> new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)),
                    s -> {
                        throw new IllegalArgumentException("Relative references foriddden for in memory manifest.json: '" + s + "'");
                    },
                    id);
        }
        final var path = Paths.get(manifest);
        return manifestReader.readManifest(path.toAbsolutePath().getParent().getParent().normalize().toString(), () -> {
            try {
                return Files.newInputStream(path);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }, s -> resolveManifestReference(path, s), id);
    }

    private InputStream resolveManifestReference(final Path path, final String name) {
        try {
            final var abs = Path.of(name);
            return Files.newInputStream(Files.exists(abs) ? abs : path.getParent().resolve(name));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private ManifestAndAlveolus findAlveolusInClasspath(final String alveolus, final String id) {
        final var manifests = findManifestsFromClasspath(id).collect(toList());
        return manifests.stream()
                .flatMap(m -> ofNullable(m.getAlveoli()).stream()
                        .flatMap(a -> a.stream().map(it -> new ManifestAndAlveolus(m, it))))
                .filter(it -> alveolus.equals(it.getAlveolus().getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No alveolus named '" + alveolus + "' found"));
    }

    public Stream<Manifest> findManifestsFromClasspath(final String id) {
        final var classLoader = Thread.currentThread().getContextClassLoader();
        try {
            return list(classLoader
                    .getResources("bundlebee/manifest.json"))
                    .stream()
                    .map(manifest -> manifestReader.readManifest(null, () -> {
                        try {
                            return manifest.openStream();
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }, s -> s.startsWith("/") ? classLoader.getResourceAsStream(s) : classLoader.getResourceAsStream("bundlebee/" + s), id))
                    .distinct()
                    .collect(toList()) // materialize it to not leak manifest I/O outside of the method
                    .stream();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletionStage<?> onAlveolus(final String prefixOnVisitLog, final Manifest manifest, final Manifest.Alveolus from,
                                          final Map<Predicate<String>, Manifest.Patch> patches,
                                          final Collection<Manifest.DescriptorRef> excludes,
                                          final ArchiveReader.Cache cache,
                                          final Function<AlveolusContext, CompletionStage<?>> onAlveolus,
                                          final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor,
                                          final Function<LoadedDescriptor, CompletionStage<Void>> awaiter,
                                          final Map<String, String> placeholders,
                                          final String id) {
        if (prefixOnVisitLog != null) {
            log.info(() -> prefixOnVisitLog + " '" + from.getName() + "'");
        }
        final var currentExcludes = from.getExcludedDescriptors() == null ?
                excludes :
                Stream.concat(
                                excludes.stream(),
                                from.getExcludedDescriptors() != null ? from.getExcludedDescriptors().stream() : Stream.empty())
                        .distinct()
                        .collect(toList());
        final var currentPatches = from.getPatches() == null || from.getPatches().isEmpty() ?
                patches :
                mergePatches(patches, from.getPatches());
        final var currentPlaceholders = from.getPlaceholders() == null || from.getPlaceholders().isEmpty() ?
                placeholders :
                mergePlaceholders(placeholders, from.getPlaceholders());
        final var dependencies = ofNullable(from.getDependencies()).orElseGet(List::of);
        final var dependenciesTasks = dependencies.stream()
                // note we can't filter it *here* even if all descriptors are excluded because it can have depdencies
                .filter(dep -> conditionEvaluator.test(dep.getIncludeIf()))
                .map(it -> {
                    if (it.getLocation() == null) {
                        if (manifest.getAlveoli() != null) { // prefer in the same manifest first
                            final var found = manifest.getAlveoli().stream()
                                    .filter(a -> Objects.equals(it.getName(), a.getName()))
                                    .findFirst()
                                    .orElse(null);
                            if (found != null) {
                                return (Supplier<CompletionStage<?>>) () -> onAlveolus.apply(new AlveolusContext(
                                        manifest, found, currentPatches, currentPlaceholders, currentExcludes, cache, id));
                            }
                        }
                        final var alveolus = findAlveolusInClasspath(it.getName(), id);
                        return (Supplier<CompletionStage<?>>) () -> onAlveolus.apply(new AlveolusContext(
                                alveolus.getManifest(), alveolus.getAlveolus(), currentPatches, currentPlaceholders, currentExcludes, cache, id));
                    }
                    return (Supplier<CompletionStage<?>>) () -> findAlveolus(it.getLocation(), it.getName(), cache, id)
                            .thenCompose(alveolus -> onAlveolus.apply(new AlveolusContext(
                                    manifest, alveolus.getAlveolus(), currentPatches, currentPlaceholders, currentExcludes, cache, id)));
                });
        if (from.isChainDependencies()) {
            return chain(dependenciesTasks.iterator(), true)
                    .thenCompose(ready -> all(
                            selectDescriptors(from, excludes)
                                    .map(desc -> findDescriptor(desc, cache, id))
                                    .collect(toList()), toList(), true)
                            .thenCompose(descriptors -> afterDependencies(
                                    manifest, from, patches, excludes, cache, onDescriptor,
                                    awaiter, currentPlaceholders, currentPatches, descriptors, id)));
        }
        return all(
                dependenciesTasks
                        .map(Supplier::get)
                        .map(it -> it.thenApply(ignored -> 1)) // just to match the signature of all()
                        .collect(toList()),
                counting(), true)
                .thenCompose(ready -> all(
                        selectDescriptors(from, excludes)
                                .map(desc -> findDescriptor(desc, cache, id))
                                .collect(toList()), toList(), true)
                        .thenCompose(descriptors -> afterDependencies(
                                manifest, from, patches, excludes, cache, onDescriptor,
                                awaiter, currentPlaceholders, currentPatches, descriptors, id)));
    }

    private CompletionStage<?> afterDependencies(final Manifest manifest, final Manifest.Alveolus from,
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
        for (final var next : rankedDescriptors.stream()
                .map(descs -> {
                    if (awaiter == null) {
                        return prepareDescriptors(
                                manifest, from, patches, placeholders, excludes, cache, onDescriptor, currentPatches, descs, id);
                    }

                    final var filteredDescs = new ArrayList<LoadedDescriptor>();
                    final var descriptorApply = prepareDescriptors(
                            manifest, from, patches, placeholders, excludes, cache,
                            (ctx, desc) -> {
                                synchronized (filteredDescs) {
                                    filteredDescs.add(desc);
                                }
                                return onDescriptor.apply(ctx, desc);
                            },
                            currentPatches, descs, id);
                    return descriptorApply.thenCompose(result -> {
                        final Collection<CompletionStage<Void>> awaiters = filteredDescs.stream()
                                .map(awaiter)
                                .collect(toList());
                        return all(awaiters, counting(), true).thenApply(it -> result);
                    });
                })
                .collect(toList())) {
            promise = promise.thenCompose(ignored -> next);
        }
        return promise;
    }

    private Collection<Collection<LoadedDescriptor>> rankDescriptors(final List<LoadedDescriptor> descriptors) {
        final Collection<Collection<LoadedDescriptor>> rankedDescriptors = new ArrayList<>(1 /*generally 1 or 2*/);
        Collection<LoadedDescriptor> current = new ArrayList<>();
        for (final LoadedDescriptor desc : descriptors) {
            current.add(desc);
            if (desc.getConfiguration().isAwait()) {
                rankedDescriptors.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            rankedDescriptors.add(current);
        }
        return rankedDescriptors;
    }

    private CompletionStage<Long> prepareDescriptors(final Manifest manifest, final Manifest.Alveolus from,
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
                        .peek(it -> onPrepareDescriptorEvent.fire(new OnPrepareDescriptor(id, from.getName(), it.getConfiguration().getName(), it.getContent(), placeholders)))
                        .map(it -> prepare(from, it, currentPatches, placeholders, id))
                        .map(it -> onDescriptor.apply(new AlveolusContext(manifest, from, patches, placeholders, excludes, cache, id), it))
                        .collect(toList()), counting(), true);
    }

    private Stream<Manifest.Descriptor> selectDescriptors(final Manifest.Alveolus from, final Collection<Manifest.DescriptorRef> excludes) {
        return ofNullable(from.getDescriptors()).orElseGet(List::of).stream()
                .filter(desc -> conditionEvaluator.test(desc.getIncludeIf()) && excludes.stream()
                        .noneMatch(d -> matches(d.getLocation(), desc.getLocation()) && matches(d.getName(), desc.getName())));
    }

    private boolean matches(final String expected, final String actual) {
        return Objects.equals(expected, actual) || expected == null || "*".equals(expected);
    }

    private CompletionStage<ManifestAndAlveolus> findAlveolus(final String from, final String alveolus,
                                                              final ArchiveReader.Cache cache,
                                                              final String id) {
        return cache.loadArchive(from, id)
                .thenApply(archive -> requireNonNull(requireNonNull(archive.getManifest(), "No manifest found in " + from)
                        .getAlveoli(), "No alveolus in manifest of " + from).stream()
                        .filter(it -> Objects.equals(it.getName(), alveolus))
                        .peek(it -> {
                            if (it.getDescriptors() != null) {
                                it.getDescriptors().stream()
                                        .filter(d -> d.getLocation() == null)
                                        .forEach(d -> d.setLocation(from));
                            }
                        })
                        .findFirst()
                        .map(it -> new ManifestAndAlveolus(archive.getManifest(), it))
                        .orElseThrow(() -> new IllegalArgumentException("" +
                                "No alveolus '" + alveolus + "' found, available in '" + from + "': " +
                                archive.getManifest().getAlveoli().stream()
                                        .map(Manifest.Alveolus::getName)
                                        .collect(joining(",")))));
    }

    private CompletionStage<LoadedDescriptor> findDescriptor(final Manifest.Descriptor desc,
                                                             final ArchiveReader.Cache cache,
                                                             final String id) {
        final var type = ofNullable(desc.getType()).orElse("kubernetes");
        final var resource = String.join("/", "bundlebee", type, desc.getName() + findExtension(desc.getName(), type));
        return handled(() -> ofNullable(Thread.currentThread().getContextClassLoader().getResource(resource))
                .map(url -> {
                    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                        return new LoadedDescriptor(
                                desc, reader.lines().collect(joining("\n")), extractExtension(resource),
                                url.toExternalForm(), resource);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(CompletableFuture::completedStage)
                .orElseGet(() -> {
                    if (desc.getLocation() == null || desc.getLocation().isBlank()) {
                        throw new IllegalArgumentException("No location for descriptor: >" + desc + "< so it will not be downloadable.");
                    }
                    return cache.loadArchive(desc.getLocation(), id)
                            .thenApply(archive -> {
                                final var content = archive.getDescriptors().get(resource);
                                if (content == null) {
                                    throw new IllegalStateException("No descriptor '" + resource + "' found in '" + desc.getLocation() + "'");
                                }
                                return new LoadedDescriptor(
                                        desc, content, extractExtension(resource),
                                        Files.isDirectory(archive.getLocation()) ?
                                                archive.getLocation().resolve(resource).toUri().toASCIIString() :
                                                archive.getLocation().toUri().toASCIIString() + "!" + resource, resource);
                            });
                }));
    }

    private Map<Predicate<String>, Manifest.Patch> mergePatches(final Map<Predicate<String>, Manifest.Patch> patches,
                                                                final List<Manifest.Patch> newPatches) {
        final var result = new HashMap<>(patches);
        newPatches.forEach(p -> result.put(toPredicate(p.getDescriptorName()), p));
        return result;
    }

    private Map<String, String> mergePlaceholders(final Map<String, String> current, final Map<String, String> newOnes) {
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
            return "*".equals(descriptorName) ? s -> true : Pattern.compile(descriptorName).asMatchPredicate();
        }
        if (descriptorName.startsWith("regex:")) { // other advanced regex, this prefix forces regex mode
            return Pattern.compile(descriptorName.substring("regex:".length())).asMatchPredicate();
        }
        return descriptorName::equals;
    }

    private LoadedDescriptor prepare(final Manifest.Alveolus alveolus,
                                     final LoadedDescriptor desc,
                                     final Map<Predicate<String>, Manifest.Patch> patches,
                                     final Map<String, String> placeholders,
                                     final String id) {
        return threadLocalConfigSource.withContext(placeholders, () -> doPrepare(alveolus, desc, patches, id));
    }

    private LoadedDescriptor doPrepare(final Manifest.Alveolus alveolus, final LoadedDescriptor desc,
                                       final Map<Predicate<String>, Manifest.Patch> patches, final String id) {
        var content = desc.getContent();

        final var descPatches = patches.entrySet().stream()
                .filter(e -> e.getKey().test(desc.configuration.getName()) ||
                        // extensions can be implicit so ensure we support both
                        e.getKey().test(desc.configuration.getName() + "." + desc.extension))
                .map(Map.Entry::getValue)
                .collect(toList());
        boolean alreadyInterpolated = false;
        if (!descPatches.isEmpty()) {
            for (final Manifest.Patch patch : descPatches) {
                if (patch.isInterpolate()) {
                    content = substitutor.replace(alveolus, desc, content, id);
                }
                if (patch.getPatch() == null) {
                    continue;
                }
                if (patch.getIncludeIf() != null &&
                        patch.getIncludeIf().getConditions() != null &&
                        !patch.getIncludeIf().getConditions().isEmpty() &&
                        !conditionEvaluator.test(patch.getIncludeIf())) {
                    continue;
                }

                final JsonArray array;
                if (patch.isInterpolate()) { // interpolate patch too, if not desired the patch can be split in 2
                    array = jsonb.fromJson(substitutor.replace(patch.getPatch().toString(), id), JsonArray.class);
                } else {
                    array = patch.getPatch();
                }
                final var jsonPatch = jsonProvider.createPatch(array);
                try {
                    final var structure = loadJsonStructure(desc, content);
                    content = jsonPatch.apply(structure).toString();
                } catch (final JsonException je) {
                    if (!desc.getConfiguration().getInterpolate()) {
                        throw new IllegalStateException("Can't patch '" + desc.getConfiguration().getName() + "': " + je.getMessage(), je);
                    }
                    log.finest(() -> "" +
                            "Trying to interpolate the descriptor before patching it since it failed without: '" +
                            desc.getConfiguration().getName() + "'");
                    content = substitutor.replace(alveolus, desc, content, id);
                    alreadyInterpolated = true;
                    content = jsonPatch.apply(loadJsonStructure(desc, content)).toString();
                }
            }
        }
        if (!alreadyInterpolated && desc.getConfiguration().getInterpolate()) {
            content = substitutor.replace(alveolus, desc, content, id);
        }
        return new LoadedDescriptor(desc.getConfiguration(), content, desc.getExtension(), desc.getUri(), desc.getResource());
    }

    private JsonStructure loadJsonStructure(final LoadedDescriptor desc, final String content) {
        return "json".equals(desc.getExtension()) ?
                jsonb.fromJson(content.trim(), JsonStructure.class) :
                yaml2json.convert(JsonStructure.class, content.trim());
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
            if (name.endsWith(".yaml") || name.endsWith("yml") ||
                    name.endsWith(".json") ||
                    name.endsWith(".hb") || name.endsWith(".handlebars")) {
                return "";
            }
            // yaml is the most common even if we would like json....
            return ".yaml";
        }
        throw new IllegalArgumentException("Unsupported type: '" + type + "'");
    }

    public Stream<String> findCompletionAlveoli(final Map<String, String> options) {
        try {
            return findManifest(
                    ofNullable(options.get("from")).orElse("auto"),
                    ofNullable(options.get("manifest")).orElse("skip"),
                    null)
                    .exceptionally(e -> null)
                    .thenApply(m -> {
                        if (m == null || m.getAlveoli() == null) {
                            return Stream.<String>empty();
                        }
                        return m.getAlveoli().stream().map(Manifest.Alveolus::getName).sorted();
                    })
                    .toCompletableFuture()
                    .get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            // no-op
        }
        return Stream.empty();
    }

    @Data
    public static class LoadedDescriptor {
        private final Manifest.Descriptor configuration;
        private final String content;
        private final String extension;
        private final String uri;
        private final String resource;
    }

    @Data
    public static class AlveolusContext {
        private final Manifest manifest;
        private final Manifest.Alveolus alveolus;
        private final Map<Predicate<String>, Manifest.Patch> patches;
        private final Map<String, String> placeholders;
        private final Collection<Manifest.DescriptorRef> excludes;
        private final ArchiveReader.Cache cache;
        private final String id;
    }

    @Data
    public static class ManifestAndAlveolus {
        private final Manifest manifest;
        private final Manifest.Alveolus alveolus;

        public ManifestAndAlveolus exclude(final String excludedLocations, final String excludedDescriptors) {
            if ("none".equals(excludedDescriptors) && "none".equals(excludedLocations)) {
                return this;
            }

            final var alveolus = getAlveolus().copy();
            alveolus.setExcludedDescriptors(Stream.concat(
                    getAlveolus().getExcludedDescriptors() == null ?
                            Stream.empty() :
                            getAlveolus().getExcludedDescriptors().stream(),
                    Stream.concat(
                            "none".equals(excludedDescriptors) ?
                                    Stream.empty() :
                                    Stream.of(excludedDescriptors.split(",")).map(it -> new Manifest.DescriptorRef(it, "*")),
                            "none".equals(excludedLocations) ?
                                    Stream.empty() :
                                    Stream.of(excludedLocations.split(",")).map(it -> new Manifest.DescriptorRef("*", it)))
            ).collect(toList()));
            return new AlveolusHandler.ManifestAndAlveolus(getManifest(), alveolus);
        }
    }
}
