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
package io.yupiik.bundlebee.helm;

import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.lang.spi.PasswordResolver;
import lombok.extern.java.Log;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Downloads remote Helm charts from HTTP/HTTPS URIs or resolves file:// URIs.
 * Supports optional basic authentication with Maven server credentials.
 */
@Log
@Dependent
public class HelmChartDownloader {

    @Inject
    @BundleBee
    private HttpClient httpClient;

    @Inject
    @BundleBee
    private ScheduledExecutorService executor;

    @Inject
    private PasswordResolver passwordResolver;

    public HelmChartDownloader() {
        // CDI proxy
    }

    public HelmChartDownloader(final PasswordResolver passwordResolver, final HttpClient httpClient, final ScheduledExecutorService executor) {
        this.passwordResolver = passwordResolver;
        this.httpClient = httpClient;
        this.executor = executor;
    }

    /**
     * Resolve a chart URI to a local directory.
     *
     * @param chartUri  the chart URI (http/https/file/oci)
     * @param username  optional username for authentication
     * @param password  optional password (can use maven:serverId syntax)
     * @return a future resolving to the local directory containing the extracted chart
     */
    public CompletionStage<Path> resolve(final String chartUri, final String username, final String password) {
        final var uri = URI.create(chartUri);
        final var scheme = uri.getScheme();

        if ("file".equals(scheme)) {
            return CompletableFuture.completedFuture(Path.of(uri));
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return downloadHttp(uri, username, password);
        }
        if ("oci".equals(scheme)) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "OCI registry support is not yet implemented. Use http/https or file URIs."));
        }
        // Assume it's a local path
        return CompletableFuture.completedFuture(Path.of(chartUri));
    }

    private CompletionStage<Path> downloadHttp(final URI uri, final String username, final String password) {
        try {
            final var tempDir = Files.createTempDirectory("bundlebee-helm-");
            final var tgzFile = tempDir.resolve("chart.tgz");

            final var requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET();

            final var authHeader = buildAuthHeader(username, password);
            if (authHeader != null) {
                requestBuilder.header("Authorization", authHeader);
            }

            return httpClient.sendAsync(
                            requestBuilder.build(),
                            HttpResponse.BodyHandlers.ofInputStream())
                    .thenComposeAsync(response -> {
                        if (response.statusCode() != 200) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Failed to download chart from " + uri + ": HTTP " + response.statusCode()));
                        }
                        try {
                            final var in = response.body();
                            Files.copy(in, tgzFile);
                            in.close();
                        } catch (final IOException e) {
                            return CompletableFuture.failedFuture(e);
                        }
                        return CompletableFuture.completedFuture(extractWithCommonsCompress(tgzFile, tempDir));
                    }, executor);
        } catch (final IOException e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Failed to create temp directory for " + uri, e));
        }
    }

    private String buildAuthHeader(final String username, final String password) {
        if (username == null && password == null) {
            return null;
        }

        final var effectiveUsername = username;
        final var effectivePassword = resolvePassword(password);

        if (effectiveUsername != null && effectivePassword != null) {
            final var credentials = effectiveUsername + ":" + effectivePassword;
            final var encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            return "Basic " + encoded;
        }
        return null;
    }

    private String resolvePassword(final String password) {
        if (password == null) {
            return null;
        }
        if (password.startsWith("maven:")) {
            final var serverId = password.substring("maven:".length());
            return passwordResolver.resolveServerPassword(serverId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Maven server '" + serverId + "' not found in settings.xml"));
        }
        return password;
    }

    private Path extractWithCommonsCompress(final Path tgzFile, final Path targetDir) {
        try {
            final var chartDir = targetDir.resolve("chart");
            Files.createDirectories(chartDir);

            // Use Java's built-in GZIPInputStream for decompression (commons-compress 1.28.0
            // strict extra field parsing rejects some valid gzip headers)
            try (final var tarInput = new TarArchiveInputStream(
                    new java.util.zip.GZIPInputStream(new java.io.FileInputStream(tgzFile.toFile())))) {
                TarArchiveEntry entry;
                while ((entry = tarInput.getNextTarEntry()) != null) {
                    final var name = entry.getName();

                    // Skip root directory and normalize path
                    final var normalName = name.startsWith("./") ? name.substring(2) : name;
                    if (normalName.isEmpty()) {
                        continue;
                    }

                    final var entryPath = chartDir.resolve(normalName);

                    // Prevent zip slip
                    if (!entryPath.normalize().startsWith(chartDir.normalize())) {
                        log.warning("Skipping potentially unsafe path: " + name);
                        continue;
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        try (final var out = Files.newOutputStream(entryPath)) {
                            final var buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = tarInput.read(buffer, 0, buffer.length)) != -1) {
                                out.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                }
            }

            // If the tgz contains a single directory, use that as the chart dir
            try (final var entries = Files.list(chartDir)) {
                final var subdirs = entries
                        .filter(Files::isDirectory)
                        .collect(java.util.stream.Collectors.toList());
                final var files = Files.list(chartDir)
                        .filter(p -> !Files.isDirectory(p))
                        .collect(java.util.stream.Collectors.toList());
                if (subdirs.size() == 1 && files.isEmpty()) {
                    return subdirs.get(0);
                }
            }

            return chartDir;
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to extract chart archive '" + tgzFile + "'.", e);
        }
    }
}
