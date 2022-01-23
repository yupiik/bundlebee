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
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Log
public class K8sJSONSchemasGenerator implements Runnable {
    private final Path sourceBase;
    private final String tagsUrl;
    private final String urlTemplate;
    private final boolean force;

    public K8sJSONSchemasGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.tagsUrl = requireNonNull(configuration.get("tagsUrl"), () -> "No tagsUrl in " + configuration);
        this.urlTemplate = requireNonNull(configuration.get("specUrlTemplate"), () -> "No specUrlTemplate in " + configuration);
        this.force = Boolean.parseBoolean(configuration.get("force"));
    }

    @Override
    public void run() {
        final var httpClient = HttpClient.newHttpClient();
        final var jsonReaderFactory = Json.createReaderFactory(Map.of());
        final var jsonBuilderFactory = Json.createBuilderFactory(Map.of());
        final var jsonWriterFactory = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
        try {
            final var root = Files.createDirectories(sourceBase.resolve("assets/generated/kubernetes/jsonschema"));
            for (final var version : fetchTags(httpClient, tagsUrl, jsonReaderFactory)) {
                final var url = urlTemplate.replace("{{version}}", version);
                generate(
                        Files.createDirectories(root.resolve(version)), url, httpClient,
                        jsonBuilderFactory, jsonReaderFactory, jsonWriterFactory);
            }
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Iterable<String> fetchTags(final HttpClient httpClient, final String uri, final JsonReaderFactory jsonReaderFactory) throws IOException, InterruptedException {
        return () -> new Iterator<>() {
            private URI next = URI.create(uri);
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
                            HttpRequest.newBuilder()
                                    .GET()
                                    .uri(next)
                                    .header("accept", "application/json")
                                    .build(),
                            ofString());

                    switch (response.statusCode()) {
                        case 200:
                            // all good
                            break;
                        case 403:
                            log.warning(() -> "Rate limit hit, skipping for this run: " + response);
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
                    delegate = array.stream().map(JsonValue::asJsonObject).map(i -> i.getString("name")).iterator();
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
        final var response = httpClient.send(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create(url))
                        .build(),
                ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Invalid response: " + response);
        }

        final JsonObject spec;
        try (final var reader = jsonReaderFactory.createReader(new StringReader(response.body()))) {
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
                final var versionless = root.resolve(filename);

                String jsonString = null;
                if (force || !Files.exists(versionned)) {
                    jsonString = doResolve(jsonBuilderFactory, jsonWriterFactory, definitions, obj);
                    Files.writeString(versionned, jsonString);
                    log.info(() -> "Wrote '" + versionned + "'");
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

    private String doResolve(final JsonBuilderFactory jsonBuilderFactory, final JsonWriterFactory jsonWriterFactory,
                             final Map<String, JsonObject> definitions, final JsonObject obj) {
        final var resolved = resolveRefs(jsonBuilderFactory, definitions, obj).build();
        final var out = new StringWriter();
        try (final var writer = jsonWriterFactory.createWriter(out)) {
            writer.write(resolved);
        }

        final var jsonString = out.toString();
        if (jsonString.contains("$ref")) {
            throw new IllegalStateException("$ref should have been replaced: " + jsonString);
        }
        return jsonString;
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
