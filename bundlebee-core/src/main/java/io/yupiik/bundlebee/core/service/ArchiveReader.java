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
import lombok.Data;
import lombok.extern.java.Log;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Vetoed;
import javax.inject.Inject;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.Collections.list;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Log
@ApplicationScoped
public class ArchiveReader {
    @Inject
    private ManifestReader manifestReader;

    @Inject
    private Maven mvn;

    public Archive read(final Path zipLocation) {
        log.finest(() -> "Reading " + zipLocation);
        if (Files.isDirectory(zipLocation)) {
            final var manifest = zipLocation.resolve("bundlebee/manifest.json");
            if (Files.exists(manifest)) {
                final var manifestJson = manifestReader.readManifest(() -> {
                    try {
                        return Files.newInputStream(manifest);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                final var descriptors = new HashMap<String, String>();
                try {
                    Files.walkFileTree(zipLocation, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                            final var name = zipLocation.relativize(file).toString().replace('\\', '/');
                            if (name.startsWith("bundlebee/kubernetes/") && name.endsWith(".yaml") || name.endsWith(".json") || name.endsWith(".yml")) {
                                try (final BufferedReader reader = new BufferedReader(new InputStreamReader(
                                        Files.newInputStream(file), StandardCharsets.UTF_8))) {
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
                return new Archive(manifestJson, descriptors);
            }
            throw new IllegalArgumentException("No '" + manifest + "' found");
        }
        try (final var zip = new ZipFile(zipLocation.toFile())) {
            final var manifestEntry = zip.getEntry("bundlebee/manifest.json");
            if (manifestEntry == null) {
                throw new IllegalStateException("No manifest.json in " + zipLocation);
            }
            final var manifest = manifestReader.readManifest(() -> {
                try {
                    return zip.getInputStream(manifestEntry);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            return new Archive(
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
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            return reader.lines().collect(joining("\n"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    @Vetoed
    public class Cache {
        private final Map<String, CompletionStage<Archive>> cache = new ConcurrentHashMap<>();

        public CompletionStage<Archive> loadArchive(final String coords) {
            return cache.computeIfAbsent(coords, k -> {
                final var local = Paths.get(coords);
                if (Files.exists(local)) {
                    return completedFuture(read(local));
                }
                return mvn.findOrDownload(k).thenApply(ArchiveReader.this::read);
            });
        }
    }

    @Data
    @Vetoed
    public static class Archive {
        private final Manifest manifest;
        private final Map<String, String> descriptors;
    }
}
