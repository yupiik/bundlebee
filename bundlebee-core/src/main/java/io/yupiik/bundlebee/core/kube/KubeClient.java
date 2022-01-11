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
package io.yupiik.bundlebee.core.kube;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.http.JsonHttpResponse;
import io.yupiik.bundlebee.core.lang.ConfigHolder;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonBuilderFactory;
import javax.json.JsonException;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbException;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Log
@ApplicationScoped
public class KubeClient implements ConfigHolder {
    @Inject
    private Yaml2JsonConverter yaml2json;

    @Inject
    private ApiPreloader apiPreloader;

    @Inject
    private HttpKubeClient api;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    @BundleBee
    private JsonBuilderFactory jsonBuilderFactory;

    @Inject
    @Description("Enables to define resource mapping, syntax uses propeties one: `<lowercased resource kind>s = /apis/....`.")
    @ConfigProperty(name = "bundlebee.kube.resourceMapping", defaultValue = "")
    private String rawResourceMapping;

    @Inject
    @Description("List of _kind_ of descriptors updates can be skipped, it is often useful for `PersistentVolumeClaim`.")
    @ConfigProperty(name = "bundlebee.kube.skipUpdateForKinds", defaultValue = "PersistentVolumeClaim")
    private String skipUpdateForKinds;

    @Inject
    @Description("Should YAML/JSON be logged when it can't be parsed.")
    @ConfigProperty(name = "bundlebee.kube.logDescriptorOnParsingError", defaultValue = "true")
    private boolean logDescriptorOnParsingError;

    @Inject
    @Description("" +
            "By default a descriptor update is done using `PATCH` with strategic merge patch logic, if set to `true` it will use a plain `PUT`. " +
            "Note that `io.yupiik.bundlebee/putOnUpdate` annotations can be set to `true` to force that in the descriptor itself.")
    @ConfigProperty(name = "bundlebee.kube.putOnUpdate", defaultValue = "false")
    private boolean putOnUpdate;

    @Inject
    @Description("Default value for deletions of `propagationPolicy`. Values can be `Orphan`, `Foreground` and `Background`.")
    @ConfigProperty(name = "bundlebee.kube.defaultPropagationPolicy", defaultValue = "Foreground")
    private String defaultPropagationPolicy;

    @Inject
    @Description("When using custom metadata (bundlebee ones or timestamp to force a rollout), where to inject them. " +
            "Default uses labels since it enables to query them later on but you can switch it to annotations.")
    @ConfigProperty(name = "bundlebee.kube.customMetadataInjectionPoint", defaultValue = "labels")
    private String customMetadataInjectionPoint;

    private Map<String, String> resourceMapping;
    private List<String> kindsToSkipUpdateIfPossible;

