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

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenTest {
    @Test
    void httpTimeoutIsAppliedToTheRequest(@TempDir final Path repository) {
        final var captured = new AtomicReference<HttpRequest>();
        try (final var client = new DelegatingHttpClient(null) {
            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
                captured.set(request);
                return CompletableFuture.failedFuture(new IOException("No network needed"));
            }
        }) {
            final var resolver = new Maven(
                    client,
                    new MavenConfiguration(
                            1234, repository.toString(), true, true, "https://example.invalid/", UNSET, UNSET, false));
            resolver.init();
            resolver.findAvailableVersions("org.example", "demo")
                    .toCompletableFuture()
                    .join();
            assertEquals(Duration.ofMillis(1234), captured.get().timeout().orElseThrow());
        }
    }

    @Test
    void resolveLocalWithoutDownloads(@TempDir final Path repository) throws Exception {
        final var artifact = repository.resolve("org/example/demo/1.0/demo-1.0.jar");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "local-artifact");
        final var resolver = new Maven(
                HttpClient.newHttpClient(),
                new MavenConfiguration(
                        30000,
                        repository.toString(),
                        true,
                        true,
                        "https://repo.maven.apache.org/maven2/",
                        UNSET,
                        UNSET,
                        false));
        resolver.init();
        assertEquals(
                artifact,
                resolver.findOrDownload("org.example:demo:1.0")
                        .toCompletableFuture()
                        .get());
        assertThrows(
                RuntimeException.class,
                () -> resolver.findOrDownload("org.example:missing:1.0")
                        .toCompletableFuture()
                        .join());
    }
}
