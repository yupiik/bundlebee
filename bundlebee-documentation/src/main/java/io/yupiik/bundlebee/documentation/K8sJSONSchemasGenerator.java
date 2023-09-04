/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.function.Function.identity;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Log
public class K8sJSONSchemasGenerator implements Runnable {
    private final Path sourceBase;
    private final String tagsUrl;
    private final String urlTemplate;
    private final boolean force;
    private final int maxThreads;
    private final Function<HttpRequest.Builder, HttpRequest.Builder> setAuth;
    private final boolean skip;

    public K8sJSONSchemasGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.skip = !Boolean.parseBoolean(configuration.getOrDefault("minisite.actions.k8s.jsonschema", "false"));
        this.sourceBase = sourceBase;
        this.tagsUrl = requireNonNull(configuration.get("tagsUrl"), () -> "No tagsUrl in " + configuration);
        this.urlTemplate = requireNonNull(configuration.get("specUrlTemplate"), () -> "No specUrlTemplate in " + configuration);
        this.force = Boolean.parseBoolean(configuration.get("force"));
        this.maxThreads = Integer.parseInt(configuration.get("maxThreads"));

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
        try {
            final var root = Files.createDirectories(sourceBase.resolve("assets/generated/kubernetes/jsonschema"));
            for (final var version : fetchTags(httpClient, tagsUrl, jsonReaderFactory)) {
                if (version.startsWith("1.4.") || version.startsWith("1.3.") ||
                        version.startsWith("1.2.") || version.startsWith("1.1.") ||
                        version.startsWith("1.0.") || version.startsWith("v0.")) {
                    log.fine(() -> "Skipping version without an openapi: " + version);
                    continue;
                }

                final var url = urlTemplate.replace("{{version}}", version);
                awaited.add(tasks.submit(() -> {
                    synchronized (errorCapture) {
                        if (errorCapture.getSuppressed().length > 0) {
                            log.warning(() -> "Cancelling task '" + url + "' since there is(are) error(s).");
                            return;
                        }
                    }
                    try {
                        generate(
                                Files.createDirectories(root.resolve(version)), url, httpClient,
                                jsonBuilderFactory, jsonReaderFactory, jsonWriterFactory);
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
                        case 403:
                            log.warning(() -> "Rate limit hit, skipping for this run: " + response + "\n" +
                                    "X-RateLimit-Limit=" +
                                    response.headers()
                                            .firstValue("X-RateLimit-Limit")
                                            .orElse("?") + "\n" +
                                    "X-RateLimit-Reset=" +
                                    response.headers()
                                            .firstValue("X-RateLimit-Reset")
                                            .map(Long::parseLong)
                                            .map(Instant::ofEpochSecond)
                                            .map(i -> i.atZone(ZoneOffset.systemDefault()))
                                            .orElse(null));
                            return false;
                        default:
                            throw new IllegalStateException("Invalid response: " + response);
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
                        return hasNext(); // check next if exists (unlikely)
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

    private void generate(final Path root, final String url, final HttpClient httpClient,
                          final JsonBuilderFactory jsonBuilderFactory, final JsonReaderFactory jsonReaderFactory,
                          final JsonWriterFactory jsonWriterFactory) throws IOException, InterruptedException {
        log.info(() -> "Fetching '" + url + "'");
        final var response = retry(3, () -> {
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
                return;
            default:
                throw new IllegalStateException("Invalid response: " + response);
        }

        final JsonObject spec;
        try (final var reader = jsonReaderFactory.createReader(
                response.headers().firstValue("content-encoding")
                        .map(it -> it.contains("gzip"))
                        .orElse(false) ?
                        new GZIPInputStream(response.body()) :
                        response.body())) {
            spec = reader.readObject();
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
                final var versionned = Files.createDirectories(root.resolve(version)).resolve(filename);
                final var raw = versionned.getParent().resolve(kind + ".jsonschema.raw.json");
                final var versionless = root.resolve(filename);

                String jsonString = null;
                if (force || !Files.exists(versionned)) {
                    jsonString = doResolve(jsonBuilderFactory, jsonWriterFactory, definitions, obj);
                    Files.writeString(versionned, jsonString);
                    log.info(() -> "Wrote '" + versionned + "'");
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
            }
        }
    }

    private <T> T retry(final int max, final Supplier<T> supplier) {
        for (int i = 0; i < max; i++) {
            try {
                return supplier.get();
            } catch (final RuntimeException ie) {
                try {
                    Thread.sleep(500);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                if (i == max - 1) {
                    throw ie;
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
}
