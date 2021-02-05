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

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.SEVERE;
import static lombok.AccessLevel.PRIVATE;

@Log
@ApplicationScoped
public class MavenResolver {
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    @Inject
    @Description("When fetching a dependency using HTTP, the connection timeout for this dependency.")
    @ConfigProperty(name = "bundlebee.maven.http.connectTimeout", defaultValue = "30000")
    private int connectTimeout;

    @Inject
    @Description("Where to cache maven dependencies. If set to `auto`, `$HOME/.m2/repository` is used.")
    @ConfigProperty(name = "bundlebee.maven.cache", defaultValue = "auto")
    private String m2CacheConfig;

    @Inject
    @Description("Default release repository.")
    @ConfigProperty(name = "bundlebee.maven.repositories.release", defaultValue = "https://repo.maven.apache.org/maven2/")
    private String releaseRepository;

    @Inject
    @Description("Default snapshot repository, not set by default.")
    @ConfigProperty(name = "bundlebee.maven.repositories.snapshot", defaultValue = "unset")
    private String snapshotRepository;

    @Inject
    @Description("Enables to disable the download, i.e. ensure it runs only with local maven repository.")
    @ConfigProperty(name = "bundlebee.maven.repositories.downloads.enabled", defaultValue = "false")
    private boolean canDownload;

    @Inject
    @BundleBee
    private HttpClient client;

    private SAXParserFactory factory;
    private Path m2;
    private final ConcurrentMap<String, Semaphore> locks = new ConcurrentHashMap<>();

    @PostConstruct
    private void init() {
        if ("unset".equals(snapshotRepository)) {
            snapshotRepository = null;
        }
        m2 = m2Home();

        factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
    }

    public CompletionStage<Path> findOrDownload(final String url) {
        final var lock = locks.computeIfAbsent(url, k -> new Semaphore(1));
        try {
            lock.acquire();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            return doFind(removeRepoIfPresent(url))
                    .whenComplete((p, e) -> lock.release());
        } catch (final MalformedURLException e) {
            lock.release();
            throw new IllegalArgumentException(e);
        }
    }

    private String removeRepoIfPresent(final String url) {
        final int sep = url.indexOf('!') + 1;
        if (sep != 0) {
            return url.substring(0, sep) + url.substring(sep).replace(":", "/");
        }
        return url;
    }

    private CompletionStage<Path> doFind(final String raw) throws MalformedURLException {
        final var segments = raw.substring(raw.indexOf('!') + 1).split(":");
        if (segments.length < 3) {
            throw new MalformedURLException("Invalid path: " + raw);
        }

        final var group = segments[0];
        if (group.trim().isEmpty()) {
            throw new MalformedURLException("Invalid groupId: " + raw);
        }

        final var artifact = segments[1];
        if (artifact.trim().isEmpty()) {
            throw new MalformedURLException("Invalid artifactId: " + raw);
        }

        final String type;
        if (segments.length >= 4 && segments[3].trim().length() > 0) {
            type = segments[3];
        } else {
            type = "jar";
        }

        final String fullClassifier;
        if (segments.length >= 5 && segments[4].trim().length() > 0) {
            fullClassifier = "-" + segments[4];
        } else {
            fullClassifier = null;
        }

        String version = segments[2];
        if (version.trim().isEmpty()) {
            throw new MalformedURLException("Invalid artifactId: " + raw);
        }

        final Path file = m2.resolve(toRelativePath(null, group, artifact, version, fullClassifier, type));
        if (Files.exists(file)) {
            log.finest(() -> "Found " + file + ", skipping download");
            return completedFuture(file);
        }

        final String repoBase;
        final var sep = raw.lastIndexOf('!');
        if (sep > 0) {
            repoBase = raw.substring(0, sep);
        } else {
            repoBase = getDefaultRepository(raw);
        }

        return findVersion(repoBase, group, artifact, version)
                .thenCompose(actualVersion -> download(
                        group, artifact, actualVersion, fullClassifier, type,
                        toRelativePath(repoBase, group, artifact, actualVersion, fullClassifier, type)));
    }

    private CompletionStage<String> findVersion(final String repoBase, final String group, final String artifact, final String version) throws MalformedURLException {
        final var base = repoBase == null || repoBase.isEmpty() ? "" : (repoBase + (!repoBase.endsWith("/") ? "/" : ""));
        if (("LATEST".equals(version) || "LATEST-SNAPSHOT".equals(version)) && base.startsWith("http")) {
            final String meta = base + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
            return client.sendAsync(
                    HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(meta))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("Invalid " + meta + " response: " + response);
                        }
                        try (final var stream = new ByteArrayInputStream(response.body())) {
                            return extractRealVersion(version, stream);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .exceptionally(error -> {
                        log.log(SEVERE, error.getMessage() + "\nWill default to version=" + version + '.', error);
                        return version;
                    });
        }
        if (version.endsWith("-SNAPSHOT") && base.startsWith("http")) {
            final String meta = base + group.replace('.', '/') + "/" + artifact + "/" + version + "/maven-metadata.xml";
            return client.sendAsync(
                    HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(meta))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("Invalid " + meta + " response: " + response);
                        }
                        try (final var stream = new ByteArrayInputStream(response.body())) {
                            return extractLastSnapshotVersion(version, stream);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .exceptionally(error -> {
                        log.log(SEVERE, error.getMessage() + "\nWill default to version=" + version + '.', error);
                        return version;
                    });
        }
        return completedFuture(version);
    }

