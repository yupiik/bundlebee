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
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.lang.spi.PasswordResolver;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Init;
import io.yupiik.tools.codec.simple.SimpleCodec;
import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

@ApplicationScoped
public class Maven implements PasswordResolver {
    private static final Pattern COLON = Pattern.compile(":");
    private final Logger log = Logger.getLogger(Maven.class.getName());

    public Maven(final HttpClient client, final MavenConfiguration configuration) {
        this.client = client;
        this.configuration = configuration;
    }

    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private Duration timeout;
    private String m2CacheConfig;
    private boolean preferCustomSettingsXml;
    private boolean forceCustomSettingsXml;
    private String releaseRepository;
    private String snapshotRepository;
    private String httpHeaders;
    private boolean canDownload;
    private final HttpClient client;
    private Path m2;
    private SAXParserFactory factory;
    private final ConcurrentMap<String, Semaphore> locks = new ConcurrentHashMap<>();
    private Map<String, Map<String, String>> headers;

    @Init
    protected void init() {
        this.timeout = Duration.ofMillis(configuration.timeout());
        this.m2CacheConfig = configuration.cache();
        this.preferCustomSettingsXml = configuration.preferCustomSettingsXml();
        this.forceCustomSettingsXml = configuration.forceCustomSettingsXml();
        this.releaseRepository = configuration.releaseRepository();
        this.snapshotRepository = configuration.snapshotRepository();
        this.httpHeaders = configuration.httpHeaders();
        this.canDownload = configuration.downloadsEnabled();
        if (UNSET.equals(snapshotRepository)) {
            snapshotRepository = null;
        }
        m2 = m2Home();
        factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        try {
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            // Fail at startup if the provider cannot enforce the parser-level restrictions.
            newSecureSAXParser();
        } catch (final ParserConfigurationException | SAXException | UnsupportedOperationException e) {
            throw new IllegalStateException("Unable to configure secure Maven XML parsing", e);
        }
        headers = UNSET.equals(httpHeaders) ? Map.of() : parseHttpHeaders();
    }

    private SAXParser newSecureSAXParser() throws ParserConfigurationException, SAXException {
        final var parser = factory.newSAXParser();
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return parser;
    }

    public Path ensureSettingsXml() {
        try {
            // this one also test our custom m2/settings.xml location
            return findSettingsXml();
        } catch (final IllegalArgumentException iae) {
            final var settingsXml = preferCustomSettingsXml || forceCustomSettingsXml
                    ? m2.resolve("settings.xml")
                    : Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml");
            try {
                Files.createDirectories(settingsXml.getParent());
                Files.writeString(
                        settingsXml,
                        "<settings>\n" + "  <servers>\n" + "  </servers>\n" + "</settings>\n" + "\n" + "",
                        StandardOpenOption.CREATE);
                log.info(() -> "Created " + settingsXml);
            } catch (final IOException ioe) {
                throw new IllegalStateException(ioe);
            }
            return settingsXml;
        }
    }

    public Optional<String> findMasterPassword(final Path settingsXml) {
        final var settingsSecurity = settingsXml.getParent().resolve("settings-security.xml");
        if (!Files.exists(settingsSecurity)) {
            return empty();
        }
        final var extractor = new MvnMasterExtractor();
        try (var is = Files.newInputStream(settingsSecurity)) {
            newSecureSAXParser().parse(is, extractor);
        } catch (final ParserConfigurationException | IOException | SAXException e) {
            throw new IllegalArgumentException(e);
        }
        return extractor.current == null
                ? empty()
                : of(extractor.current.toString().trim());
    }

    public Optional<Server> findServerPassword(final String serverId) {
        return findServerPassword(findSettingsXml(), serverId);
    }

    public Optional<Server> findServerPassword(final Path settings, final String serverId) {
        final var extractor =
                new MvnServerExtractor(findMasterPassword(settings).orElse(null), serverId, this::decryptPassword);
        try (var is = Files.newInputStream(settings)) {
            newSecureSAXParser().parse(is, extractor);
        } catch (final ParserConfigurationException | IOException | SAXException e) {
            throw new IllegalArgumentException(e);
        }
        return ofNullable(extractor.server);
    }

    @Override
    public Optional<String> resolveServerPassword(final String serverId) {
        return findServerPassword(serverId).map(Server::getPassword);
    }

