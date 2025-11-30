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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.java.Log;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonBuilderFactory;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReaderFactory;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
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

@Log
public class K8sJSONSchemasGenerator implements Runnable {
    private final Pattern versionSplitter = Pattern.compile("\\.");
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
        this.urlTemplate = requireNonNull(configuration.get("specUrlTemplate"), () -> "No specUrlTemplate in " + configuration);
        this.force = Boolean.parseBoolean(configuration.get("force"));
        this.skipReactView = Boolean.parseBoolean(configuration.get("skipReactView"));
        this.maxThreads = Integer.parseInt(configuration.get("maxThreads"));
        this.minVersion = parseVersion(configuration.get("minVersion"));
        this.skipPatchVersion = Boolean.parseBoolean(configuration.get("skipPatchVersion"));
        try {
            this.cache = Files.createDirectories(sourceBase.resolve("content/_partials/generated/jsonschema/generated/cache"));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        final var token = configuration.get("githubToken");
        if (token != null && !token.isBlank()) {
            setAuth = b -> b.header("Authorization", "Bearer " + token);
        } else {
            setAuth = identity();
        }
    }

    @Override
    public void run() {
        if (skip) {
            Logger.getLogger(getClass().getName()).info(() -> "Skipping " + getClass().getSimpleName());
            return;
        }
        final var httpClient = HttpClient.newBuilder().version(HTTP_1_1).build();
        final var jsonReaderFactory = Json.createReaderFactory(Map.of());
        final var jsonBuilderFactory = Json.createBuilderFactory(Map.of());
        final var jsonWriterFactory = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
        final var concurrency = Math.max(maxThreads, Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())));
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
        try {
            final var root = Files.createDirectories(sourceBase.resolve("assets/generated/kubernetes/jsonschema"));
            for (final var version : fetchTags(httpClient, tagsUrl, jsonReaderFactory)) {
                if (version.startsWith("1.4.") || version.startsWith("1.3.") ||
                        version.startsWith("1.2.") || version.startsWith("1.1.") ||
                        version.startsWith("1.0.") || version.startsWith("v0.")) {
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
                        pVersion[0] + "." + (pVersion.length == 1 ? "0" : pVersion[1]) + (skipPatchVersion ? "" : ("." + (pVersion.length > 2 ? pVersion[2] : "0"))),
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
                    .sorted(Comparator.<Map.Entry<String, Map.Entry<String, int[]>>, Integer>comparing(it -> it.getValue().getValue()[0])
                            .thenComparing(i -> i.getValue().getValue().length >= 2 ? i.getValue().getValue()[1] : 0)
                            .thenComparing(i -> i.getValue().getValue().length >= 3 ? i.getValue().getValue()[2] : 0))
                    .collect(toList())) {
                final var url = urlTemplate.replace("{{version}}", version.getValue().getKey());
                awaited.add(tasks.submit(() -> {
                    synchronized (errorCapture) {
                        if (errorCapture.getSuppressed().length > 0) {
                            log.warning(() -> "Cancelling task '" + url + "' since there is(are) error(s).");
                            return;
                        }
                    }
                    try {
                        descriptorsPerVersion.put(version.getKey(), generate(
                                Files.createDirectories(root.resolve(version.getKey())), url, httpClient,
                                jsonBuilderFactory, jsonReaderFactory, jsonWriterFactory, version.getKey()));
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
            }
        }


        // now generate the index page
        final var index = sourceBase.resolve("content/generated/kubernetes/jsonschema").resolve("index.adoc");
        final var sorted = descriptorsPerVersion.entrySet().stream()
                .map(it -> entry(entry(parseVersion(it.getKey()), it.getKey()), it.getValue()))
                .sorted(Comparator.<Map.Entry<Map.Entry<int[], String>, Collection<Descriptor>>, Integer>comparing(it -> it.getKey().getKey()[0])
                        .thenComparing(i -> i.getKey().getKey().length >= 2 ? i.getKey().getKey()[1] : 0)
                        .thenComparing(i -> i.getKey().getKey().length >= 3 ? i.getKey().getKey()[2] : 0))
                .collect(toList());
        try {
            Files.writeString(index, "= Kubernetes Descriptors Index\n" +
                    "\n" +
                    "WARNING: due to Github Pages limitations we have to prune the version from time to time. " +
                    "We try to always keep up the maintained versions but very old versions can disappear.\n" +
                    "\n" +
                    "++++\n" +
                    "<div class=\"d-flex justify-content-center g-3 mb-4\">\n" +
                    "    <div class=\"col-md-4\">\n" +
                    "        <label class=\"form-label fw-semibold\">Filter by Version</label>\n" +
                    "        <select id=\"versionFilter\" class=\"form-control\">\n" +
                    "            <option value=\"\">All Versions</option>\n" +
                    sorted.stream()
                            .sorted(Comparator.<Map.Entry<Map.Entry<int[], String>, Collection<Descriptor>>, String>comparing(e -> e.getKey().getValue()).reversed())
                            .map(it -> "            <option value=\"" + it.getKey().getValue() + "\">Version " + it.getKey().getValue() + "</option>\n")
                            .collect(joining()) +
                    "        </select>\n" +
                    "    </div>\n" +
                    "    <div class=\"col-md-4\">\n" +
                    "        <label class=\"form-label fw-semibold\">Search Descriptor</label>\n" +
                    "        <input type=\"text\" id=\"descriptorFilter\" class=\"form-control\" placeholder=\"type to filter...\">\n" +
                    "    </div>\n" +
                    "</div>\n" +
                    "<ul class=\"list-group\" style=\"margin-left: 0 !important\" id=\"descriptorList\">\n" +
                    sorted.stream()
                            .map(it -> "    <!-- Version " + it.getKey().getValue() + " -->\n" +
                                    it.getValue().stream()
                                            .sorted(Comparator.comparing(Descriptor::getKind)
                                                    .thenComparing(i -> {
                                                        // latest first
                                                        return -versionWeight(i);
                                                    }))
                                            .map(d -> {
                                                final var linkBase = it.getKey().getValue() + "/" + d.getVersion() + "/" + d.getKind();
                                                return "    <li class=\"list-group-item descriptor-item d-flex\" data-version=\"" + it.getKey().getValue() + "\" data-kind=\"" + d.getKind() + "\" style=\"display: block;\">\n" +
                                                        "      <strong class=\"pr-2\">" + d.getKind() + "</strong> " + d.getVersion() + "\n" +
                                                        "      <div class=\"schema-links ml-auto\">\n" +
                                                        "        <a href=\"" + linkBase + ".jsonschema.html\" target=\"_blank\" class=\"pr-1\">\n" +
                                                        "          <i class=\"fas fa-file-code\"></i> HTML\n" +
                                                        "        </a>\n" +
                                                        "        <a href=\"" + linkBase + ".jsonschema.json\" target=\"_blank\" class=\"pr-1\">\n" +
                                                        "          <i class=\"fas fa-code\"></i> JSON\n" +
                                                        "        </a>\n" +
                                                        "        <a href=\"" + linkBase + ".jsonschema.raw.json\" target=\"_blank\" class=\"pr-1\">\n" +
                                                        "          <i class=\"fas fa-file\"></i> Raw JSON\n" +
                                                        "        </a>\n" +
                                                        "      </div>\n" +
                                                        "    </li>\n";
                                            })
                                            .collect(joining()))
                            .collect(joining()) +
                    "</ul>\n" +
                    "\n" +
                    "<script>\n" +
                    "    const versionFilter = document.getElementById('versionFilter');\n" +
                    "    const descriptorFilter = document.getElementById('descriptorFilter');\n" +
                    "    const items = document.querySelectorAll('.descriptor-item');\n" +
                    "    function getQueryParam(name) {\n" +
                    "        const params = new URLSearchParams(window.location.search);\n" +
                    "        return params.get(name);\n" +
                    "    }\n" +
                    "    function setQueryParam(name, value) {\n" +
                    "        const params = new URLSearchParams(window.location.search);\n" +
                    "        if (value === \"\" || value == null) {\n" +
                    "            params.delete(name);\n" +
                    "        } else {\n" +
                    "            params.set(name, value);\n" +
                    "        }\n" +
                    "        const newUrl = window.location.pathname + \"?\" + params.toString();\n" +
                    "        window.history.replaceState({}, \"\", newUrl);\n" +
                    "    }\n" +
                    "    function applyFilters() {\n" +
                    "        const versionValue = versionFilter.value.toLowerCase();\n" +
                    "        const descValue = descriptorFilter.value.toLowerCase();\n" +
                    "        let first = true;\n" +
                    "        items.forEach(item => {\n" +
                    "            const matchesVersion = versionValue === \"\" || item.dataset.version === versionValue;\n" +
                    "            const matchesText = item.textContent.toLowerCase().includes(descValue);\n" +
                    "            if (matchesVersion && matchesText) {\n" +
                    "              item.classList.remove('d-none');\n" +
                    "              item.classList.add('d-flex');\n" +
                    "              if (first) { item.classList.add('first-version'); }\n" +
                    "              first = false;\n" +
                    "            } else {\n" +
                    "              item.classList.add('d-none');\n" +
                    "              item.classList.remove('d-flex');\n" +
                    "              item.classList.remove('first-version');\n" +
                    "            }\n" +
                    "        });\n" +
                    "    }\n" +
                    "    const qpVersion = getQueryParam(\"v\");\n" +
                    "    if (qpVersion && versionFilter.querySelector(`option[value=\"${qpVersion}\"]`)) {\n" +
                    "        versionFilter.value = qpVersion;\n" +
                    "        setTimeout(applyFilters);\n" +
                    "    }\n" +
                    "    versionFilter.addEventListener('change', () => {\n" +
                    "        setQueryParam(\"v\", versionFilter.value);\n" +
                    "        applyFilters();\n" +
                    "    });\n" +
                    "    descriptorFilter.addEventListener('input', applyFilters);\n" +
                    "</script>\n" +
                    "++++\n");
        } catch (final IOException e) {
            errorCapture.addSuppressed(e);
        }

        if (errorCapture.getSuppressed().length > 0) {
            throw new IllegalStateException(Stream.of(errorCapture.getSuppressed())
                    .map(e -> {
                        final var out = new ByteArrayOutputStream();
                        try (final var ps = new PrintStream(out)) {
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
                return Integer.parseInt(version.substring(0, bIdx)) * 1_000_000 + 1_000 * Integer.parseInt(version.substring(bIdx + "beta".length()));
            }

            final var aIdx = version.indexOf("alpha");
            if (version.contains("alpha")) {
                return Integer.parseInt(version.substring(0, aIdx)) * 1_000_000 + Integer.parseInt(version.substring(aIdx + "alpha".length()));
            }
            return Integer.parseInt(version) * 1_000_000;
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
        return Stream.of(versionSplitter.split(version)).mapToInt(Integer::parseInt).toArray();
    }

    private Iterable<String> fetchTags(final HttpClient httpClient, final String uri, final JsonReaderFactory jsonReaderFactory) throws IOException, InterruptedException {
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
                            setAuth.apply(
                                            HttpRequest.newBuilder()
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
                                Thread.sleep(5_000);
                                return doHasNext(retries - 1);
                            }
                            throw new IllegalStateException("Invalid response: " + response + ": " + response.body());
                        case 403:
                            final var reset = rateLimitReset(response);
                            if (reset != null) {
                                Thread.sleep(Math.max(1, Duration.between(OffsetDateTime.now(), reset).toMillis()));
                            }
                            log.warning(() -> "Rate limit hit, skipping for this run: " + response + "\n" +
                                    "X-RateLimit-Limit=" +
                                    response.headers()
                                            .firstValue("X-RateLimit-Limit")
                                            .orElse("?") + "\n" +
                                    "X-RateLimit-Reset=" +
                                    reset);
                            return false;
                        default:
                            throw new IllegalStateException("Invalid response: " + response + ": " + response.body());
                    }

                    final JsonArray array;
                    try (final var reader = jsonReaderFactory.createReader(new StringReader(response.body()))) {
                        array = reader.readArray();
                    }

                    next = response.headers().allValues("link").stream()
                            .flatMap(it -> Stream.of(it.split(",")))
                            .map(String::strip)
                            .filter(it -> it.endsWith("rel=\"next\""))
                            .findFirst()
                            .map(it -> URI.create(it.substring(it.indexOf("<") + 1, it.indexOf(">"))))
                            .orElse(null);
                    delegate = array.stream()
                            .map(JsonValue::asJsonObject)
                            .map(i -> i.getString("name"))
                            // keep only releases
                            .filter(it -> !it.contains("-alpha.") && !it.contains("-beta.") && !it.contains("-rc."))
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

    private Collection<Descriptor> generate(final Path root, final String url, final HttpClient httpClient,
                                            final JsonBuilderFactory jsonBuilderFactory, final JsonReaderFactory jsonReaderFactory,
                                            final JsonWriterFactory jsonWriterFactory, final String versionName) throws IOException, InterruptedException {
        log.info(() -> "Fetching '" + url + "'");
        final var cached = cache.resolve(versionName.replace('/', '_') + ".json");
        final var descriptors = new HashSet<Descriptor>();

        final JsonObject spec;
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
                    log.info(() -> "No spec for '" + url + "'");
                    return List.of();
                case 403:
                    final var reset = rateLimitReset(response);
                    if (reset != null) {
                        Thread.sleep(Math.max(1, Duration.between(OffsetDateTime.now(), reset).toMillis()));
                    }
                    throw new IllegalStateException("Rate limited response: " + response);
                default:
                    try (final var r = response.body()) {
                        throw new IllegalStateException("Invalid response: " + response + ": " + new String(r.readAllBytes(), UTF_8) + "\n" + response.headers().map());
                    }
            }

            try (final var reader = jsonReaderFactory.createReader(
                    response.headers().firstValue("content-encoding")
                            .map(it -> it.contains("gzip"))
                            .orElse(false) ?
                            new GZIPInputStream(response.body()) :
                            response.body())) {
                spec = reader.readObject();
            }
            Files.writeString(cached, spec.toString());
        } else {
            try (final var reader = jsonReaderFactory.createReader(Files.newBufferedReader(cached))) {
                spec = reader.readObject();
            } catch (final RuntimeException e) {
                Files.deleteIfExists(cached); // was corrupted, cleanup
                throw e;
            }
        }

        final var definitions = spec.getJsonObject("definitions").entrySet().stream()
                .filter(it -> it.getValue().getValueType() == JsonValue.ValueType.OBJECT)
                .collect(toMap(Map.Entry::getKey, it -> it.getValue().asJsonObject()));

        for (final var definition : definitions.entrySet()) {
            final var obj = definition.getValue().asJsonObject();
            final var meta = obj.get("x-kubernetes-group-version-kind");
            if (meta == null || meta.getValueType() != JsonValue.ValueType.ARRAY) {
                continue;
            }

            for (final var entry : meta.asJsonArray()) {
                final var currentMeta = entry.asJsonObject();
                if (!currentMeta.containsKey("kind") || !currentMeta.containsKey("version")) {
                    continue;
                }

                final var version = currentMeta.getString("version");
                final var kind = currentMeta.getString("kind");

                final var filename = kind + ".jsonschema.json";
                final var react = kind + ".jsonschema.adoc";
                final var versioned = Files.createDirectories(root.resolve(version)).resolve(filename);
                final var versionedHtml = Files.createDirectories(sourceBase.resolve("content/generated/kubernetes/jsonschema").resolve(versionName).resolve(version)).resolve(react);
                final var raw = versioned.getParent().resolve(kind + ".jsonschema.raw.json");
                final var versionless = root.resolve(filename);
                final var versionlessHtml = Files.createDirectories(sourceBase.resolve("content/generated/kubernetes/jsonschema").resolve(versionName)).resolve(react);

                descriptors.add(new Descriptor(kind, version));

                String jsonString = null;
                if (force || !Files.exists(versioned)) {
                    jsonString = doResolve(jsonBuilderFactory, jsonWriterFactory, definitions, obj);
                    Files.writeString(versioned, jsonString);
                    log.info(() -> "Wrote '" + versioned + "'");
                }
                if (force || !Files.exists(raw)) {
                    Files.writeString(raw, toString(jsonWriterFactory, obj));
                    log.info(() -> "Wrote '" + raw + "'");
                }
                if (force || !Files.exists(versionless)) {
                    if (jsonString == null) {
                        jsonString = doResolve(jsonBuilderFactory, jsonWriterFactory, definitions, obj);
                    }
                    Files.writeString(versionless, jsonString);
                    log.info(() -> "Wrote '" + versionless + "'");
                }

                String html = null;
                if (!skipReactView && (force || !Files.exists(versionedHtml))) {
                    html = renderForHtml(versionName, kind, version, versioned);
                    Files.writeString(versionedHtml, html);
                    log.info(() -> "Wrote '" + versionedHtml + "'");
                }

                // todo: refine to handle v1/v2 comparison
                if (!skipReactView && ((force || !Files.exists(versionlessHtml)) && !versionName.contains("beta") && !versionName.contains("alpha"))) {
                    final var content = html == null ? renderForHtml(versionName, kind, version, versioned) : html;
                    Files.writeString(versionlessHtml, content.replace("\"../../../../", "\"../../../"));
                    log.info(() -> "Wrote '" + versionlessHtml + "'");
                }
            }
        }

        return descriptors;
    }

    private String renderForHtml(final String versionName, final String kind, final String version, final Path versionned) throws IOException {
        final var relative = "../../../../";
        return "= " + kind + " " + versionName + "\n" +
                "\n" +
                "++++\n" +
                "<div id=\"main\"></div>\n" +
                "<script>\n" +
                "window.jsonSchemaViewerOpts = {\n" +
                "    base: \"../" + relative + "\",\n" +
                "    schema: " + Files.readString(versionned, UTF_8) + ",\n" +
                "};\n" +
                "</script>\n" +
                "<script src=\"" + relative + "js/kubernetes.schema.js?v=0\"></script>\n" +
                "++++";
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
                    Thread.sleep(1_000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("unlikely");
    }

    private String doResolve(final JsonBuilderFactory jsonBuilderFactory, final JsonWriterFactory jsonWriterFactory,
                             final Map<String, JsonObject> definitions, final JsonObject obj) {
        final var resolved = resolveRefs(jsonBuilderFactory, definitions, obj).build();
        final String jsonString = toString(jsonWriterFactory, resolved);
        if (jsonString.contains("$ref")) {
            throw new IllegalStateException("$ref should have been replaced: " + jsonString);
        }
        return jsonString;
    }

    private static String toString(final JsonWriterFactory jsonWriterFactory, final JsonObject resolved) {
        final var out = new StringWriter();
        try (final var writer = jsonWriterFactory.createWriter(out)) {
            writer.write(resolved);
        }
        return out.toString();
    }

    private JsonObjectBuilder resolveRefs(final JsonBuilderFactory jsonBuilderFactory, final Map<String, JsonObject> definitions, final JsonObject root) {
        final var current = jsonBuilderFactory.createObjectBuilder();

        for (final var entry : root.entrySet()) {
            switch (entry.getValue().getValueType()) {
                case OBJECT:
                    current.add(entry.getKey(), resolveRefs(jsonBuilderFactory, definitions, entry.getValue().asJsonObject()));
                    break;
                case ARRAY:
                    current.add(
                            entry.getKey(),
                            jsonBuilderFactory.createArrayBuilder(
                                            entry.getValue().asJsonArray().stream()
                                                    .map(it -> it.getValueType() == JsonValue.ValueType.OBJECT /* never another array in practise */ ?
                                                            resolveRefs(jsonBuilderFactory, definitions, it.asJsonObject()).build() :
                                                            it)
                                                    .collect(toList()))
                                    .build());
                    break;
                case STRING:
                    if ("$ref".equals(entry.getKey())) {
                        final var ref = JsonString.class.cast(entry.getValue()).getString();
                        final var schema = resolveRefs(
                                jsonBuilderFactory, definitions,
                                resolveRef(definitions, ref, jsonBuilderFactory));
                        current.addAll(schema);
                    } else if (!entry.getKey().startsWith("x-")) {
                        current.add(entry.getKey(), entry.getValue());
                    }
                    break;
                default:
                    current.add(entry.getKey(), entry.getValue());
            }
        }

        return current;
    }

    private JsonObject resolveRef(final Map<String, JsonObject> definitions, final String ref, final JsonBuilderFactory jsonBuilderFactory) {
        if (!ref.startsWith("#/definitions/")) {
            throw new IllegalArgumentException("Wrong ref: '" + ref + "'");
        }
        final var key = ref.substring("#/definitions/".length());

        // this one is recursive so "cut" it - only needed for CRD validation anyway
        // todo: handle it in jsonschema definitions - but added value is quite low since it is specific to one rarely used descriptor
        if (key.substring(key.lastIndexOf('.') + 1).startsWith("JSON"/*Schema**/)) {
            return jsonBuilderFactory.createObjectBuilder().add("type", "object").build();
        }

        return requireNonNull(definitions.get(key), () -> "Didn't find ref '" + ref + "'");
    }

    @Data
    @AllArgsConstructor
    private static class Descriptor {
        private final String kind;
        private final String version;
    }
}