    @PostConstruct
    private void init() {
        kindsToSkipUpdateIfPossible = Stream.of(skipUpdateForKinds.split(",")).filter(it -> !it.isBlank()).collect(toList());

        final var tmpResourceMapping = new Properties();
        if (rawResourceMapping != null && !rawResourceMapping.isBlank()) {
            try (final var reader = new StringReader(rawResourceMapping)) {
                tmpResourceMapping.load(reader);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            resourceMapping = tmpResourceMapping.stringPropertyNames().stream()
                    .collect(toMap(identity(), tmpResourceMapping::getProperty));
        } else {
            resourceMapping = Map.of();
        }
    }

    // for backward compatibility
    public KubeConfig getLoadedKubeConfig() {
        return api.getLoadedKubeConfig();
    }

    public CompletionStage<JsonObject> findSecret(final String namespace, final String name) {
        return api.execute(HttpRequest.newBuilder().GET(), "/api/v1/namespaces/" + namespace + "/secrets/" + name)
                .thenApply(r -> {
                    if (r.statusCode() != 200) {
                        throw new IllegalArgumentException("Can't read secret '" + namespace + "'/'" + name + "': " + r.body());
                    }
                    return jsonb.fromJson(r.body().trim(), JsonObject.class);
                });
    }

    public CompletionStage<JsonObject> findServiceAccount(final String namespace, final String name) {
        return api.execute(HttpRequest.newBuilder().GET(), "/api/v1/namespaces/" + namespace + "/serviceaccounts/" + name)
                .thenApply(r -> {
                    if (r.statusCode() != 200) {
                        throw new IllegalArgumentException("Can't read account '" + namespace + "'/'" + name + "': " + r.body());
                    }
                    return jsonb.fromJson(r.body().trim(), JsonObject.class);
                });
    }

    public CompletionStage<Boolean> exists(final String descriptorContent, final String ext) {
        final var result = new AtomicBoolean(true);
        return forDescriptor(null, descriptorContent, ext, desc -> {
            final var kindLowerCased = desc.getString("kind").toLowerCase(ROOT) + 's';
            return apiPreloader.ensureResourceSpec(desc, kindLowerCased)
                    .thenCompose(ignored -> doExists(result, desc, kindLowerCased));
        }).thenApply(ignored -> result.get());
    }

    public CompletionStage<List<HttpResponse<JsonObject>>> getResources(final String descriptorContent, final String ext) {
        return forDescriptor(null, descriptorContent, ext, desc -> {
            final var kindLowerCased = desc.getString("kind").toLowerCase(ROOT) + 's';
            return apiPreloader.ensureResourceSpec(desc, kindLowerCased)
                    .thenCompose(ignored -> doGet(desc, kindLowerCased));
        }).thenApply(responses -> responses.stream()
                .map(it -> new JsonHttpResponse(jsonb, it))
                .collect(toList()));
    }

    private CompletionStage<HttpResponse<String>> doGet(final JsonObject desc, final String kindLowerCased) {
        final var metadata = desc.getJsonObject("metadata");
        final var name = metadata.getString("name");
        final var namespace = metadata.containsKey("namespace") ? metadata.getString("namespace") : api.getNamespace();
        final var baseUri = toBaseUri(desc, kindLowerCased, namespace);
        return api.execute(HttpRequest.newBuilder().GET().header("Accept", "application/json"), baseUri + "/" + name);
    }

    private CompletionStage<?> doExists(final AtomicBoolean result, final JsonObject desc, final String kindLowerCased) {
        return doGet(desc, kindLowerCased).whenComplete((r, e) -> {
            if (r != null) {
                switch (r.statusCode()) {
                    case 404:
                        result.set(false);
                        break;
                    case 200:
                        result.set(true);
                        break;
                    default:
                }
            }
        });
    }

    public CompletionStage<?> apply(final String descriptorContent, final String ext,
                                    final Map<String, String> customLabels) {
        return forDescriptor("Applying", descriptorContent, ext, json -> doApply(json, customLabels));
    }

    public CompletionStage<?> delete(final String descriptorContent, final String ext, final int gracePeriod) {
        return forDescriptor("Deleting", descriptorContent, ext, json -> doDelete(json, gracePeriod));
    }

    public <T> CompletionStage<List<T>> forDescriptor(final String prefixLog, final String descriptorContent, final String ext,
                                                      final Function<JsonObject, CompletionStage<T>> descHandler) {
        if (api.isVerbose()) {
            log.info(() -> prefixLog + " descriptor\n" + descriptorContent);
        }
        final var json = toJson(descriptorContent, ext);
        if (api.isVerbose()) {
            log.info(() -> "Loaded descriptor(s)\n" + json);
        }
        switch (json.getValueType()) {
            case ARRAY:
                return all(
                        json.asJsonArray().stream()
                                .map(JsonValue::asJsonObject)
                                .map(descHandler)
                                .collect(toList()),
                        toList(),
                        true);
            case OBJECT:
                return descHandler
                        .apply(json.asJsonObject())
                        .thenApply(List::of);
            default:
                throw new IllegalArgumentException("Unsupported json type for apply: " + json);
        }
    }

    private JsonValue toJson(final String descriptorContent, final String ext) {
        try {
            return "json".equals(ext) ?
                    jsonb.fromJson(descriptorContent.trim(), JsonValue.class) :
                    yaml2json.convert(JsonValue.class, descriptorContent.trim());
        } catch (final JsonbException | JsonException je) {
            if (!logDescriptorOnParsingError) {
                throw je;
            }
            throw new IllegalStateException("Can't read '\n" + descriptorContent + "'\n->: " + je.getMessage(), je);
        }
    }

    private CompletionStage<?> doDelete(final JsonObject desc, final int gracePeriod) {
        final var kindLowerCased = desc.getString("kind").toLowerCase(ROOT) + 's';
        return apiPreloader.ensureResourceSpec(desc, kindLowerCased)
                .thenCompose(ignored -> doDelete(desc, gracePeriod, kindLowerCased));
    }

    private CompletionStage<?> doDelete(final JsonObject desc, final int gracePeriod, final String kindLowerCased) {
        final var metadata = desc.getJsonObject("metadata");
        final var name = metadata.getString("name");
        final var namespace = metadata.containsKey("namespace") ? metadata.getString("namespace") : api.getNamespace();
        log.info(() -> "Deleting '" + name + "' (kind=" + kindLowerCased + ") for namespace '" + namespace + "'");

        final var uri = toBaseUri(desc, kindLowerCased, namespace) + "/" + name + (gracePeriod >= 0 ? "?gracePeriodSeconds=" + gracePeriod : "");
        return api.execute(
                        HttpRequest.newBuilder()
                                .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBuilderFactory.createObjectBuilder()
                                        .add("kind", "DeleteOptions")
                                        .add("apiVersion", "v1")
                                        // todo: .add("gracePeriodSeconds", config)
                                        // .add("orphanDependents", true) // this one is deprecated, this is why we use propagationPolicy too
                                        .add("propagationPolicy", metadata.containsKey("bundlebee.delete.propagationPolicy") ?
                                                metadata.getString("bundlebee.delete.propagationPolicy") :
                                                defaultPropagationPolicy)
                                        .build()
                                        .toString(), StandardCharsets.UTF_8))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json"),
                        uri)
                .thenApply(it -> {
                    if (it.statusCode() == 422) {
                        log.warning("Invalid deletion on " + uri + ":\n" + it.body());
                    }
                    return it;
                });
    }

    private CompletionStage<?> doApply(final JsonObject rawDesc, final Map<String, String> customLabels) {
        // apply logic is a "create or replace" one
        // so first thing we have to do is to test if the resource exists, and if not create it
        // for that we will need to extract the resource "kind" and "name" (id):
        //
        // kind: ConfigMap
        // metadata:
        //   name: my-config
        //
        // 1. curl -H "Accept: application/json" 'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>/<name>'
        // if HTTP 200 -> replace (patch by default, put if configured)
        //   2. curl -XPATCH -H "Content-Type: application/strategic-merge-patch+json" -H "Accept: application/json"
        //          'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>/<name>?fieldManager=kubectl-client-side-apply'
        //          <descriptor in json - or send yaml>
        // else -> create
        //   2. curl -XPOST -H "Accept: application/json" -H "Content-Type: application/json"
        //          'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>?fieldManager=kubectl-client-side-apply'
        //          <descriptor>
        // end
        final var desc = customLabels.isEmpty() ? rawDesc : injectMetadata(rawDesc, customLabels);
        final var kindLowerCased = desc.getString("kind").toLowerCase(ROOT) + 's';
        return apiPreloader.ensureResourceSpec(desc, kindLowerCased)
                .thenCompose(ignored -> doApply(rawDesc, desc, kindLowerCased));
    }

    private CompletionStage<HttpResponse<String>> doApply(final JsonObject rawDesc, final JsonObject desc, final String kindLowerCased) {
        final var metadata = desc.getJsonObject("metadata");
        final var name = metadata.getString("name");
        final var namespace = metadata.containsKey("namespace") ? metadata.getString("namespace") : api.getNamespace();
        log.info(() -> "Applying '" + name + "' (kind=" + kindLowerCased + ") for namespace '" + namespace + "'");

        final var fieldManager = "?fieldManager=kubectl-client-side-apply" + (!api.isDryRun() ? "" : ("&dryRun=All"));
        final var baseUri = toBaseUri(desc, kindLowerCased, namespace);

        if (api.isVerbose()) {
            log.info(() -> "Will apply descriptor " + rawDesc + " on " + baseUri);
        }

        return api.execute(HttpRequest.newBuilder()
                                .GET()
                                .header("Accept", "application/json"),
                        baseUri + "/" + name)
                .thenCompose(findResponse -> {
                    if (api.isVerbose()) {
                        log.info(findResponse::toString);
                    }
                    if (findResponse.statusCode() > 404) {
                        throw new IllegalStateException("Invalid HTTP response: " + findResponse);
                    }

                    // we re-serialize the json there, we could use the raw descriptor
                    // but in case it is a list it is saner to do it this way
                    if (findResponse.statusCode() == 200) {
                        log.finest(() -> name + " (" + kindLowerCased + ") already exists, updating it");
                        JsonObject obj = null;
                        String kind = null;
                        try {
                            obj = jsonb.fromJson(findResponse.body(), JsonObject.class);
                            kind = obj.getString("kind");
                        } catch (final RuntimeException re) {
                            // no-op
                        }
                        if (obj == null || !kindsToSkipUpdateIfPossible.contains(kind) || needsUpdate(obj, desc)) {
                            return doUpdate(desc, name, fieldManager, baseUri)
                                    .thenApply(response -> {
                                        if (api.isVerbose()) {
                                            log.info(response::toString);
                                        }
                                        if (response.statusCode() != 200) {
                                            throw new IllegalStateException("" +
                                                    "Can't update " + name + " (" + kindLowerCased + "): " + response + "\n" +
                                                    response.body());
                                        }
                                        return response;
                                    });
                        }
                        return completedStage(findResponse);
                    } else {
                        log.finest(() -> name + " (" + kindLowerCased + ") does ont exist, creating it");
                        return api.execute(HttpRequest.newBuilder()
                                                .POST(HttpRequest.BodyPublishers.ofString(desc.toString()))
                                                .header("Content-Type", "application/json")
                                                .header("Accept", "application/json"),
                                        baseUri + fieldManager)
                                .thenApply(response -> {
                                    if (api.isVerbose()) {
                                        log.info(response::toString);
                                    }
                                    if (response.statusCode() != 201) {
                                        throw new IllegalStateException(
                                                "Can't create " + name + " (" + kindLowerCased + "): " + response + "\n" +
                                                        response.body());
                                    } else {
                                        log.info(() -> "Created " + name + " (" + kindLowerCased + ") successfully");
                                    }
                                    return response;
                                });
                    }
                });
    }

    private CompletableFuture<HttpResponse<String>> doUpdate(final JsonObject desc,
                                                             final String name,
                                                             final String fieldManager,
                                                             final String baseUri) {
        if (putOnUpdate || isUsePutOnUpdateForced(desc)) {
            return api.execute(
                            HttpRequest.newBuilder()
                                    .method("PUT", HttpRequest.BodyPublishers.ofString(desc.toString()))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json"),
                            baseUri + "/" + name + fieldManager)
                    .toCompletableFuture();
        }
        return api.execute(
                        HttpRequest.newBuilder()
                                .method("PATCH", HttpRequest.BodyPublishers.ofString(desc.toString()))
                                .header("Content-Type", "application/strategic-merge-patch+json")
                                .header("Accept", "application/json"),
                        baseUri + "/" + name + fieldManager)
                .toCompletableFuture();
    }

    // if all user entries are the same in existing one we consider we don't need an update
    private boolean needsUpdate(final JsonValue existing, final JsonValue user) {
        if (existing == null && user == null) {
            return false;
        }
        if (existing == null || user == null || existing.getValueType() != user.getValueType()) {
            return true;
        }
        switch (existing.getValueType()) {
            case STRING:
                return !Objects.equals(JsonString.class.cast(existing).getString(), JsonString.class.cast(user).getString());
            case NUMBER:
                return !Objects.equals(JsonNumber.class.cast(existing).doubleValue(), JsonNumber.class.cast(user).doubleValue());
            case OBJECT:
                final var obj = existing.asJsonObject();
                return user.asJsonObject().entrySet().stream()
                        .filter(it -> !"bundlebee.timestamp".equals(it.getKey())) // this one moves but is not a real change
                        .anyMatch(e -> needsUpdate(obj.get(e.getKey()), e.getValue()));
            case TRUE:
            case FALSE:
            case NULL:
            case ARRAY:
            default:
                return !Objects.equals(existing, user);
        }
    }

    private boolean isUsePutOnUpdateForced(final JsonObject desc) {
        try {
            return ofNullable(desc.getJsonObject("metadata"))
                    .map(it -> it.getJsonObject("annotations"))
                    .map(it -> it.getString("io.yupiik.bundlebee/putOnUpdate"))
                    .map(Boolean::parseBoolean)
                    .orElse(false);
        } catch (final RuntimeException re) {
            return false;
        }
    }

    private String toBaseUri(final JsonObject desc, final String kindLowerCased, final String namespace) {
        return ofNullable(resourceMapping.get(kindLowerCased))
                .map(mapped -> !mapped.startsWith("http") ? api.getBaseApi() + mapped : mapped)
                .or(() -> ofNullable(apiPreloader.getBaseUrls().get(kindLowerCased))
                        .map(url -> api.getBaseApi() + url.replace("${namespace}", namespace)))
                .orElseGet(() -> api.getBaseApi() + findApiPrefix(kindLowerCased, desc) +
                        (!isSkipNameSpace(kindLowerCased) ? "/namespaces/" + namespace : "") +
                        "/" + kindLowerCased);
    }

    private boolean isSkipNameSpace(final String kindLowerCased) {
        switch (kindLowerCased) {
            case "nodes":
            case "persistentvolumes":
            case "clusterroles":
            case "clusterrolebindings":
                return true;
            default:
                return false;
        }
    }

    private String findApiPrefix(final String kindLowerCased, final JsonObject desc) {
        switch (kindLowerCased) {
            case "deployments":
            case "statefulsets":
            case "daemonsets":
            case "replicasets":
            case "controllerrevisions":
                return "/apis/apps/v1";
            case "cronjobs":
                return "/apis/batch/v1beta1";
            case "apiservices":
                return "/apis/apiregistration.k8s.io/v1";
            case "customresourcedefinitions":
                return "/apis/apiextensions.k8s.io/v1beta1";
            case "mutatingwebhookconfigurations":
            case "validatingwebhookconfigurations":
                return "/apis/admissionregistration.k8s.io/v1";
            case "roles":
            case "rolebindings":
            case "clusterroles":
            case "clusterrolebindings":
                return "/apis/" + desc.getString("apiVersion");
            default:
                return "/api/v1";
        }
    }

    public JsonObject injectMetadata(final JsonObject rawDesc, final Map<String, String> customLabels) {
        return rawDesc.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> {
                    if ("metadata".equals(entry.getKey())) {
                        final JsonObject labelsJson = customLabels.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .collect(Collector.of(
                                        jsonBuilderFactory::createObjectBuilder,
                                        (builder, kv) -> builder.add(kv.getKey(), kv.getValue()),
                                        JsonObjectBuilder::addAll,
                                        JsonObjectBuilder::build));

                        final var metadata = entry.getValue().asJsonObject();
                        if (metadata.containsKey(customMetadataInjectionPoint)) {
                            return mergeLabels(entry, metadata, labelsJson);
                        }
                        return Stream.of(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), Stream.concat(
                                        metadata.entrySet().stream().sorted(Map.Entry.comparingByKey()),
                                        Stream.of(new AbstractMap.SimpleImmutableEntry<>(customMetadataInjectionPoint, labelsJson)))
                                .collect(Collector.of(
                                        jsonBuilderFactory::createObjectBuilder,
                                        (builder, kv) -> builder.add(kv.getKey(), kv.getValue()),
                                        JsonObjectBuilder::addAll,
                                        JsonObjectBuilder::build))));
                    }
                    return Stream.of(entry);
                })
                .collect(Collector.of(
                        jsonBuilderFactory::createObjectBuilder,
                        (builder, kv) -> builder.add(kv.getKey(), kv.getValue()),
                        JsonObjectBuilder::addAll,
                        JsonObjectBuilder::build));
    }

    private Stream<Map.Entry<String, JsonValue>> mergeLabels(final Map.Entry<String, JsonValue> entry,
                                                             final JsonObject metadata,
                                                             final JsonObject customLabels) {
        return Stream.of(new AbstractMap.SimpleImmutableEntry<>(
                entry.getKey(),
                metadata.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .flatMap(metadataEntry -> {
                            if (customMetadataInjectionPoint.equals(metadataEntry.getKey())) {
                                return Stream.of(new AbstractMap.SimpleImmutableEntry<>(
                                        metadataEntry.getKey(),
                                        Stream.concat(
                                                        metadataEntry.getValue().asJsonObject().entrySet().stream()
                                                                .sorted(Map.Entry.comparingByKey())
                                                                .filter(e -> !customLabels.containsKey(e.getKey())),
                                                        customLabels.entrySet().stream())
                                                .collect(Collector.of(
                                                        jsonBuilderFactory::createObjectBuilder,
                                                        (builder, kv) -> builder.add(kv.getKey(), kv.getValue()),
                                                        JsonObjectBuilder::addAll,
                                                        JsonObjectBuilder::build))));
                            }
                            return Stream.of(metadataEntry);
                        })
                        .collect(Collector.of(
                                jsonBuilderFactory::createObjectBuilder,
                                (builder, kv) -> builder.add(kv.getKey(), kv.getValue()),
                                JsonObjectBuilder::addAll,
                                JsonObjectBuilder::build))));
    }
}
