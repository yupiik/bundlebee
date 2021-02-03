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

    private String readAll(final ZipFile zip, final ZipEntry entry) {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            return reader.lines().collect(joining("\n"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    @Vetoed
    public static class Archive {
        private final Manifest manifest;
        private final Map<String, String> descriptors;
    }
}
