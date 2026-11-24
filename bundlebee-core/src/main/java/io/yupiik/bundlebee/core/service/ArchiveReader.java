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

import static java.util.Collections.list;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnLoadArchive;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@ApplicationScoped
public class ArchiveReader {
    private final Logger log = Logger.getLogger(ArchiveReader.class.getName());

    public ArchiveReader(final ManifestReader manifestReader, final Maven mvn, final Emitter onLoadArchiveEvent) {
        this.manifestReader = manifestReader;
        this.mvn = mvn;
        this.onLoadArchiveEvent = onLoadArchiveEvent;
    }

    private final ManifestReader manifestReader;
    private final Maven mvn;
    private final Emitter onLoadArchiveEvent;

    public Archive read(final String coords, final Path zipLocation, final String id) {
        log.finest(() -> "Reading " + zipLocation);
        if (Files.isDirectory(zipLocation)) {
            final var manifest = zipLocation.resolve("bundlebee/manifest.json");
            if (Files.exists(manifest)) {
                final var manifestJson = manifestReader.readManifest(
                        zipLocation.toAbsolutePath().normalize().toString(),
                        () -> {
                            try {
                                return Files.newInputStream(manifest);
                            } catch (final IOException e) {
                                throw new IllegalStateException(e);
                            }
                        },
                        name -> {
                            try {
                                final var abs = Path.of(name);
                                return Files.newInputStream(
                                        Files.exists(abs)
                                                ? abs
                                                : manifest.getParent().resolve(name));
                            } catch (final IOException e) {
                                throw new IllegalArgumentException(e);
                            }
                        },
                        id);
                final var descriptors = new HashMap<String, String>();
                try {
                    Files.walkFileTree(zipLocation, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                                throws IOException {
                            final var name =
                                    zipLocation.relativize(file).toString().replace('\\', '/');
                            if (name.startsWith("bundlebee/kubernetes/") && name.endsWith(".yaml")
                                    || name.endsWith(".json")
                                    || name.endsWith(".yml")) {
                                try (BufferedReader reader = new BufferedReader(
                                        new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
                                    descriptors.put(name, reader.lines().collect(joining("\n")));
                                } catch (final IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            }
                            return super.visitFile(file, attrs);
                        }
                    });
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
                return new Archive(zipLocation, manifestJson, descriptors);
            }
            throw new IllegalArgumentException("No \'" + manifest + "\' found");
        }
        try (var zip = new ZipFile(zipLocation.toFile())) {
            final var manifestEntry = zip.getEntry("bundlebee/manifest.json");
            if (manifestEntry == null) {
                throw new IllegalStateException("No manifest.json in " + zipLocation);
            }
            final var manifest = manifestReader.readManifest(
                    coords,
                    () -> {
                        try {
                            return zip.getInputStream(manifestEntry);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    name -> {
                        final var ref = name.startsWith("/") ? name : "bundlebee/" + name;
                        final var entry = zip.getEntry(ref);
                        if (entry == null) {
                            throw new IllegalStateException("No \'" + ref + "\' in " + zipLocation);
                        }
                        try {
                            return zip.getInputStream(entry);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    id);
            return new Archive(
                    zipLocation,
                    manifest,
                    list(zip.entries()).stream()
                            .filter(it -> !it.isDirectory() && it.getName().startsWith("bundlebee/kubernetes/"))
                            .collect(toMap(ZipEntry::getName, entry -> readAll(zip, entry))));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // for now it is a per command storage but later on we can use a singleton at the condition of handling eviction
    // for long running cli instances
    public Cache newCache() {
        return new Cache();
    }

    private String readAll(final ZipFile zip, final ZipEntry entry) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            return reader.lines().collect(joining("\n"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public class Cache {
        private final Map<String, CompletionStage<Archive>> cache = new ConcurrentHashMap<>();

        public CompletionStage<Archive> loadArchive(final String coords, final String id) {
            return cache.computeIfAbsent(coords, it -> doLoadArchive(it, id));
        }

        private CompletionStage<Archive> doLoadArchive(final String coords, final String id) {
            final var archive = new OnLoadArchive(coords);
            onLoadArchiveEvent.emit(archive);
            if (archive.getLoader() != null) {
                return archive.getLoader();
            }
            final var local = Paths.get(coords);
            if (Files.exists(local)) {
                return completedFuture(read(coords, local, id));
            }
            return mvn.findOrDownload(coords).thenApply(it -> read(coords, it, id));
        }

        public Cache() {}

        public Map<String, CompletionStage<Archive>> getCache() {
            return this.cache;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            return o instanceof ArchiveReader.Cache other
                    && other.canEqual(this)
                    && Objects.equals(getCache(), other.getCache());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof ArchiveReader.Cache;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getCache());
        }

        @Override
        public String toString() {
            return "ArchiveReader.Cache(cache=" + this.getCache() + ")";
        }
    }

    public static class Archive {
        private final Path location;
        private final Manifest manifest;
        private final Map<String, String> descriptors;

        public Archive(final Path location, final Manifest manifest, final Map<String, String> descriptors) {
            this.location = location;
            this.manifest = manifest;
            this.descriptors = descriptors;
        }

        public Path getLocation() {
            return this.location;
        }

        public Manifest getManifest() {
            return this.manifest;
        }

        public Map<String, String> getDescriptors() {
            return this.descriptors;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            return o instanceof ArchiveReader.Archive other
                    && other.canEqual(this)
                    && Objects.equals(getLocation(), other.getLocation())
                    && Objects.equals(getManifest(), other.getManifest())
                    && Objects.equals(getDescriptors(), other.getDescriptors());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof ArchiveReader.Archive;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getLocation(), getManifest(), getDescriptors());
        }

        @Override
        public String toString() {
            return "ArchiveReader.Archive(location=" + this.getLocation() + ", manifest=" + this.getManifest()
                    + ", descriptors=" + this.getDescriptors() + ")";
        }
    }
}