    private CompletionStage<Path> download(final String group, final String artifact, final String version,
                                           final String fullClassifier, final String type, final String url) {
        if (!canDownload) {
            throw new IllegalStateException("Download are disabled so can't download '" + url + "'");
        }
        log.info(() -> "Downloading " + url);
        return client.sendAsync(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create(url))
                        .build(),
                HttpResponse.BodyHandlers.ofFile(m2.resolve(toRelativePath(null, group, artifact, version, fullClassifier, type))))
                .thenApply(it -> {
                    if (it.statusCode() != 200) {
                        throw new IllegalStateException("An error occured downloading " + url);
                    }
                    return it.body();
                });
    }

    private String getDefaultRepository(final String raw) {
        String base;
        if (raw.contains(SNAPSHOT_SUFFIX) && raw.contains("apache")) {
            base = requireNonNull(snapshotRepository, "No snapshot repository set.");
        } else {
            base = releaseRepository;
        }
        return base;
    }

    private Path m2Home() {
        return Optional.of(m2CacheConfig)
                .filter(it -> !"auto".equals(it))
                .map(Paths::get)
                .orElseGet(() -> {
                    final var m2 = Paths.get(System.getProperty("user.home")).resolve(".m2/repository");
                    final var settingsXml = m2.resolve("settings.xml");
                    if (Files.exists(settingsXml)) {
                        try {
                            // faster to do that than using dom
                            final String content = Files.readString(settingsXml, StandardCharsets.UTF_8);
                            final int start = content.indexOf("<localRepository>");
                            String localM2RepositoryFromSettings = null;
                            if (start > 0) {
                                final int end = content.indexOf("</localRepository>", start);
                                if (end > 0) {
                                    localM2RepositoryFromSettings = content.substring(start + "<localRepository>".length(), end);
                                }
                            }
                            if (localM2RepositoryFromSettings != null && !localM2RepositoryFromSettings.isEmpty()) {
                                return Paths.get(localM2RepositoryFromSettings);
                            }
                        } catch (final Exception ignore) {
                            // fallback on default local path
                        }
                    }
                    return m2;
                });
    }

    private String toRelativePath(final String base, final String group, final String artifact, final String version,
                                  final String classifier, final String type) {
        final var builder = new StringBuilder(base == null ? "" : (base.endsWith("/") ? base : (base + '/')));
        builder.append(group.replace('.', '/')).append('/');
        builder.append(artifact).append('/');
        builder.append(version).append('/');
        builder.append(artifact).append('-').append(version);
        if (classifier != null && !classifier.isBlank()) {
            builder.append(classifier);
        }
        return builder.append('.').append(type).toString();
    }

    private String extractRealVersion(String version, final InputStream stream) {
        final QuickMvnMetadataParser handler = new QuickMvnMetadataParser();
        try {
            final SAXParser parser = factory.newSAXParser();
            parser.parse(stream, handler);
            if (!version.endsWith(SNAPSHOT_SUFFIX) && handler.release != null) {
                version = handler.release.toString();
            } else if (handler.latest != null) {
                version = handler.latest.toString();
            }
        } catch (final Exception e) {
            // no-op: not parseable so ignoring
        }
        return version;
    }

    private String extractLastSnapshotVersion(final String defaultVersion, final InputStream metadata) {
        final QuickMvnMetadataParser handler = new QuickMvnMetadataParser();
        try {
            final SAXParser parser = factory.newSAXParser();
            parser.parse(metadata, handler);
            if (handler.timestamp != null && handler.buildNumber != null) {
                return defaultVersion.substring(0, defaultVersion.length() - SNAPSHOT_SUFFIX.length())
                        + "-" + handler.timestamp + "-" + handler.buildNumber;
            }
        } catch (final Exception e) {
            // no-op: not parseable so ignoring
        }
        return defaultVersion;
    }

    @NoArgsConstructor(access = PRIVATE)
    private static class QuickMvnMetadataParser extends DefaultHandler {
        private StringBuilder timestamp;
        private StringBuilder buildNumber;
        private StringBuilder latest;
        private StringBuilder release;
        private StringBuilder text;

        @Override
        public void startElement(final String uri, final String localName,
                                 final String qName, final Attributes attributes) {
            if ("timestamp".equalsIgnoreCase(qName)) {
                timestamp = new StringBuilder();
                text = timestamp;
            } else if ("buildNumber".equalsIgnoreCase(qName)) {
                buildNumber = new StringBuilder();
                text = buildNumber;
            } else if ("latest".equalsIgnoreCase(qName)) {
                latest = new StringBuilder();
                text = latest;
            } else if ("release".equalsIgnoreCase(qName)) {
                release = new StringBuilder();
                text = release;
            }
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            if (text != null) {
                text.append(new String(ch, start, length));
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            text = null;
        }
    }
}