    public CompletionStage<Path> findOrDownload(final String url) {
        final var lock = locks.computeIfAbsent(url, k -> new Semaphore(1));
        try {
            lock.acquire();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            return doFind(removeRepoIfPresent(url)).whenComplete((p, e) -> lock.release());
        } catch (final MalformedURLException e) {
            lock.release();
            throw new IllegalArgumentException(e);
        }
    }

    public CompletionStage<List<String>> findAvailableVersions(final String group, final String artifact) {
        return findAvailableVersions(releaseRepository, group, artifact).thenApply(Versions::getVersions);
    }

    public CompletionStage<Versions> findAvailableVersions(
            final String repository, final String group, final String artifact) {
        log.finest(() -> "Looking for available version of " + group + ":" + artifact);
        final var uri = URI.create(repository + (repository.endsWith("/") ? "" : "/") + group.replace('.', '/') + '/'
                + artifact + "/maven-metadata.xml");
        return client.sendAsync(
                        newHttpRequest(uri.getHost())
                                .GET()
                                .timeout(timeout)
                                .uri(uri)
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Invalid " + uri + " response: " + response);
                    }
                    try (var stream = new ByteArrayInputStream(response.body())) {
                        return extractVersions(stream);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .exceptionally(error -> {
                    log.log(FINEST, error.getMessage());
                    return new Versions(null, null, List.of());
                });
    }

    public GavParser extractGav(final InputStream metadata) {
        final GavParser handler = new GavParser();
        try {
            final SAXParser parser = newSecureSAXParser();
            parser.parse(metadata, handler);
            return handler;
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest.Builder newHttpRequest(final String host) {
        final var builder = HttpRequest.newBuilder();
        final var staticHeaders = headers.get(host);
        if (staticHeaders != null) {
            staticHeaders.forEach(builder::header);
        } else {
            try {
                findServerPassword("bundlebee." + host)
                        .ifPresent(s -> builder.header(
                                "Authorization",
                                s.getUsername() == null
                                        ? s.getPassword()
                                        : ("Basic "
                                                + Base64.getEncoder()
                                                        .encodeToString((s.getUsername() + ':' + s.getPassword())
                                                                .getBytes(StandardCharsets.UTF_8)))));
            } catch (final RuntimeException re) {
                log.log(FINEST, re.getMessage(), re);
            }
        }
        return builder;
    }

    private Path findSettingsXml() {
        var settings = preferCustomSettingsXml || forceCustomSettingsXml
                ? m2.resolve("settings.xml")
                : Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml");
        if (!Files.exists(settings)) {
            settings = preferCustomSettingsXml && !forceCustomSettingsXml
                    ? Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml")
                    : m2.resolve("settings.xml");
            if (!Files.exists(settings)) {
                throw new IllegalArgumentException(
                        "No " + settings + " found, ensure your credentials configuration is valid");
            }
        }
        return settings;
    }

    private String removeRepoIfPresent(final String url) {
        final int sep = url.indexOf('!') + 1;
        if (sep != 0) {
            return url.substring(0, sep) + url.substring(sep).replace(":", "/");
        }
        return url;
    }

    private CompletionStage<Path> doFind(final String raw) throws MalformedURLException {
        final var segments = COLON.split(raw.substring(raw.indexOf('!') + 1));
        if (segments.length < 3) {
            final var path = Paths.get(raw);
            if (Files.isDirectory(path)) {
                return completedFuture(path);
            }
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
        final Path file = m2.resolve(toRelativePath(null, group, artifact, version, fullClassifier, type, version));
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
                        group,
                        artifact,
                        actualVersion,
                        fullClassifier,
                        type,
                        toRelativePath(repoBase, group, artifact, actualVersion, fullClassifier, type, version),
                        version));
    }

    private CompletionStage<String> findVersion(
            final String repoBase, final String group, final String artifact, final String version) {
        final var base =
                repoBase == null || repoBase.isEmpty() ? "" : (repoBase + (!repoBase.endsWith("/") ? "/" : ""));
        if (("LATEST".equals(version) || "LATEST-SNAPSHOT".equals(version)) && base.startsWith("http")) {
            final var meta = URI.create(base + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml");
            return client.sendAsync(
                            newHttpRequest(meta.getHost()).GET().uri(meta).build(),
                            HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("Invalid " + meta + " response: " + response);
                        }
                        try (var stream = new ByteArrayInputStream(response.body())) {
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
            final var meta =
                    URI.create(base + group.replace('.', '/') + "/" + artifact + "/" + version + "/maven-metadata.xml");
            return client.sendAsync(
                            newHttpRequest(meta.getHost()).GET().uri(meta).build(),
                            HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("Invalid " + meta + " response: " + response);
                        }
                        try (var stream = new ByteArrayInputStream(response.body())) {
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

    private CompletionStage<Path> download(
            final String group,
            final String artifact,
            final String version,
            final String fullClassifier,
            final String type,
            final String url,
            final String rootVersion) {
        if (!canDownload) {
            throw new IllegalStateException("Download are disabled so can\'t download \'" + url + "\'");
        }
        return doDownload(group, artifact, version, fullClassifier, type, url, rootVersion);
    }

    public CompletionStage<Path> doDownload(
            final String group,
            final String artifact,
            final String version,
            final String fullClassifier,
            final String type,
            final String url,
            final String rootVersion) {
        log.info(() -> "Downloading " + url);
        final var uri = URI.create(url);
        final var target =
                m2.resolve(toRelativePath(null, group, artifact, version, fullClassifier, type, rootVersion));
        try {
            Files.createDirectories(target.getParent());
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
        return doDownload(uri, target);
    }

    public CompletionStage<Path> doDownload(final URI uri, final Path target) {
        return client.sendAsync(
                        newHttpRequest(uri.getHost()).GET().uri(uri).build(), HttpResponse.BodyHandlers.ofFile(target))
                .thenApply(it -> {
                    if (it.statusCode() != 200) {
                        throw new IllegalStateException("An error occured downloading " + uri);
                    }
                    return it.body();
                });
    }

    private String getDefaultRepository(final String raw) {
        String base;
        if (raw.contains(SNAPSHOT_SUFFIX)) {
            base = requireNonNull(snapshotRepository, "No snapshot repository set.");
        } else {
            base = releaseRepository;
        }
        return base;
    }

    private Path m2Home() {
        return ofNullable(m2CacheConfig)
                .filter(it -> !"auto".equals(it))
                .map(Paths::get)
                .orElseGet(() -> {
                    final var sp = System.getProperty("maven.repo.local");
                    if (sp != null) {
                        return Path.of(sp);
                    }
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
                                    localM2RepositoryFromSettings =
                                            content.substring(start + "<localRepository>".length(), end);
                                }
                            }
                            if (localM2RepositoryFromSettings != null && !localM2RepositoryFromSettings.isEmpty()) {
                                return Paths.get(localM2RepositoryFromSettings);
                            }
                        } catch (final Exception ignore) {
                        }
                    }
                    // fallback on default local path
                    return m2;
                });
    }

    public String toRelativePath(
            final String base,
            final String group,
            final String artifact,
            final String version,
            final String classifier,
            final String type,
            final String rootVersion) {
        final var builder = new StringBuilder(base == null ? "" : (base.endsWith("/") ? base : (base + '/')));
        builder.append(group.replace('.', '/')).append('/');
        builder.append(artifact).append('/');
        builder.append(rootVersion).append('/');
        builder.append(artifact).append('-').append(version);
        if (classifier != null && !classifier.isBlank()) {
            builder.append(classifier);
        }
        return builder.append('.').append(type).toString();
    }

    private String extractRealVersion(final String version, final InputStream stream) {
        final QuickMvnMetadataParser handler = new QuickMvnMetadataParser();
        try {
            final SAXParser parser = newSecureSAXParser();
            parser.parse(stream, handler);
            if (!version.endsWith(SNAPSHOT_SUFFIX) && handler.release != null) {
                return handler.release.toString();
            }
            if (handler.latest != null) {
                return handler.latest.toString();
            }
        } catch (final Exception e) {
        }
        // no-op: not parseable so ignoring
        return version;
    }

    private Map<String, Map<String, String>> parseHttpHeaders() {
        final var props = new Properties();
        ofNullable(httpHeaders).filter(it -> !UNSET.equals(it)).ifPresent(content -> {
            try (var reader = new StringReader(content)) {
                props.load(reader);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return props.stringPropertyNames().stream()
                .collect(toMap(
                        identity(),
                        key -> Stream.of(props.getProperty(key))
                                .filter(it -> !it.isBlank())
                                .map(property -> {
                                    final var sep = property.indexOf(' ');
                                    if (sep < 0) {
                                        return new AbstractMap.SimpleImmutableEntry<>(property, "");
                                    }
                                    return new AbstractMap.SimpleImmutableEntry<>(
                                            property.substring(0, sep).trim(),
                                            property.substring(sep + 1).trim());
                                })
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))));
    }

    private String extractLastSnapshotVersion(final String defaultVersion, final InputStream metadata) {
        final QuickMvnMetadataParser handler = new QuickMvnMetadataParser();
        try {
            final SAXParser parser = newSecureSAXParser();
            parser.parse(metadata, handler);
            if (handler.timestamp != null && handler.buildNumber != null) {
                return defaultVersion.substring(0, defaultVersion.length() - SNAPSHOT_SUFFIX.length()) + "-"
                        + handler.timestamp + "-" + handler.buildNumber;
            }
        } catch (final Exception e) {
        }
        // no-op: not parseable so ignoring
        return defaultVersion;
    }

    private Versions extractVersions(final InputStream metadata) {
        final VersionsExtractor handler = new VersionsExtractor();
        try {
            final SAXParser parser = newSecureSAXParser();
            parser.parse(metadata, handler);
            return new Versions(handler.latest, handler.release, handler.versions);
        } catch (final Exception e) {
            log.log(FINEST, e.getMessage(), e);
        }
        return new Versions(null, null, List.of());
    }

    public String createPassword(final String password, final String masterPassword) {
        return new SimpleCodec(SimpleCodecConfiguration.builder()
                        .masterPassword(masterPassword)
                        .build())
                .encrypt("auto".equals(password) ? UUID.randomUUID().toString() : password);
    }

    public String decryptPassword(final String value, final String pwd) {
        return value == null
                ? null
                : new SimpleCodec(SimpleCodecConfiguration.builder()
                                .masterPassword(pwd)
                                .build())
                        .decrypt(value);
    }

    private static class VersionsExtractor extends DefaultHandler {
        private String latest;
        private String release;
        private final List<String> versions = new ArrayList<>();
        private boolean versioningBlock;
        private boolean versionsBlock;
        private StringBuilder current;

        @Override
        public void startElement(
                final String uri, final String localName, final String qName, final Attributes attributes) {
            if ("versioning".equalsIgnoreCase(qName)) {
                versioningBlock = true;
            } else if (versioningBlock && "versions".equalsIgnoreCase(qName)) {
                versionsBlock = true;
            } else if (versionsBlock && "version".equalsIgnoreCase(qName)) {
                current = new StringBuilder();
            } else if (versioningBlock && ("release".equalsIgnoreCase(qName) || "latest".equalsIgnoreCase(qName))) {
                current = new StringBuilder();
            }
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            if (current != null) {
                current.append(new String(ch, start, length));
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            if ("versioning".equalsIgnoreCase(qName)) {
                versioningBlock = false;
            } else if (versioningBlock && "versions".equalsIgnoreCase(qName)) {
                versionsBlock = false;
            } else if (current != null && "release".equalsIgnoreCase(qName)) {
                release = current.toString();
                current = null;
            } else if (current != null && "latest".equalsIgnoreCase(qName)) {
                latest = current.toString();
                current = null;
            } else if (current != null && versionsBlock && "version".equalsIgnoreCase(qName)) {
                final var trimmed = current.toString().trim();
                if (!trimmed.isBlank()) {
                    versions.add(trimmed);
                }
                current = null;
            }
        }

        private VersionsExtractor() {}
    }

    private static class QuickMvnMetadataParser extends DefaultHandler {
        private StringBuilder timestamp;
        private StringBuilder buildNumber;
        private StringBuilder latest;
        private StringBuilder release;
        private StringBuilder text;

        @Override
        public void startElement(
                final String uri, final String localName, final String qName, final Attributes attributes) {
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

        private QuickMvnMetadataParser() {}
    }

    public static class GavParser extends DefaultHandler {
        private String group;
        private String artifact;
        private String version;
        private final LinkedList<String> tags = new LinkedList<>();
        private StringBuilder text;

        @Override
        public void startElement(
                final String uri, final String localName, final String qName, final Attributes attributes) {
            if ("groupId".equals(qName) || "artifactId".equals(qName) || "version".equals(qName)) {
                text = new StringBuilder();
            }
            tags.add(qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            if (text != null) {
                text.append(new String(ch, start, length));
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            tags.removeLast();
            if ("groupId".equals(qName) && "project".equals(tags.getLast())) {
                group = text.toString().trim();
            } else if ("artifactId".equals(qName) && "project".equals(tags.getLast())) {
                artifact = text.toString().trim();
            } else if ("version".equals(qName) && "project".equals(tags.getLast())) {
                version = text.toString().trim();
            } else if ("version".equals(qName)
                    && "parent".equals(tags.getLast())
                    && tags.size() == 2
                    && version == null) {
                version = text.toString().trim();
            } else if ("groupId".equals(qName)
                    && "parent".equals(tags.getLast())
                    && tags.size() == 2
                    && group == null) {
                group = text.toString().trim();
            }
            text = null;
        }

        private GavParser() {}

        public String getGroup() {
            return this.group;
        }

        public String getArtifact() {
            return this.artifact;
        }

        public String getVersion() {
            return this.version;
        }
    }

    private static class MvnServerExtractor extends DefaultHandler {
        private final String passphrase;
        private final String serverId;
        private final BiFunction<String, String, String> doDecrypt;
        private Server server;
        private String encryptedPassword;
        private boolean done;
        private StringBuilder current;

        private MvnServerExtractor(
                final String passphrase, final String serverId, final BiFunction<String, String, String> decipher) {
            this.doDecrypt = decipher;
            this.passphrase = decipher.apply(passphrase, "settings.security");
            this.serverId = serverId;
        }

        @Override
        public void startElement(
                final String uri, final String localName, final String qName, final Attributes attributes) {
            if ("server".equalsIgnoreCase(qName)) {
                if (!done) {
                    server = new Server();
                }
            } else if (server != null) {
                current = new StringBuilder();
            }
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            if (current != null) {
                current.append(new String(ch, start, length));
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            if (done) {
                // decrypt password only when the server is found
                server.setPassword(doDecrypt.apply(encryptedPassword, passphrase));
                return;
            }
            if ("server".equalsIgnoreCase(qName)) {
                if (server.getId().equals(serverId)) {
                    done = true;
                } else if (!done) {
                    server = null;
                    encryptedPassword = null;
                }
            } else if (server != null && current != null) {
                switch (qName) {
                    case "id":
                        server.setId(current.toString());
                        break;
                    case "username":
                        try {
                            server.setUsername(doDecrypt.apply(current.toString(), passphrase));
                        } catch (final RuntimeException re) {
                            server.setUsername(current.toString());
                        }
                        break;
                    case "password":
                        encryptedPassword = current.toString();
                        break;
                    default:
                }
                current = null;
            }
        }
    }

    private static class MvnMasterExtractor extends DefaultHandler {
        private StringBuilder current;

        @Override
        public void startElement(
                final String uri, final String localName, final String qName, final Attributes attributes) {
            if ("master".equalsIgnoreCase(qName)) {
                current = new StringBuilder();
            }
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            if (current != null) {
                current.append(new String(ch, start, length));
            }
        }
    }

    public static class Server {
        private String id;
        private String username;
        private String password;

        public Server() {}

        public String getId() {
            return this.id;
        }

        public String getUsername() {
            return this.username;
        }

        public String getPassword() {
            return this.password;
        }

        public void setId(final String id) {
            this.id = id;
        }

        public void setUsername(final String username) {
            this.username = username;
        }

        public void setPassword(final String password) {
            this.password = password;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Server other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getId(), other.getId())
                    && Objects.equals(this.getUsername(), other.getUsername())
                    && Objects.equals(this.getPassword(), other.getPassword());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof Maven.Server;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getId(), this.getUsername(), this.getPassword());
        }

        @Override
        public String toString() {
            return "Maven.Server(id=" + this.getId() + ", username=" + this.getUsername() + ", password="
                    + this.getPassword() + ")";
        }
    }

    public static class Versions {
        private final String latest;
        private final String release;
        private final List<String> versions;

        public Versions(final String latest, final String release, final List<String> versions) {
            this.latest = latest;
            this.release = release;
            this.versions = versions;
        }

        public String getLatest() {
            return this.latest;
        }

        public String getRelease() {
            return this.release;
        }

        public List<String> getVersions() {
            return this.versions;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Versions other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getLatest(), other.getLatest())
                    && Objects.equals(this.getRelease(), other.getRelease())
                    && Objects.equals(this.getVersions(), other.getVersions());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof Maven.Versions;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getLatest(), this.getRelease(), this.getVersions());
        }

        @Override
        public String toString() {
            return "Maven.Versions(latest=" + this.getLatest() + ", release=" + this.getRelease() + ", versions="
                    + this.getVersions() + ")";
        }
    }

    private final MavenConfiguration configuration;

    public Path getM2() {
        return this.m2;
    }
}
