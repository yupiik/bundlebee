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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.Collections.list;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

@Log
@ApplicationScoped
public class ArchiveReader {
    @Inject
    private ManifestReader manifestReader;

    @Inject
    private MavenResolver mvn;

    public Archive read(final Path zipLocation) {
        log.finest(() -> "Reading " + zipLocation);
        try (final ZipFile zip = new ZipFile(zipLocation.toFile())) {
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
            return cache.computeIfAbsent(coords, k -> mvn.findOrDownload(k)
                    .thenApply(ArchiveReader.this::read));
        }
    }

    @Data
    @Vetoed
    public static class Archive {
        private final Manifest manifest;
        private final Map<String, String> descriptors;
    }
}
