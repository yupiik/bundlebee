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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import lombok.Data;
import lombok.extern.java.Log;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonStructure;
import javax.json.bind.Jsonb;
import javax.json.spi.JsonProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static io.yupiik.bundlebee.core.lang.CompletionFutures.handled;
import static java.util.Collections.list;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

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

    public CompletionStage<?> executeOnAlveolus(final String prefixOnVisitLog, final String from, final String manifest, final String alveolus,
                                                final Function<AlveolusContext, CompletionStage<?>> onAlveolusUser,
                                                final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor) {
        final var cache = archives.newCache();
        final var patches = Map.<String, Collection<Manifest.Patch>>of();

        final var ref = new AtomicReference<Function<AlveolusContext, CompletionStage<?>>>();
        final Function<AlveolusContext, CompletionStage<?>> internalOnAlveolus = ctx ->
                this.onAlveolus(prefixOnVisitLog, ctx.alveolus, ctx.patches, ctx.cache, ref.get(), onDescriptor);
        final Function<AlveolusContext, CompletionStage<?>> combinedOnAlveolus = onAlveolusUser == null ?
                internalOnAlveolus :
                ctx -> onAlveolusUser.apply(ctx).thenCompose(i -> internalOnAlveolus.apply(ctx));
        ref.set(combinedOnAlveolus);

        if (!"skip".equals(manifest)) {
            final var mf = manifestReader.readManifest(() -> {
                try {
                    return Files.newInputStream(Paths.get(manifest));
                } catch (final IOException e) {
                    throw new IllegalArgumentException(e);
                }
            });
            if (mf.getAlveoli() == null) {
                throw new IllegalArgumentException("No alveoli in manifest '" + manifest + "'");
            }
            final String matchedAlveolus;
            if (mf.getAlveoli().size() == 1 && "auto".equals(alveolus)) {
                matchedAlveolus = mf.getAlveoli().iterator().next().getName();
            } else {
                matchedAlveolus = alveolus;
            }
            return combinedOnAlveolus.apply(new AlveolusContext(
                    mf.getAlveoli().stream()
                            .filter(it -> Objects.equals(matchedAlveolus, it.getName()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Didn't find alveolus '" + matchedAlveolus + "' in '" + manifest + "'")),
                    patches, cache));
        }
        if ("auto".equals(alveolus)) {
            log.info("" +
                    "Auto scanning the classpath, this can be dangerous if you don't fully control your classpath, " +
                    "ensure to set a particular alveolus if you doubt about this behavior");
            return all(
                    manifests()
                            .flatMap(m -> ofNullable(m.getAlveoli()).stream().flatMap(Collection::stream))
                            .map(it -> combinedOnAlveolus.apply(new AlveolusContext(it, patches, cache)))
                            .collect(toList()), toList(),
                    true);
        }
        if (from == null || "auto".equals(from)) {
            return combinedOnAlveolus.apply(new AlveolusContext(findAlveolusInClasspath(alveolus), patches, cache));
        }
        return findAlveolus(from, alveolus, cache)
                .thenCompose(it -> combinedOnAlveolus.apply(new AlveolusContext(it, patches, cache)));
    }

    private Manifest.Alveolus findAlveolusInClasspath(final String alveolus) {
        return manifests()
                .flatMap(m -> ofNullable(m.getAlveoli()).stream().flatMap(Collection::stream))
                .filter(it -> alveolus.equals(it.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No alveolus named '" + alveolus + "' found"));
    }

    private Stream<Manifest> manifests() {
        try {
            return list(Thread.currentThread()
                    .getContextClassLoader()
                    .getResources("bundlebee/manifest.json"))
                    .stream()
                    .map(manifest -> manifestReader.readManifest(() -> {
                        try {
                            return manifest.openStream();
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }))
                    .distinct()
                    .collect(toList()) // materialize it to not leak manifest I/O outside of the method
                    .stream();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletionStage<?> onAlveolus(final String prefixOnVisitLog, final Manifest.Alveolus from,
                                          final Map<String, Collection<Manifest.Patch>> patches,
                                          final ArchiveReader.Cache cache,
                                          final Function<AlveolusContext, CompletionStage<?>> onAlveolus,
                                          final BiFunction<AlveolusContext, LoadedDescriptor, CompletionStage<?>> onDescriptor) {
        log.info(() -> prefixOnVisitLog + " '" + from.getName() + "'");
        final var currentPatches = from.getPatches() == null || from.getPatches().isEmpty() ?
                patches :
                mergePatches(patches, from.getPatches());
        final var dependencies = ofNullable(from.getDependencies()).orElseGet(List::of);
        return all(
                dependencies.stream()
                        .map(it -> {
                            if (it.getLocation() == null) {
                                return onAlveolus.apply(new AlveolusContext(findAlveolusInClasspath(it.getName()), currentPatches, cache));
                            }
                            return findAlveolus(it.getLocation(), it.getName(), cache)
                                    .thenCompose(alveolus -> onAlveolus.apply(new AlveolusContext(alveolus, currentPatches, cache)));
                        })
                        .map(it -> it.thenApply(ignored -> 1)) // just to match the signature of all()
                        .collect(toList()),
                counting(), true)
                .thenCompose(ready -> all(
                        ofNullable(from.getDescriptors()).orElseGet(List::of).stream()
                                .map(it -> findDescriptor(it, cache))
                                .collect(toList()),
                        toList(),
                        true)
                        .thenCompose(descriptors -> {
                            log.finest(() -> "Applying " + descriptors);
                            return all(descriptors.stream()
                                    .map(it -> prepare(it, currentPatches))
                                    .map(it -> onDescriptor.apply(new AlveolusContext(from, patches, cache), it))
                                    .collect(toList()), counting(), true);
                        }));
    }

    private CompletionStage<Manifest.Alveolus> findAlveolus(final String from, final String alveolus,
                                                            final ArchiveReader.Cache cache) {
        return cache.loadArchive(from)
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
                        .orElseThrow(() -> new IllegalArgumentException("" +
                                "No alveolus '" + alveolus + "' found, available in '" + from + "': " +
                                archive.getManifest().getAlveoli().stream()
                                        .map(Manifest.Alveolus::getName)
                                        .collect(joining(",")))));
    }

    private CompletionStage<LoadedDescriptor> findDescriptor(final Manifest.Descriptor desc,
                                                             final ArchiveReader.Cache cache) {
        final var type = ofNullable(desc.getType()).orElse("kubernetes");
        final var resource = String.join("/", "bundlebee", type, desc.getName() + findExtension(desc.getName(), type));
        return handled(() -> ofNullable(Thread.currentThread().getContextClassLoader().getResource(resource))
                .map(url -> {
                    try (final BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                        return new LoadedDescriptor(desc, reader.lines().collect(joining("\n")), extractExtension(resource));
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(CompletableFuture::completedStage)
                .orElseGet(() -> {
                    if (desc.getLocation() == null || desc.getLocation().isBlank()) {
                        throw new IllegalArgumentException("No location for descriptor: >" + desc + "< so it will not be downloadable.");
                    }
                    return cache.loadArchive(desc.getLocation())
                            .thenApply(archive -> {
                                final var content = archive.getDescriptors().get(resource);
                                if (content == null) {
                                    throw new IllegalStateException("No descriptor '" + resource + "' found in '" + desc.getLocation() + "'");
                                }
                                return new LoadedDescriptor(desc, content, extractExtension(resource));
                            });
                }));
    }

    private Map<String, Collection<Manifest.Patch>> mergePatches(final Map<String, Collection<Manifest.Patch>> patches,
                                                                 final List<Manifest.Patch> newPatches) {
        final var result = new HashMap<>(patches);
        newPatches.forEach(p -> result.computeIfAbsent(p.getDescriptorName(), k -> new ArrayList<>()).add(p));
        return result;
    }

    private LoadedDescriptor prepare(final LoadedDescriptor desc, final Map<String, Collection<Manifest.Patch>> patches) {
        var content = desc.getContent();

        final var descPatches = patches.get(desc.configuration.getName());
        if (descPatches != null && !descPatches.isEmpty()) {
            for (final Manifest.Patch patch : descPatches) {
                if (patch.isInterpolate()) {
                    content = substitutor.replace(content);
                }
                if (patch.getPatch() != null) {
                    final JsonArray array;
                    if (patch.isInterpolate()) { // interpolate patch too, if not desired the patch can be split in 2
                        array = jsonb.fromJson(substitutor.replace(patch.getPatch().toString()), JsonArray.class);
                    } else {
                        array = patch.getPatch();
                    }
                    final var jsonPatch = jsonProvider.createPatch(array);
                    final var structure = "json".equals(desc.getExtension()) ?
                            jsonb.fromJson(content.trim(), JsonStructure.class) :
                            yaml2json.convert(JsonStructure.class, content.trim());
                    content = jsonPatch.apply(structure).toString();
                }
            }
        }
        if (desc.getConfiguration().isInterpolate()) {
            content = substitutor.replace(content);
        }
        return new LoadedDescriptor(desc.getConfiguration(), content, desc.getExtension());
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
            if (name.endsWith(".yaml") || name.endsWith("yml") || name.endsWith(".json")) {
                return "";
            }
            // yaml is the most common even if we would like json....
            return ".yaml";
        }
        throw new IllegalArgumentException("Unsupported type: '" + type + "'");
    }

    @Data
    public static class LoadedDescriptor {
        private final Manifest.Descriptor configuration;
        private final String content;
        private final String extension;
    }

    @Data
    public static class AlveolusContext {
        private final Manifest.Alveolus alveolus;
        private final Map<String, Collection<Manifest.Patch>> patches;
        private final ArchiveReader.Cache cache;
    }
}
