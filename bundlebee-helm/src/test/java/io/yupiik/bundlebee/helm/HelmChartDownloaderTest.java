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

import io.yupiik.bundlebee.helm.test.http.SpyingResponseLocator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.talend.sdk.component.junit.http.api.HttpApiHandler;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.internal.impl.ResponseImpl;
import org.talend.sdk.component.junit.http.junit5.HttpApi;
import org.talend.sdk.component.junit.http.junit5.HttpApiInject;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@HttpApi(useSsl = false)
class HelmChartDownloaderTest {

    @HttpApiInject
    private HttpApiHandler<?> handler;

    private HttpClient createTrustAllHttpClient() throws Exception {
        final var trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(final X509Certificate[] chain, final String authType) {}

                    @Override
                    public void checkServerTrusted(final X509Certificate[] chain, final String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        final var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }

    @Test
    void downloadAndExtractChart(@TempDir final Path tempDir) throws Exception {
        final var tgzBytes = createChartTgz("test-chart", "1.0.0", "apiVersion: apps/v1\nkind: Deployment\n");

        handler.setResponseLocator(new SpyingResponseLocator("download") {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                if (request.method().equals("GET") && request.uri().contains("chart.tgz")) {
                    return Optional.of(new ResponseImpl(Map.of(), 200, tgzBytes));
                }
                return Optional.of(new ResponseImpl(Map.of(), 404, "{}".getBytes(StandardCharsets.UTF_8)));
            }
        });

        final var executor = Executors.newSingleThreadScheduledExecutor();
        final var downloader = new HelmChartDownloader(null, createTrustAllHttpClient(), executor);
        final var url = "http://localhost:" + handler.getPort() + "/charts/chart.tgz";
        final var chartDir = downloader.resolve(url, null, null).toCompletableFuture().get();
        executor.shutdown();

        assertNotNull(chartDir);
        assertTrue(Files.isDirectory(chartDir));

        final var chartYaml = chartDir.resolve("Chart.yaml");
        assertTrue(Files.exists(chartYaml), "Chart.yaml should exist after extraction");
        final var content = Files.readString(chartYaml);
        assertTrue(content.contains("name: test-chart"), "Chart.yaml should contain chart name");
    }

    @Test
    void downloadWithBasicAuth(@TempDir final Path tempDir) throws Exception {
        final var tgzBytes = createChartTgz("auth-chart", "2.0.0", "apiVersion: v1\nkind: Service\n");

        handler.setResponseLocator(new SpyingResponseLocator("download-auth") {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                if (request.method().equals("GET") && request.uri().contains("chart.tgz")) {
                    final var auth = request.headers().getOrDefault("Authorization", null);
                    if (auth != null && auth.startsWith("Basic ")) {
                        return Optional.of(new ResponseImpl(Map.of(), 200, tgzBytes));
                    }
                    return Optional.of(new ResponseImpl(Map.of(), 401, "Unauthorized".getBytes(StandardCharsets.UTF_8)));
                }
                return Optional.of(new ResponseImpl(Map.of(), 404, "{}".getBytes(StandardCharsets.UTF_8)));
            }
        });

        final var executor = Executors.newSingleThreadScheduledExecutor();
        final var downloader = new HelmChartDownloader(null, createTrustAllHttpClient(), executor);
        final var url = "http://localhost:" + handler.getPort() + "/charts/chart.tgz";
        final var chartDir = downloader.resolve(url, "user", "pass").toCompletableFuture().get();
        executor.shutdown();

        assertNotNull(chartDir);
        assertTrue(Files.isDirectory(chartDir));

        final var chartYaml = chartDir.resolve("Chart.yaml");
        assertTrue(Files.exists(chartYaml), "Chart.yaml should exist after auth download");
    }

    @Test
    void download404Throws(@TempDir final Path tempDir) throws Exception {
        handler.setResponseLocator(new SpyingResponseLocator("download-404") {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                return Optional.of(new ResponseImpl(Map.of(), 404, "Not Found".getBytes(StandardCharsets.UTF_8)));
            }
        });

        final var executor = Executors.newSingleThreadScheduledExecutor();
        final var downloader = new HelmChartDownloader(null, createTrustAllHttpClient(), executor);
        final var url = "http://localhost:" + handler.getPort() + "/charts/missing.tgz";

        final var future = downloader.resolve(url, null, null).toCompletableFuture();
        try {
            future.get();
            throw new AssertionError("Expected ExecutionException wrapping IllegalStateException for 404");
        } catch (final java.util.concurrent.ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException, "Should wrap IllegalStateException");
            assertTrue(e.getCause().getMessage().contains("HTTP 404"), "Should report HTTP 404 in error");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void fileUriResolvesToPath() throws Exception {
        final var executor = Executors.newSingleThreadScheduledExecutor();
        final var downloader = new HelmChartDownloader(null, null, executor);
        final var path = downloader.resolve("file:///tmp/my-chart", null, null).toCompletableFuture().get();
        executor.shutdown();
        assertEquals(Path.of("/tmp/my-chart"), path);
    }

    @Test
    void localPathResolvesDirectly() throws Exception {
        final var executor = Executors.newSingleThreadScheduledExecutor();
        final var downloader = new HelmChartDownloader(null, null, executor);
        final var path = downloader.resolve("/some/local/path", null, null).toCompletableFuture().get();
        executor.shutdown();
        assertEquals(Path.of("/some/local/path"), path);
    }

    @Test
    void ociSchemeThrows() throws Exception {
        final var executor = Executors.newSingleThreadScheduledExecutor();
        final var downloader = new HelmChartDownloader(null, null, executor);
        final var future = downloader.resolve("oci://registry.example.com/chart:1.0", null, null).toCompletableFuture();
        try {
            future.get();
            throw new AssertionError("Expected ExecutionException wrapping UnsupportedOperationException for oci");
        } catch (final java.util.concurrent.ExecutionException e) {
            assertTrue(e.getCause() instanceof UnsupportedOperationException, "Should wrap UnsupportedOperationException");
            assertTrue(e.getCause().getMessage().contains("OCI"), "Should mention OCI is not supported");
        } finally {
            executor.shutdown();
        }
    }

    private byte[] createChartTgz(final String chartName, final String version,
                                  final String templateContent) throws Exception {
        final var chartYaml = "apiVersion: v2\nname: " + chartName + "\nversion: " + version + "\n";
        final var deploymentYaml = templateContent;

        final var baos = new ByteArrayOutputStream();
        final var gzos = new GZIPOutputStream(baos);

        try (final var tarOutput = new TarArchiveOutputStream(gzos)) {
            // Add Chart.yaml
            final var chartYamlBytes = chartYaml.getBytes(StandardCharsets.UTF_8);
            final var chartYamlEntry = new TarArchiveEntry(chartName + "/Chart.yaml");
            chartYamlEntry.setSize(chartYamlBytes.length);
            tarOutput.putArchiveEntry(chartYamlEntry);
            tarOutput.write(chartYamlBytes);
            tarOutput.closeArchiveEntry();

            // Add deployment.yaml
            final var deploymentBytes = deploymentYaml.getBytes(StandardCharsets.UTF_8);
            final var deploymentEntry = new TarArchiveEntry(chartName + "/templates/deployment.yaml");
            deploymentEntry.setSize(deploymentBytes.length);
            tarOutput.putArchiveEntry(deploymentEntry);
            tarOutput.write(deploymentBytes);
            tarOutput.closeArchiveEntry();

            tarOutput.finish();
        }

        return baos.toByteArray();
    }
}
