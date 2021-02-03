package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.bundlebee.core.service.ManifestReader;
import io.yupiik.bundlebee.core.service.MavenResolver;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import lombok.Data;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonStructure;
import javax.json.bind.Jsonb;
import javax.json.spi.JsonProvider;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
@Dependent
public class ApplyCommand implements Executable {
    @Inject
    @Description("Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath.")
    @ConfigProperty(name = "bundlebee.apply.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.apply.from", defaultValue = "auto")
    private String from;

    @Inject
    private KubeClient kube;

    @Inject
    private MavenResolver mvn;

    @Inject
    private ManifestReader manifestReader;

    @Inject
    private ArchiveReader archives;

    @Inject
    private Config config;

    @Inject
    @BundleBee
    private JsonProvider jsonProvider;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    private Yaml2JsonConverter yaml2json;

    private Substitutor substitutor;

    @Override
    public String name() {
        return "apply";
    }

    @Override
    public String description() {
        return "Apply/deploy a set of descriptors from a root one.";
    }

    @PostConstruct
    private void init() {
        substitutor = new Substitutor(it -> config.getOptionalValue(it, String.class).orElse(null));
    }

    @Override
    public CompletionStage<?> execute() {
        final var cache = new ConcurrentHashMap<String, CompletionStage<ArchiveReader.Archive>>();
        final var patches = Map.<String, Collection<Manifest.Patch>>of();
        if ("auto".equals(alveolus)) {
            log.info("Auto deploying the classpath, this can be dangerous if you don't fully control your classpath");
            return all(
                    manifests()
                            .flatMap(m -> ofNullable(m.getAlveoli()).stream().flatMap(Collection::stream))
                            .map(it -> deploy(it, patches, cache))
                            .collect(toList()), toList(),
                    true);
        }
        if (from == null || "auto".equals(from)) {
            return deploy(findAlveolusInClasspath(alveolus), patches, cache);
        }
        return findAlveolus(alveolus, cache)
                .thenCompose(it -> deploy(it, patches, cache));
    }

    private CompletionStage<Manifest.Alveolus> findAlveolus(final String alveolus,
                                                            final ConcurrentMap<String, CompletionStage<ArchiveReader.Archive>> cache) {
        return loadArchive(from, cache)
                .thenApply(archive -> requireNonNull(requireNonNull(archive.getManifest(), "No manifest found in " + from)
                        .getAlveoli(), "No alveolus in manifest of " + from).stream()
                        .filter(it -> Objects.equals(it.getName(), alveolus))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("" +
                                "No alveolus '" + this.alveolus + "' found, available in '" + from + "': " +
                                archive.getManifest().getAlveoli().stream()
                                        .map(Manifest.Alveolus::getName)
                                        .collect(joining(",")))));
    }

    private CompletionStage<ArchiveReader.Archive> loadArchive(final String coords,
                                                               final ConcurrentMap<String, CompletionStage<ArchiveReader.Archive>> cache) {
        return cache.computeIfAbsent(coords, k -> mvn.findOrDownload(k)
                .thenApply(zipLocation -> archives.read(zipLocation)));
    }

    private Manifest.Alveolus findAlveolusInClasspath(final String alveolus) {
        return manifests()
                .flatMap(m -> ofNullable(m.getAlveoli()).stream().flatMap(Collection::stream))
                .filter(it -> alveolus.equals(it.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No alveolus named '" + alveolus + "' found"));
    }

    private CompletionStage<?> deploy(final Manifest.Alveolus from,
                                      final Map<String, Collection<Manifest.Patch>> patches,
                                      final ConcurrentMap<String, CompletionStage<ArchiveReader.Archive>> cache) {
        log.info(() -> "Starting to deploy '" + from.getName() + "'");
        if (from.getDescriptors() == null) { // we tolerate empty for testing purses for now
            final CompletableFuture<?> promise = new CompletableFuture<>();
            promise.completeExceptionally(new IllegalArgumentException("No descriptor for alveolus '" + from.getName() + "'"));
            return promise;
        }
        final var currentPatches = from.getPatches() == null || from.getPatches().isEmpty() ?
                patches :
                mergePatches(patches, from.getPatches());
        final var dependencies = ofNullable(from.getDependencies()).orElseGet(List::of);
        return all(
                dependencies.stream()
                        .map(it -> {
                            if (it.getLocation() == null) {
                                return deploy(findAlveolusInClasspath(it.getName()), currentPatches, cache);
                            }
                            return findAlveolus(it.getName(), cache)
                                    .thenCompose(alveolus -> deploy(alveolus, currentPatches, cache));
                        })
                        .map(it -> it.thenApply(ignored -> 1)) // just to match the signature of all()
                        .collect(toList()),
                counting(), true)
                .thenCompose(ready -> all(
                        from.getDescriptors().stream()
                                .map(it -> findDescriptor(it, cache))
                                .collect(toList()),
                        toList(),
                        true)
                        .thenCompose(descriptors -> {
                            log.finest(() -> "Applying " + descriptors);
                            return all(descriptors.stream()
                                    .map(it -> prepare(it, currentPatches))
                                    .map(it -> kube.apply(it.getContent(), it.getExtension()))
                                    .collect(toList()), toList(), true);
                        }));
    }

    private Map<String, Collection<Manifest.Patch>> mergePatches(final Map<String, Collection<Manifest.Patch>> patches,
                                                                 final List<Manifest.Patch> newPatches) {
        final var result = new HashMap<>(patches);
        newPatches.forEach(p -> result.computeIfAbsent(p.getDescriptorName(), k -> new ArrayList<>()).add(p));
        return result;
    }

    private LoadedDescriptor prepare(final LoadedDescriptor desc, final Map<String, Collection<Manifest.Patch>> patches) {
        final var descPatches = patches.get(desc.configuration.getName());
        if (descPatches == null || descPatches.isEmpty()) {
            return desc;
        }
        var content = desc.getContent();
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

    private CompletionStage<LoadedDescriptor> findDescriptor(final Manifest.Descriptor desc,
                                                             final ConcurrentMap<String, CompletionStage<ArchiveReader.Archive>> cache) {
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
                    return loadArchive(desc.getLocation(), cache)
                            .thenApply(archive -> {
                                final var content = archive.getDescriptors().get(resource);
                                if (content == null) {
                                    throw new IllegalStateException("No descriptor '" + resource + "' found in '" + desc.getLocation() + "'");
                                }
                                return new LoadedDescriptor(desc, content, extractExtension(resource));
                            });
                }));
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

    @Data
    private static class LoadedDescriptor {
        private final Manifest.Descriptor configuration;
        private final String content;
        private final String extension;
    }
}
