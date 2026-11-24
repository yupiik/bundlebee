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
package io.yupiik.bundlebee.documentation;

import static io.yupiik.bundlebee.documentation.JsonSupport.array;
import static io.yupiik.bundlebee.documentation.JsonSupport.object;
import static io.yupiik.bundlebee.documentation.JsonSupport.string;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.function.Function.identity;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class K8sJSONSchemasGenerator implements Runnable {
    private static final Pattern VERSION_SPLITTER = Pattern.compile("\\.");
    private static final Pattern COMMA = Pattern.compile(",");
    private final Logger log = Logger.getLogger(K8sJSONSchemasGenerator.class.getName());
    private final Path sourceBase;
    private final String tagsUrl;
    private final String urlTemplate;
    private final boolean force;
    private final int maxThreads;
    private final Function<HttpRequest.Builder, HttpRequest.Builder> setAuth;
    private final boolean skip;
    private final int[] minVersion;
    private final Path cache;
    private final boolean skipReactView;
    private final boolean skipPatchVersion;

    public K8sJSONSchemasGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.skip = !Boolean.parseBoolean(configuration.getOrDefault("minisite.actions.k8s.jsonschema", "false"));
        this.sourceBase = sourceBase;
        this.tagsUrl = requireNonNull(configuration.get("tagsUrl"), () -> "No tagsUrl in " + configuration);
        this.urlTemplate =
                requireNonNull(configuration.get("specUrlTemplate"), () -> "No specUrlTemplate in " + configuration);
        this.force = Boolean.parseBoolean(configuration.get("force"));
        this.skipReactView = Boolean.parseBoolean(configuration.get("skipReactView"));
        this.maxThreads = Integer.parseInt(configuration.get("maxThreads"));
        this.minVersion = parseVersion(configuration.get("minVersion"));
        this.skipPatchVersion = Boolean.parseBoolean(configuration.get("skipPatchVersion"));
        try {
            this.cache = Files.createDirectories(
                    sourceBase.resolve("content/_partials/generated/jsonschema/generated/cache"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final var token = configuration.getOrDefault("githubToken", System.getProperty("minisite.githubToken"));
        if (token != null && !token.isBlank()) {
            setAuth = b -> b.header("Authorization", "Bearer " + token);
        } else {
            setAuth = identity();
        }
    }

    @Override
    public void run() {
        if (skip) {
            Logger.getLogger(getClass().getName())
                    .info(() -> "Skipping " + getClass().getSimpleName());
            return;
        }
        final var httpClient = HttpClient.newBuilder().version(HTTP_1_1).build();
        final var concurrency = Math.max(
                maxThreads, Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())));
        final var tasks = Executors.newFixedThreadPool(concurrency, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
                return new Thread(r, getClass().getName() + "-" + counter.incrementAndGet());
            }
        });
        log.info(() -> "Using " + concurrency + " threads");
        final var errorCapture = new IllegalStateException("An error occurred during generation");
        final var awaited = new CopyOnWriteArrayList<Future<?>>();
        final var versions = new HashMap<String, Map.Entry<String, int[]>>();
        final var descriptorsPerVersion = new ConcurrentHashMap<String, Collection<Descriptor>>();
        final var container = ConfiguringContainer.of().start();
        try {
            final var json = container.lookup(JsonMapper.class).instance();
            final var root = Files.createDirectories(sourceBase.resolve("assets/generated/kubernetes/jsonschema"));
            for (final var version : fetchTags(httpClient, tagsUrl, json)) {
                if (version.startsWith("1.4.")
                        || version.startsWith("1.3.")
                        || version.startsWith("1.2.")
                        || version.startsWith("1.1.")
                        || version.startsWith("1.0.")
                        || version.startsWith("v0.")) {
                    log.fine(() -> "Skipping version without an openapi: " + version);
                    continue;
                }
                final var pVersion = parseVersion(version);
                boolean skipVersion = false;
                for (int i = 0; i < pVersion.length; i++) {
                    if (i >= minVersion.length) {
                        break;
                    }
                    if (pVersion[i] < minVersion[i]) {
                        skipVersion = true;
                        break;
                    }
                }
                if (skipVersion) {
                    log.fine(() -> "Skipping version (<minVersion): " + version);
                    continue;
                }
                versions.compute(
                        pVersion[0] + "." + (pVersion.length == 1 ? "0" : pVersion[1])
                                + (skipPatchVersion ? "" : ("." + (pVersion.length > 2 ? pVersion[2] : "0"))),
                        (key, previous) -> {
                            if (previous == null) {
                                return entry(version, pVersion);
                            }
                            final var previousPatch = previous.getValue().length > 2 ? previous.getValue()[2] : 0;
                            final var currentPatch = pVersion.length > 2 ? pVersion[2] : 0;
                            if (currentPatch - previousPatch > 0) {
                                return entry(version, pVersion);
                            }
                            return previous;
                        });
            }
            for (final var version : versions.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Map.Entry<String, int[]>>, Integer>comparing(
                                    it -> it.getValue().getValue()[0])
                            .thenComparing(i -> i.getValue().getValue().length >= 2
                                    ? i.getValue().getValue()[1]
                                    : 0)
                            .thenComparing(i -> i.getValue().getValue().length >= 3
                                    ? i.getValue().getValue()[2]
                                    : 0))
                    .collect(toList())) {
                final var url =
                        urlTemplate.replace("{{version}}", version.getValue().getKey());
                awaited.add(tasks.submit(() -> {
                    synchronized (errorCapture) {
                        if (errorCapture.getSuppressed().length > 0) {
                            log.warning(() -> "Cancelling task \'" + url + "\' since there is(are) error(s).");
                            return;
                        }
                    }
                    try {
                        descriptorsPerVersion.put(
                                version.getKey(),
                                generate(
                                        Files.createDirectories(root.resolve(version.getKey())),
                                        url,
                                        httpClient,
                                        json,
                                        version.getKey()));
                    } catch (final IOException | RuntimeException ioe) {
                        log.log(SEVERE, ioe, ioe::getMessage);
                        synchronized (errorCapture) {
                            errorCapture.addSuppressed(ioe);
                        }
                        throw new IllegalStateException(ioe);
                    } catch (final InterruptedException e) {
                        log.log(SEVERE, e, e::getMessage);
                        synchronized (errorCapture) {
                            errorCapture.addSuppressed(e);
                        }
                        Thread.currentThread().interrupt();
                    }
                }));
            }
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tasks.shutdown();
            try {
                for (final var future : awaited) {
                    try {
                        future.get();
                    } catch (final ExecutionException e) {
                        log.log(SEVERE, e, e::getMessage);
                        synchronized (errorCapture) {
                            errorCapture.addSuppressed(e);
                        }
                    }
                }
                if (!tasks.awaitTermination(1, MINUTES)) {
                    log.warning(() -> "Wrong interruption of generation task");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                container.close();
            }
        }
        if (descriptorsPerVersion.values().stream().allMatch(Collection::isEmpty)) {
            throw new IllegalStateException("No Kubernetes schemas were generated", errorCapture);
        }
        // now generate the index page
        final var index =
                sourceBase.resolve("content/generated/kubernetes/jsonschema").resolve("index.adoc");
        final var sorted = descriptorsPerVersion.entrySet().stream()
                .map(it -> entry(entry(parseVersion(it.getKey()), it.getKey()), it.getValue()))
                .sorted(Comparator.<Map.Entry<Map.Entry<int[], String>, Collection<Descriptor>>, Integer>comparing(
                                it -> it.getKey().getKey()[0])
                        .thenComparing(i ->
                                i.getKey().getKey().length >= 2 ? i.getKey().getKey()[1] : 0)
                        .thenComparing(i ->
                                i.getKey().getKey().length >= 3 ? i.getKey().getKey()[2] : 0))
                .collect(toList());
        try {
            Files.writeString(
                    index,
                    // latest first
                    "= Kubernetes Descriptors Index\n" + "\n"
                            + "WARNING: due to Github Pages limitations we have to prune the version from time to time. "
                            + "We try to always keep up the maintained versions but very old versions can disappear.\n"
                            + "\n" + "++++\n" + "<div class=\"d-flex justify-content-center g-3 mb-4\">\n"
                            + "    <div class=\"col-md-4\">\n"
                            + "        <label class=\"form-label fw-semibold\">Filter by Version</label>\n"
                            + "        <select id=\"versionFilter\" class=\"form-control\">\n"
                            + "            <option value=\"\">All Versions</option>\n"
                            + sorted.stream()
                                    .sorted(
                                            Comparator
                                                    .<Map.Entry<Map.Entry<int[], String>, Collection<Descriptor>>,
                                                            String>
                                                            comparing(e ->
                                                            e.getKey().getValue())
                                                    .reversed())
                                    .map(it -> "            <option value=\""
                                            + it.getKey().getValue() + "\">Version "
                                            + it.getKey().getValue() + "</option>\n")
                                    .collect(joining())
                            + "        </select>\n" + "    </div>\n" + "    <div class=\"col-md-4\">\n"
                            + "        <label class=\"form-label fw-semibold\">Search Descriptor</label>\n"
                            + "        <input type=\"text\" id=\"descriptorFilter\" class=\"form-control\" placeholder=\"type to filter...\">\n"
                            + "    </div>\n" + "</div>\n"
                            + "<ul class=\"list-group\" style=\"margin-left: 0 !important\" id=\"descriptorList\">\n"
                            + sorted.stream()
                                    .map(it -> "    <!-- Version " + it.getKey().getValue() + " -->\n"
                                            + it.getValue().stream()
                                                    .sorted(Comparator.comparing(Descriptor::getKind)
                                                            .thenComparing(i -> {
                                                                return -versionWeight(i);
                                                            }))
                                                    .map(d -> {
                                                        final var linkBase =
                                                                it.getKey().getValue() + "/" + d.getVersion() + "/"
                                                                        + d.getKind();
                                                        return "    <li class=\"list-group-item descriptor-item d-flex\" data-version=\""
                                                                + it.getKey().getValue() + "\" data-kind=\""
                                                                + d.getKind() + "\" style=\"display: block;\">\n"
                                                                + "      <strong class=\"pr-2\">" + d.getKind()
                                                                + "</strong> " + d.getVersion() + "\n"
                                                                + "      <div class=\"schema-links ml-auto\">\n"
                                                                + "        <a href=\"" + linkBase
                                                                + ".jsonschema.html\" target=\"_blank\" class=\"pr-1\">\n"
                                                                + "          <i class=\"fas fa-file-code\"></i> HTML\n"
                                                                + "        </a>\n" + "        <a href=\"" + linkBase
                                                                + ".jsonschema.json\" target=\"_blank\" class=\"pr-1\">\n"
                                                                + "          <i class=\"fas fa-code\"></i> JSON\n"
                                                                + "        </a>\n" + "        <a href=\"" + linkBase
                                                                + ".jsonschema.raw.json\" target=\"_blank\" class=\"pr-1\">\n"
                                                                + "          <i class=\"fas fa-file\"></i> Raw JSON\n"
                                                                + "        </a>\n" + "      </div>\n" + "    </li>\n";
                                                    })
                                                    .collect(joining()))
                                    .collect(joining())
                            + "</ul>\n" + "\n" + "<script>\n"
                            + "    const versionFilter = document.getElementById(\'versionFilter\');\n"
                            + "    const descriptorFilter = document.getElementById(\'descriptorFilter\');\n"
                            + "    const items = document.querySelectorAll(\'.descriptor-item\');\n"
                            + "    function getQueryParam(name) {\n"
                            + "        const params = new URLSearchParams(window.location.search);\n"
                            + "        return params.get(name);\n" + "    }\n"
                            + "    function setQueryParam(name, value) {\n"
                            + "        const params = new URLSearchParams(window.location.search);\n"
                            + "        if (value === \"\" || value == null) {\n" + "            params.delete(name);\n"
                            + "        } else {\n" + "            params.set(name, value);\n" + "        }\n"
                            + "        const newUrl = window.location.pathname + \"?\" + params.toString();\n"
                            + "        window.history.replaceState({}, \"\", newUrl);\n" + "    }\n"
                            + "    function applyFilters() {\n"
                            + "        const versionValue = versionFilter.value.toLowerCase();\n"
                            + "        const descValue = descriptorFilter.value.toLowerCase();\n"
                            + "        let first = true;\n" + "        items.forEach(item => {\n"
                            + "            const matchesVersion = versionValue === \"\" || item.dataset.version === versionValue;\n"
                            + "            const matchesText = item.textContent.toLowerCase().includes(descValue);\n"
                            + "            if (matchesVersion && matchesText) {\n"
                            + "              item.classList.remove(\'d-none\');\n"
                            + "              item.classList.add(\'d-flex\');\n"
                            + "              if (first) { item.classList.add(\'first-version\'); }\n"
                            + "              first = false;\n" + "            } else {\n"
                            + "              item.classList.add(\'d-none\');\n"
                            + "              item.classList.remove(\'d-flex\');\n"
                            + "              item.classList.remove(\'first-version\');\n" + "            }\n"
                            + "        });\n" + "    }\n" + "    const qpVersion = getQueryParam(\"v\");\n"
                            + "    if (qpVersion && versionFilter.querySelector(`option[value=\"${qpVersion}\"]`)) {\n"
                            + "        versionFilter.value = qpVersion;\n" + "        setTimeout(applyFilters);\n"
                            + "    }\n" + "    versionFilter.addEventListener(\'change\', () => {\n"
                            + "        setQueryParam(\"v\", versionFilter.value);\n" + "        applyFilters();\n"
                            + "    });\n" + "    descriptorFilter.addEventListener(\'input\', applyFilters);\n"
                            + "</script>\n" + "++++\n");
        } catch (final IOException e) {
            errorCapture.addSuppressed(e);
        }
        if (errorCapture.getSuppressed().length > 0) {
            throw new IllegalStateException(Stream.of(errorCapture.getSuppressed())
                    .map(e -> {
                        final var out = new ByteArrayOutputStream();
                        try (var ps = new PrintStream(out)) {
                            e.printStackTrace(ps);
                        }
                        return out.toString(StandardCharsets.UTF_8);
                    })
                    .collect(joining("\n- ", "\n- ", "")));
        }
    }

    private int versionWeight(Descriptor i) {
        try {
            var version = i.getVersion();
            if (version.startsWith("v")) {
                version = version.substring(1);
            }
            final var bIdx = version.indexOf("beta");
            if (version.contains("beta")) {
                return Integer.parseInt(version.substring(0, bIdx)) * 1000000
                        + 1000 * Integer.parseInt(version.substring(bIdx + "beta".length()));
            }
            final var aIdx = version.indexOf("alpha");
            if (version.contains("alpha")) {
                return Integer.parseInt(version.substring(0, aIdx)) * 1000000
                        + Integer.parseInt(version.substring(aIdx + "alpha".length()));
            }
            return Integer.parseInt(version) * 1000000;
        } catch (final NumberFormatException nfe) {
            return 0;
        }
    }

    private int[] parseVersion(final String version) {
        final int sep = version.indexOf('-');
        if (sep > 0) {
            return parseVersion(version.substring(0, sep));
        }
        if (version.startsWith("v")) {
            return parseVersion(version.substring(1));
        }
        return Stream.of(VERSION_SPLITTER.split(version))
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private Iterable<String> fetchTags(final HttpClient httpClient, final String uri, final JsonMapper json)
            throws IOException, InterruptedException {
        return () -> new Iterator<>() {
            private URI next = URI.create(uri + "?per_page=100");
            private Iterator<String> delegate;

            @Override
            public boolean hasNext() {
                if (delegate != null && delegate.hasNext()) {
                    return true;
                }
                if (next == null) {
                    return false;
                }
                return doHasNext(3);
            }

            private boolean doHasNext(final int retries) {
                try {
                    log.info(() -> "Fetching " + next);
                    final var response = httpClient.send(
                            setAuth.apply(HttpRequest.newBuilder()
                                            .GET()
                                            .uri(next)
                                            .version(HTTP_1_1)
                                            .header("accept", "application/json"))
                                    .build(),
                            ofString());
                    switch (response.statusCode()) {
                        case 200:
                            // all good
                            break;
                        case 503:
                            if (retries > 0) {
                                Thread.sleep(5000);
                                return doHasNext(retries - 1);
                            }
                            throw new IllegalStateException("Invalid response: " + response + ": " + response.body());
                        case 403:
                            final var reset = rateLimitReset(response);
                            if (reset != null) {
                                Thread.sleep(Math.max(
                                        1,
                                        Duration.between(OffsetDateTime.now(), reset)
                                                .toMillis()));
                            }
                            log.warning(() ->
                                    "Rate limit hit, skipping for this run: " + response + "\n" + "X-RateLimit-Limit="
                                            + response.headers()
                                                    .firstValue("X-RateLimit-Limit")
                                                    .orElse("?") + "\n" + "X-RateLimit-Reset=" + reset);
                            return false;
                        default:
                            throw new IllegalStateException("Invalid response: " + response + ": " + response.body());
                    }
                    final List<Tag> array =
                            json.fromString(new Types.ParameterizedTypeImpl(List.class, Tag.class), response.body());
                    next = response.headers().allValues("link").stream()
                            .flatMap(it -> Stream.of(COMMA.split(it)))
                            .map(String::strip)
                            .filter(it -> it.endsWith("rel=\"next\""))
                            .findFirst()
                            .map(it -> URI.create(it.substring(it.indexOf("<") + 1, it.indexOf(">"))))
                            .orElse(null);
                    delegate =
                            // keep only releases
                            array.stream()
                                    .map(Tag::name)
                                    .filter(it ->
                                            !it.contains("-alpha.") && !it.contains("-beta.") && !it.contains("-rc."))
                                    .iterator();
                    if (!delegate.hasNext()) {
                        return hasNext();
                    }
                    return true;
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            public String next() {
                return delegate.next();
            }
        };
    }

    @JsonModel
    public record Tag(String name) {}

    private ZonedDateTime rateLimitReset(final HttpResponse<?> response) {
        return response.headers()
                .firstValue("X-RateLimit-Reset")
                .map(Long::parseLong)
                .map(Instant::ofEpochSecond)
                .map(i -> i.atZone(ZoneOffset.systemDefault()))
                .map(i -> {
                    log.info(() -> "Rate limit reset: " + i);
                    return i;
                })
                .orElse(null);
    }

    private Collection<Descriptor> generate(
            final Path root,
            final String url,
            final HttpClient httpClient,
            final JsonMapper json,
            final String versionName)
            throws IOException, InterruptedException {
        log.info(() -> "Fetching \'" + url + "\'");
        final var cached = cache.resolve(versionName.replace('/', '_') + ".json");
        final var descriptors = new HashSet<Descriptor>();
        final Map<String, Object> spec;
        if (Files.notExists(cached)) {
            final var response = retry(5, () -> {
                final var thread = Thread.currentThread();
                final var oldName = thread.getName();
                thread.setName(oldName + "[" + url + "]");
                try {
                    return httpClient.send(
                            setAuth.apply(HttpRequest.newBuilder()
                                            .GET()
                                            .uri(URI.create(url))
                                            .header("accept-encoding", "gzip")
                                            .version(HTTP_1_1))
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    thread.setName(oldName);
                }
            });
            switch (response.statusCode()) {
                case 200:
                    // all good
                    break;
                case 404:
                    log.info(() -> "No spec for \'" + url + "\'");
                    return List.of();
                case 403:
                    final var reset = rateLimitReset(response);
                    if (reset != null) {
                        Thread.sleep(Math.max(
                                1, Duration.between(OffsetDateTime.now(), reset).toMillis()));
                    }
                    throw new IllegalStateException("Rate limited response: " + response);
                default:
                    try (var r = response.body()) {
                        throw new IllegalStateException(
                                "Invalid response: " + response + ": " + new String(r.readAllBytes(), UTF_8) + "\n"
                                        + response.headers().map());
                    }
            }
            try (var reader = new java.io.InputStreamReader(
                    response.headers()
                                    .firstValue("content-encoding")
                                    .map(it -> it.contains("gzip"))
                                    .orElse(false)
                            ? new GZIPInputStream(response.body())
                            : response.body(),
                    UTF_8)) {
                spec = object(json.read(Object.class, reader));
            }
            Files.writeString(cached, json.toString(spec));
        } else {
            try (var reader = Files.newBufferedReader(cached)) {
                spec = object(json.read(Object.class, reader));
            } catch (final RuntimeException e) {
                Files.deleteIfExists(cached); // was corrupted, cleanup
                throw e;
            }
        }
        final var definitions = object(spec, "definitions").entrySet().stream()
                .filter(it -> it.getValue() instanceof Map<?, ?>)
                .collect(toMap(Map.Entry::getKey, it -> object(it.getValue())));
        for (final var definition : definitions.entrySet()) {
            final var obj = definition.getValue();
            final var meta = obj.get("x-kubernetes-group-version-kind");
            if (!(meta instanceof List<?>)) {
                continue;
            }
            for (final var entry : array(meta)) {
                final var currentMeta = object(entry);
                if (!currentMeta.containsKey("kind") || !currentMeta.containsKey("version")) {
                    continue;
                }
                final var version = string(currentMeta, "version");
                final var kind = string(currentMeta, "kind");
                final var filename = kind + ".jsonschema.json";
                final var react = kind + ".jsonschema.adoc";
                final var versioned =
                        Files.createDirectories(root.resolve(version)).resolve(filename);
                final var versionedHtml = Files.createDirectories(sourceBase
                                .resolve("content/generated/kubernetes/jsonschema")
                                .resolve(versionName)
                                .resolve(version))
                        .resolve(react);
                final var raw = versioned.getParent().resolve(kind + ".jsonschema.raw.json");
                final var versionless = root.resolve(filename);
                final var versionlessHtml = Files.createDirectories(sourceBase
                                .resolve("content/generated/kubernetes/jsonschema")
                                .resolve(versionName))
                        .resolve(react);
                descriptors.add(new Descriptor(kind, version));
                String jsonString = null;
                if (force || !Files.exists(versioned)) {
                    jsonString = doResolve(json, definitions, obj);
                    Files.writeString(versioned, jsonString);
                    log.info(() -> "Wrote \'" + versioned + "\'");
                }
                if (force || !Files.exists(raw)) {
                    Files.writeString(raw, json.toString(obj));
                    log.info(() -> "Wrote \'" + raw + "\'");
                }
                if (force || !Files.exists(versionless)) {
                    if (jsonString == null) {
                        jsonString = doResolve(json, definitions, obj);
                    }
                    Files.writeString(versionless, jsonString);
                    log.info(() -> "Wrote \'" + versionless + "\'");
                }
                String html = null;
                if (!skipReactView && (force || !Files.exists(versionedHtml))) {
                    html = renderForHtml(versionName, kind, version, versioned);
                    Files.writeString(versionedHtml, html);
                    log.info(() -> "Wrote \'" + versionedHtml + "\'");
                }
                // todo: refine to handle v1/v2 comparison
                if (!skipReactView
                        && ((force || !Files.exists(versionlessHtml))
                                && !versionName.contains("beta")
                                && !versionName.contains("alpha"))) {
                    final var content = html == null ? renderForHtml(versionName, kind, version, versioned) : html;
                    Files.writeString(versionlessHtml, content.replace("\"../../../../", "\"../../../"));
                    log.info(() -> "Wrote \'" + versionlessHtml + "\'");
                }
            }
        }
        return descriptors;
    }

    private String renderForHtml(
            final String versionName, final String kind, final String version, final Path versionned)
            throws IOException {
        final var relative = "../../../../";
        return "= " + kind + " " + versionName + "\n" + "\n" + "++++\n" + "<div id=\"main\"></div>\n" + "<script>\n"
                + "window.jsonSchemaViewerOpts = {\n" + "    base: \"../" + relative + "\",\n" + "    schema: "
                + Files.readString(versionned, UTF_8) + ",\n" + "};\n" + "</script>\n" + "<script src=\"" + relative
                + "js/kubernetes.schema.js?v=0\"></script>\n" + "++++";
    }

    private <T> T retry(final int max, final Supplier<T> supplier) {
        for (int i = 0; i < max; i++) {
            try {
                return supplier.get();
            } catch (final RuntimeException ie) {
                if (i == max - 1) {
                    throw ie;
                }
                log.warning("An error occurred, step will be retried (" + ie.getMessage() + ")");
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("unlikely");
    }

    private String doResolve(
            final JsonMapper json, final Map<String, Map<String, Object>> definitions, final Map<String, Object> obj) {
        return json.toString(resolveRefs(definitions, obj, new HashSet<>()));
    }

    private Object resolveRefs(
            final Map<String, Map<String, Object>> definitions,
            final Object value,
            final java.util.Set<String> visiting) {
        if (value instanceof List<?>) {
            return array(value).stream()
                    .map(it -> resolveRefs(definitions, it, visiting))
                    .collect(toList());
        }
        if (!(value instanceof Map<?, ?>)) {
            return value;
        }
        final var root = object(value);
        final var current = new TreeMap<String, Object>();
        final var ref = (String) root.get("$ref");
        if (ref != null) {
            if (!ref.startsWith("#/definitions/")) {
                throw new IllegalArgumentException("Wrong ref: \'" + ref + "\'");
            }
            final var key = ref.substring("#/definitions/".length());
            if (key.substring(key.lastIndexOf('.') + 1).startsWith("JSON") || !visiting.add(key)) {
                current.put("type", "object");
            } else {
                current.putAll(object(resolveRefs(
                        definitions,
                        requireNonNull(definitions.get(key), () -> "Didn\'t find ref \'" + ref + "\'"),
                        visiting)));
                visiting.remove(key);
            }
        }
        root.forEach((key, entry) -> {
            if (!"$ref".equals(key) && !key.startsWith("x-")) {
                current.put(key, resolveRefs(definitions, entry, visiting));
            }
        });
        return current;
    }

    private static class Descriptor {
        private final String kind;
        private final String version;

        public String getKind() {
            return this.kind;
        }

        public String getVersion() {
            return this.version;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Descriptor other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getKind(), other.getKind())
                    && Objects.equals(this.getVersion(), other.getVersion());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof K8sJSONSchemasGenerator.Descriptor;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getKind(), this.getVersion());
        }

        @Override
        public String toString() {
            return "K8sJSONSchemasGenerator.Descriptor(kind=" + this.getKind() + ", version=" + this.getVersion() + ")";
        }

        public Descriptor(final String kind, final String version) {
            this.kind = kind;
            this.version = version;
        }
    }
}
