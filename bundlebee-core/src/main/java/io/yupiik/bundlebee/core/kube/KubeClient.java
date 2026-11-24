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
package io.yupiik.bundlebee.core.kube;

import static io.yupiik.bundlebee.lang.CompletionFutures.all;
import static java.util.Locale.ROOT;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.http.JsonHttpResponse;
import io.yupiik.bundlebee.core.kube.model.KubernetesStatus;
import io.yupiik.bundlebee.core.service.ContainerSanitizer;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Init;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.patch.GenericJsonPatch;
import io.yupiik.fusion.json.patch.JsonPatchOperation;
import io.yupiik.fusion.json.pretty.PrettyJsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class KubeClient {
    private static final Pattern COMMA = Pattern.compile(",");
    private static final Pattern PIPE = Pattern.compile("\\|");
    private final Logger log = Logger.getLogger(KubeClient.class.getName());

    public KubeClient(
            final Yaml2JsonConverter yaml2json,
            final ContainerSanitizer containerSanitizer,
            final ApiPreloader apiPreloader,
            final RuntimeContainer container,
            final JsonMapper jsonb,
            final ScheduledExecutorService scheduledExecutorService,
            final DeploymentConfiguration configuration,
            final KubernetesConfiguration settings,
            final NamespaceProvider namespaceProvider,
            final KubernetesClientProducer producer) {
        this.yaml2json = yaml2json;
        this.containerSanitizer = containerSanitizer;
        this.apiPreloader = apiPreloader;
        this.container = container;
        this.jsonb = jsonb;
        this.scheduledExecutorService = scheduledExecutorService;
        this.configuration = configuration;
        this.settings = settings;
        this.namespaceProvider = namespaceProvider;
        this.producer = producer;
    }

    private final Yaml2JsonConverter yaml2json;
    private final ContainerSanitizer containerSanitizer;
    private final ApiPreloader apiPreloader;
    private final RuntimeContainer container;
    private final JsonMapper jsonb;
    private final KubernetesConfiguration settings;
    private final NamespaceProvider namespaceProvider;
    private final KubernetesClientProducer producer;
    private String fieldValidation;
    private String rawResourceMapping;
    private String skipUpdateForKinds;
    private boolean logDescriptorOnParsingError;
    private Set<String> statefulsetSpecAllowedAttributes;
    private String implicitlyDroppedAttributes;
    private boolean putOnUpdate;
    private boolean force;
    private String defaultPropagationPolicy;
    private String customMetadataInjectionPoint;
    private String patchContentType;
    private final ScheduledExecutorService scheduledExecutorService;
    private Map<String, String> resourceMapping;
    private List<String> kindsToSkipUpdateIfPossible;
    private List<GenericJsonPatch> implicitlyDrops;

    @Init
    protected void init() {
        this.fieldValidation = configuration.fieldValidation();
        this.rawResourceMapping = configuration.rawResourceMapping();
        this.skipUpdateForKinds = configuration.skipUpdateForKinds();
        this.logDescriptorOnParsingError = configuration.logDescriptorOnParsingError();
        this.statefulsetSpecAllowedAttributes = Set.copyOf(configuration.statefulsetSpecAllowedAttributes());
        this.implicitlyDroppedAttributes = configuration.implicitlyDroppedAttributes();
        this.putOnUpdate = configuration.putOnUpdate();
        this.force = configuration.force();
        this.defaultPropagationPolicy = configuration.defaultPropagationPolicy();
        this.customMetadataInjectionPoint = configuration.customMetadataInjectionPoint();
        this.patchContentType = configuration.patchContentType();
        kindsToSkipUpdateIfPossible = Stream.of(COMMA.split(skipUpdateForKinds))
                .filter(it -> !it.isBlank())
                .collect(toList());
        final var tmpResourceMapping = new Properties();
        if (rawResourceMapping != null && !rawResourceMapping.isBlank()) {
            try (var reader = new StringReader(rawResourceMapping)) {
                tmpResourceMapping.load(reader);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            resourceMapping = tmpResourceMapping.stringPropertyNames().stream()
                    .collect(toMap(identity(), tmpResourceMapping::getProperty));
        } else {
            resourceMapping = Map.of();
        }
        this.implicitlyDrops = "none".equals(implicitlyDroppedAttributes)
                ? List.of()
                : Stream.of(PIPE.split(implicitlyDroppedAttributes))
                        .map(it -> new GenericJsonPatch(
                                it.startsWith("[")
                                        ? jsonb.fromString(
                                                new Types.ParameterizedTypeImpl(List.class, JsonPatchOperation.class),
                                                it)
                                        : List.of(new JsonPatchOperation(
                                                JsonPatchOperation.Operation.remove, it, null, null))))
                        .collect(toList());
    }

    // for backward compatibility
    public KubeConfig getLoadedKubeConfig() {
        try (final var client = container.lookup(KubernetesClient.class)) {
            client.instance().base();
            return producer.loadedConfiguration();
        }
    }

    private CompletionStage<HttpResponse<String>> execute(final HttpRequest.Builder builder, final String path) {
        // Do not use Fusion's kubernetes.api URI alias here: it resolves against the
        // origin and decodes the path. BundleBee preserves base paths and raw escapes.
        try (final var client = container.lookup(KubernetesClient.class)) {
            final var api = client.instance();
            // The application scope owns the client until container shutdown, not this lookup handle.
            return api.sendAsync(
                    builder.uri(URI.create(
                                    path.startsWith("http:") || path.startsWith("https:")
                                            ? path
                                            : api.base().toString() + path))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    public CompletionStage<Map<String, Object>> findSecret(final String namespace, final String name) {
        return execute(HttpRequest.newBuilder().GET(), "/api/v1/namespaces/" + namespace + "/secrets/" + name)
                .thenApply(r -> {
                    if (r.statusCode() != 200) {
                        throw new IllegalArgumentException(
                                "Can\'t read secret \'" + namespace + "\'/\'" + name + "\': " + tryFormat(r.body()));
                    }
                    return jsonb.fromString(
                            new Types.ParameterizedTypeImpl(Map.class, String.class, Object.class),
                            r.body().trim());
                });
    }

    public CompletionStage<Map<String, Object>> findServiceAccount(final String namespace, final String name) {
        return execute(HttpRequest.newBuilder().GET(), "/api/v1/namespaces/" + namespace + "/serviceaccounts/" + name)
                .thenApply(r -> {
                    if (r.statusCode() != 200) {
                        throw new IllegalArgumentException(
                                "Can\'t read account \'" + namespace + "\'/\'" + name + "\': " + tryFormat(r.body()));
                    }
                    return jsonb.fromString(
                            new Types.ParameterizedTypeImpl(Map.class, String.class, Object.class),
                            r.body().trim());
                });
    }

    public CompletionStage<Boolean> exists(final String descriptorContent, final String ext) {
        final var result = new AtomicBoolean(true);
        return forDescriptor(null, descriptorContent, ext, desc -> doExists(result, desc))
                .thenApply(ignored -> result.get());
    }

    private CompletionStage<?> doExists(final AtomicBoolean result, final Map<String, Object> desc) {
        final var kindLowerCased = ((String) desc.get("kind")).toLowerCase(ROOT) + 's';
        return apiPreloader
                .ensureResourceSpec(desc, kindLowerCased)
                .thenCompose(ignored -> doExists(result, desc, kindLowerCased));
    }

    public CompletionStage<List<HttpResponse<Map<String, Object>>>> getResources(
            final String descriptorContent, final String ext) {
        return forDescriptor(null, descriptorContent, ext, this::getResource)
                .thenApply(responses -> responses.stream()
                        .map(it -> new JsonHttpResponse(jsonb, it))
                        .collect(toList()));
    }

    public CompletionStage<HttpResponse<String>> getResource(final Map<String, Object> desc) {
        final var kindLowerCased = ((String) desc.get("kind")).toLowerCase(ROOT) + 's';
        return apiPreloader
                .ensureResourceSpec(desc, kindLowerCased)
                .thenCompose(ignored -> doGet(desc, kindLowerCased));
    }

    private CompletionStage<HttpResponse<String>> doGet(final Map<String, Object> desc, final String kindLowerCased) {
        final var metadata = (Map<?, ?>) desc.get("metadata");
        final var name = (String) metadata.get("name");
        final var namespace =
                metadata.containsKey("namespace") ? (String) metadata.get("namespace") : namespaceProvider.namespace();
        final var baseUri = toBaseUri(desc, kindLowerCased, namespace);
        return execute(HttpRequest.newBuilder().GET().header("Accept", "application/json"), baseUri + "/" + name);
    }

    private CompletionStage<?> doExists(
            final AtomicBoolean result, final Map<String, Object> desc, final String kindLowerCased) {
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

    public CompletionStage<?> apply(
            final String descriptorContent,
            final String ext,
            final Map<String, String> customLabels,
            final boolean skipGet) {
        return forDescriptorWithOriginal(
                "Applying",
                descriptorContent,
                ext,
                item -> doApply(item.getRaw(), item.getPrepared(), customLabels, skipGet));
    }

    public CompletionStage<?> apply(
            final String descriptorContent, final String ext, final Map<String, String> customLabels) {
        return apply(descriptorContent, ext, customLabels, false);
    }

    public CompletionStage<?> delete(final String descriptorContent, final String ext, final int gracePeriod) {
        return forDescriptor("Deleting", descriptorContent, ext, json -> doDelete(json, gracePeriod));
    }

    public <T> CompletionStage<List<T>> forDescriptor(
            final String prefixLog,
            final String descriptorContent,
            final String ext,
            final Function<Map<String, Object>, CompletionStage<T>> descHandler) {
        return forDescriptorWithOriginal(
                prefixLog, descriptorContent, ext, item -> descHandler.apply(item.getPrepared()));
    }

    @SuppressWarnings("unchecked")
    public <T> CompletionStage<List<T>> forDescriptorWithOriginal(
            final String prefixLog,
            final String descriptorContent,
            final String ext,
            final Function<DescriptorItem, CompletionStage<T>> descHandler) {
        final var verbose = settings.verbose() || settings.dryRun();
        if (verbose) {
            log.info(() -> prefixLog + " descriptor\n" + descriptorContent);
        }
        final var json = toJson(descriptorContent, ext);
        if (verbose) {
            log.info(() -> "Loaded descriptor(s)\n" + json);
        }
        if (json instanceof List<?> list) {
            return all(
                    list.stream()
                            .map(it -> {
                                if (!(it instanceof Map<?, ?> descriptor)) {
                                    throw new IllegalArgumentException("Unsupported json type for apply: " + it);
                                }
                                final var item = (Map<String, Object>) descriptor;
                                return new DescriptorItem(item, sanitizeJson(item));
                            })
                            .map(descHandler)
                            .collect(toList()),
                    toList(),
                    true);
        }
        if (json instanceof Map<?, ?> map) {
            final var jsonObject = (Map<String, Object>) map;
            return descHandler
                    .apply(new DescriptorItem(jsonObject, sanitizeJson(jsonObject)))
                    .thenApply(List::of);
        }
        throw new IllegalArgumentException("Unsupported json type for apply: " + json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeJson(final Map<String, Object> json) {
        if (implicitlyDrops.isEmpty()) {
            return json;
        }
        var out = json;
        for (final var patch : implicitlyDrops) {
            try {
                final var patched = patch.apply(out);
                if (!(patched instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("Descriptor patch must produce an object");
                }
                out = (Map<String, Object>) map;
            } catch (final IllegalArgumentException je) {
            }
            // no-op
        }
        return out;
    }

    private Object toJson(final String descriptorContent, final String ext) {
        try {
            return "json".equals(ext)
                    ? jsonb.fromString(Object.class, descriptorContent.trim())
                    : yaml2json.convert(Object.class, descriptorContent.trim());
        } catch (final IllegalArgumentException je) {
            if (!logDescriptorOnParsingError) {
                throw je;
            }
            throw new IllegalStateException("Can\'t read \'\n" + descriptorContent + "\'\n->: " + je.getMessage(), je);
        }
    }

    private CompletionStage<?> doDelete(final Map<String, Object> desc, final int gracePeriod) {
        final var kindLowerCased = ((String) desc.get("kind")).toLowerCase(ROOT) + 's';
        return apiPreloader
                .ensureResourceSpec(desc, kindLowerCased)
                .thenCompose(ignored -> doDelete(desc, gracePeriod, kindLowerCased));
    }

    private CompletionStage<?> doDelete(
            final Map<String, Object> desc, final int gracePeriod, final String kindLowerCased) {
        final var metadata = (Map<?, ?>) desc.get("metadata");
        final var name = (String) metadata.get("name");
        final var namespace =
                metadata.containsKey("namespace") ? (String) metadata.get("namespace") : namespaceProvider.namespace();
        log.info(() -> "Deleting \'" + name + "\' (kind=" + kindLowerCased + ")"
                + (!"namespaces".equals(kindLowerCased) ? " for namespace \'" + namespace + "\'" : ""));
        final var uri = toBaseUri(desc, kindLowerCased, namespace) + "/" + name
                + (gracePeriod >= 0 ? "?gracePeriodSeconds=" + gracePeriod : "");
        return delete(
                uri,
                metadata.containsKey("bundlebee.delete.propagationPolicy")
                        ? (String) metadata.get("bundlebee.delete.propagationPolicy")
                        : defaultPropagationPolicy);
    }

    public CompletionStage<HttpResponse<String>> delete(final String uri, final String propagationPolicy) {
        return execute(
                        HttpRequest.newBuilder()
                                .method(
                                        "DELETE",
                                        HttpRequest.BodyPublishers.ofString(
                                                jsonb.toString(Map.of(
                                                        "kind",
                                                        "DeleteOptions",
                                                        "apiVersion",
                                                        "v1",
                                                        "propagationPolicy",
                                                        propagationPolicy == null
                                                                ? defaultPropagationPolicy
                                                                : propagationPolicy)),
                                                StandardCharsets.UTF_8))
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

    public CompletionStage<?> doApply(
            final Map<String, Object> originalDontUseDesc,
            final Map<String, Object> rawDesc,
            final Map<String, String> customLabels,
            final boolean skipGet) {
        // apply logic is a "create or replace" one
        // so first thing we have to do is to test if the resource exists, and if not create it
        // for that we will need to extract the resource "kind" and "name" (id):
        //
        // kind: ConfigMap
        // metadata:
        //   name: my-config
        //
        // 1. curl -H "Accept: application/json"
        // 'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>/<name>'
        // if HTTP 200 -> replace (patch by default, put if configured)
        //   2. curl -XPATCH -H "Content-Type: application/strategic-merge-patch+json" -H "Accept: application/json"
        //
        // 'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>/<name>?fieldManager=kubectl-client-side-apply'
        //          <descriptor in json - or send yaml>
        // else -> create
        //   2. curl -XPOST -H "Accept: application/json" -H "Content-Type: application/json"
        //
        // 'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>?fieldManager=kubectl-client-side-apply'
        //          <descriptor>
        // end
        final var desc = customLabels.isEmpty() ? rawDesc : injectMetadata(rawDesc, customLabels);
        final var kindLowerCased = ((String) desc.get("kind")).toLowerCase(ROOT) + 's';
        return apiPreloader
                .ensureResourceSpec(desc, kindLowerCased)
                .thenCompose(ignored -> doApply(originalDontUseDesc, desc, kindLowerCased, 1, skipGet));
    }

    protected CompletionStage<HttpResponse<String>> doApply(
            final Map<String, Object> rawDesc,
            final Map<String, Object> preparedDesc,
            final String kindLowerCased,
            final int retry,
            final boolean skipGet) {
        final var metadata = (Map<?, ?>) preparedDesc.get("metadata");
        final var name = (String) metadata.get("name");
        final var namespace =
                metadata.containsKey("namespace") ? (String) metadata.get("namespace") : namespaceProvider.namespace();
        log.info(() -> "Applying \'" + name + "\' (kind=" + kindLowerCased + ")"
                + (!"namespaces".equals(kindLowerCased) ? " for namespace \'" + namespace + "\'" : ""));
        final var fieldManager = "?fieldManager=kubectl-client-side-apply" + (!settings.dryRun() ? "" : ("&dryRun=All"))
                + ("skip".equals(fieldValidation) ? "" : ("&fieldValidation=" + fieldValidation));
        final var baseUri = toBaseUri(preparedDesc, kindLowerCased, namespace);
        if (settings.verbose()) {
            log.info(() -> "Will apply descriptor " + preparedDesc + " on " + baseUri);
        }
        if (skipGet) {
            return doApplyOnMissingDescriptor(
                    rawDesc, preparedDesc, kindLowerCased, retry, name, baseUri, fieldManager, true);
        }
        return execute(HttpRequest.newBuilder().GET().header("Accept", "application/json"), baseUri + "/" + name)
                .thenCompose(findResponse -> {
                    if (settings.verbose()) {
                        log.info(findResponse::toString);
                    }
                    if (findResponse.statusCode() > 404) {
                        throw new IllegalStateException("Invalid HTTP response: " + findResponse);
                    }
                    // we re-serialize the json there, we could use the raw descriptor
                    // but in case it is a list it is saner to do it this way
                    if (findResponse.statusCode() == 200) {
                        log.finest(() -> name + " (" + kindLowerCased + ") already exists, updating it");
                        Map<String, Object> obj = null;
                        String kind = null;
                        try {
                            obj = jsonb.fromString(
                                    new Types.ParameterizedTypeImpl(Map.class, String.class, Object.class),
                                    findResponse.body());
                            kind = (String) obj.get("kind");
                        } catch (final RuntimeException re) {
                        }
                        // no-op
                        final var desc = filterForApply(
                                "@" + kindLowerCased + "/" + namespace + '/' + name, preparedDesc, kindLowerCased);
                        if (obj == null || !kindsToSkipUpdateIfPossible.contains(kind) || needsUpdate(obj, desc)) {
                            return doUpdate(rawDesc, desc, name, fieldManager, baseUri)
                                    .thenCompose(response -> {
                                        if (settings.verbose()) {
                                            log.info(response::toString);
                                        }
                                        final var errorMessage = "Can\'t update " + name + " (" + kindLowerCased + "): "
                                                + response + "\n" + tryFormat(response.body());
                                        if (response.statusCode() == 422) {
                                            // try to get then update to forward the existing id
                                            return injectResourceVersionInDescriptor(desc, name, baseUri, errorMessage)
                                                    .thenCompose(descWithResourceVersion -> doUpdate(
                                                                    rawDesc,
                                                                    descWithResourceVersion,
                                                                    name,
                                                                    fieldManager,
                                                                    baseUri)
                                                            .thenApply(recoverResponse -> {
                                                                if (settings.verbose()) {
                                                                    log.info(recoverResponse::toString);
                                                                }
                                                                if (recoverResponse.statusCode() != 200) {
                                                                    throw new IllegalStateException(errorMessage);
                                                                }
                                                                return recoverResponse;
                                                            }));
                                        }
                                        if (response.statusCode() != 200 && response.statusCode() != 201) /* force */ {
                                            throw new IllegalStateException(errorMessage);
                                        }
                                        return completedStage(response);
                                    });
                        }
                        return completedStage(findResponse);
                    }
                    return doApplyOnMissingDescriptor(
                            rawDesc,
                            preparedDesc,
                            kindLowerCased,
                            retry,
                            name,
                            baseUri,
                            fieldManager,
                            findResponse.statusCode() == 404);
                });
    }

    private CompletionStage<HttpResponse<String>> doApplyOnMissingDescriptor(
            final Map<String, Object> rawDesc,
            final Map<String, Object> preparedDesc,
            final String kindLowerCased,
            final int retry,
            final String name,
            final String baseUri,
            final String fieldManager,
            final boolean sanitize) {
        final Map<String, Object> preparedAndFilteredDescriptor;
        if (sanitize && containerSanitizer.canSanitizeCpuResource(kindLowerCased)) {
            preparedAndFilteredDescriptor = containerSanitizer.dropCpuResources(kindLowerCased, preparedDesc);
        } else {
            preparedAndFilteredDescriptor = preparedDesc;
        }
        log.finest(() -> name + " (" + kindLowerCased + ") does not exist, creating it");
        return execute(
                        HttpRequest.newBuilder()
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        jsonb.toString(preparedAndFilteredDescriptor)))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json"),
                        baseUri + fieldManager)
                .thenCompose(response -> {
                    if (settings.verbose()) {
                        log.info(response::toString);
                    }
                    // can we retry, happens for service accounts for ex when implicitly created
                    if (response.statusCode() == 409 && retry > 0) {
                        try {
                            final var payload = jsonb.fromString(KubernetesStatus.class, response.body());
                            if ("AlreadyExists".equals(payload.reason())
                                    && payload.details() != null
                                    && "serviceaccounts"
                                            .equals(payload.details().kind())) {
                                return doApply(rawDesc, preparedDesc, kindLowerCased, retry - 1, false);
                            }
                        } catch (final RuntimeException re) {
                            // let it fail
                            log.log(Level.FINEST, re, re::getMessage);
                        }
                    }
                    if (response.statusCode() != 201) {
                        throw new IllegalStateException("Can\'t create " + name + " (" + kindLowerCased + "): "
                                + response + "\n" + tryFormat(response.body()));
                    }
                    log.info(() -> "Created " + name + " (" + kindLowerCased + ") successfully");
                    return completedStage(response);
                });
    }

    private String tryFormat(final String body) {
        try {
            return new PrettyJsonMapper(jsonb).toString(jsonb.fromString(Object.class, body));
        } catch (final RuntimeException re) {
        }
        // no-op
        return body;
    }

    private Map<String, Object> filterForApply(final String ref, final Map<String, Object> desc, final String kind) {
        switch (kind) {
            case "statefulsets":
                return filterSpec(ref, desc, statefulsetSpecAllowedAttributes);
            default:
                // for now other descriptors are passthrough
                return desc;
        }
    }

    private Map<String, Object> filterSpec(
            final String ref, final Map<String, Object> desc, final Collection<String> allowed) {
        if (!(desc.get("spec") instanceof Map<?, ?> spec) || allowed.containsAll(spec.keySet())) {
            return desc;
        }
        // drop spec forbidden fields for updates
        log.info(() -> "Important: filtering descriptor spec attributes (" + ref + ") for update ("
                + spec.keySet().stream()
                        .map(String.class::cast)
                        .filter(it -> !allowed.contains(it))
                        .collect(joining(", "))
                + " update is not supported)");
        final var filtered = new LinkedHashMap<>(desc);
        final var filteredSpec = new LinkedHashMap<String, Object>();
        spec.forEach((key, value) -> {
            if (key instanceof String name && allowed.contains(name)) {
                filteredSpec.put(name, value);
            }
        });
        filtered.put("spec", filteredSpec);
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private CompletionStage<Map<String, Object>> injectResourceVersionInDescriptor(
            final Map<String, Object> desc, final String name, final String baseUri, final String errorMessage) {
        return execute(HttpRequest.newBuilder().GET().header("Accept", "application/json"), baseUri + "/" + name)
                .thenApply(test -> {
                    if (test.statusCode() != 200 || !desc.containsKey("metadata")) {
                        // behave as previous execution since we didn't recover there
                        throw new IllegalStateException(errorMessage);
                    }
                    // inject in desc the "last-applied-configuration" and submit again
                    final Map<String, Object> jsonObject = jsonb.fromString(
                            new Types.ParameterizedTypeImpl(Map.class, String.class, Object.class), test.body());
                    final var metadataValue = jsonObject.get("metadata");
                    if (!(metadataValue instanceof Map<?, ?> metadata)) {
                        throw new IllegalStateException(errorMessage);
                    }
                    final var resourceVersion = metadata.get("resourceVersion");
                    if (!(resourceVersion instanceof String)) {
                        throw new IllegalStateException(errorMessage);
                    }
                    final var updated = new LinkedHashMap<>(desc);
                    final var updatedMetadata = new LinkedHashMap<>((Map<String, Object>) desc.get("metadata"));
                    updatedMetadata.put("resourceVersion", resourceVersion);
                    updated.put("metadata", updatedMetadata);
                    return updated;
                });
    }

    private CompletableFuture<HttpResponse<String>> doUpdate(
            final Map<String, Object> raw,
            final Map<String, Object> desc,
            final String name,
            final String fieldManager,
            final String baseUri) {
        final var urlOrPath = baseUri + "/" + name + fieldManager;
        if (putOnUpdate || isUsePutOnUpdateForced(desc)) {
            return execute(
                            HttpRequest.newBuilder()
                                    .PUT(HttpRequest.BodyPublishers.ofString(jsonb.toString(desc)))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json"),
                            urlOrPath)
                    .toCompletableFuture();
        }
        if (force || isForce(desc)) {
            return doDelete(desc, -1)
                    .thenCompose(it -> {
                        if (settings.dryRun()) {
                            return completedStage(null);
                        }
                        return awaitDeletion(desc);
                    })
                    .thenCompose(d -> execute(
                            HttpRequest.newBuilder()
                                    .PUT(HttpRequest.BodyPublishers.ofString(jsonb.toString(desc)))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json"),
                            urlOrPath))
                    .toCompletableFuture();
        }
        final var type = customPatchContentType(raw).orElse(patchContentType);
        return
        // try application/merge-patch+json (CRD typically)
        execute(
                        HttpRequest.newBuilder()
                                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonb.toString(desc)))
                                .header("Content-Type", type)
                                .header("Accept", "application/json"),
                        urlOrPath)
                .thenCompose(res -> {
                    if (res.statusCode() == 415 && !"application/merge-patch+json".equals(type)) {
                        return execute(
                                HttpRequest.newBuilder()
                                        .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonb.toString(desc)))
                                        .header("Content-Type", "application/merge-patch+json")
                                        .header("Accept", "application/json"),
                                urlOrPath);
                    }
                    return completedStage(res);
                })
                .toCompletableFuture();
    }

    private CompletionStage<?> awaitDeletion(final Map<String, Object> desc) {
        final var promise = new CompletableFuture<Void>();
        final var result = new AtomicBoolean();
        final var kindLowerCased = ((String) desc.get("kind")).toLowerCase(ROOT) + 's';
        final var delay = 500;
        final var remainingRetries = new AtomicInteger(30000 / delay);
        scheduledExecutorService.scheduleWithFixedDelay(
                () -> doExists(result, desc, kindLowerCased)
                        .thenApply(updated -> {
                            if (!result.get()) {
                                promise.complete(null);
                            }
                            return null;
                        })
                        .whenComplete((ok, ko) -> {
                            if (promise.isDone() || promise.isCompletedExceptionally()) {
                                return;
                            }
                            if (remainingRetries.decrementAndGet() == 0) {
                                promise.completeExceptionally(new IllegalArgumentException(
                                        "Resource was not deleted in 30s: " + desc.get("metadata")));
                            }
                        }),
                0,
                delay,
                MILLISECONDS);
        return promise;
    }

    // if all user entries are the same in existing one we consider we don't need an update
    private boolean needsUpdate(final Object existing, final Object user) {
        if (existing == null && user == null) {
            return false;
        }
        if (existing instanceof Map<?, ?> old && user instanceof Map<?, ?> requested) {
            // This timestamp moves but is not a real change. Only compare user-supplied fields.
            return requested.entrySet().stream()
                    .filter(it -> !"bundlebee.timestamp".equals(it.getKey()))
                    .anyMatch(it -> needsUpdate(old.get(it.getKey()), it.getValue()));
        }
        if (existing instanceof Number old && user instanceof Number requested) {
            return Double.compare(old.doubleValue(), requested.doubleValue()) != 0;
        }
        return !Objects.equals(existing, user);
    }

    private boolean isUsePutOnUpdateForced(final Map<String, Object> desc) {
        return desc.get("metadata") instanceof Map<?, ?> metadata
                && metadata.get("annotations") instanceof Map<?, ?> annotations
                && annotations.get("io.yupiik.bundlebee/putOnUpdate") instanceof String value
                && Boolean.parseBoolean(value);
    }

    private boolean isForce(final Map<String, Object> desc) {
        return desc.get("metadata") instanceof Map<?, ?> metadata
                && metadata.get("annotations") instanceof Map<?, ?> annotations
                && annotations.get("io.yupiik.bundlebee/force") instanceof String value
                && Boolean.parseBoolean(value);
    }

    private Optional<String> customPatchContentType(final Map<String, Object> desc) {
        if (desc.get("metadata") instanceof Map<?, ?> metadata
                && metadata.get("annotations") instanceof Map<?, ?> annotations
                && annotations.get("io.yupiik.bundlebee/patchContentType") instanceof String value) {
            return Optional.of(value);
        }
        return empty();
    }

    public String toBaseUri(final Map<String, Object> desc, final String kindLowerCased, final String namespace) {
        try (final var client = container.lookup(KubernetesClient.class)) {
            final var api = client.instance();
            return ofNullable(resourceMapping.get(kindLowerCased))
                    .map(mapped -> !mapped.startsWith("http") ? api.base().toString() + mapped : mapped)
                    .or(() -> ofNullable(apiPreloader.getBaseUrls().get(kindLowerCased))
                            .map(url -> api.base().toString() + url.replace("${namespace}", namespace)))
                    .orElseGet(() -> api.base().toString() + findApiPrefix(kindLowerCased, desc)
                            + (!isSkipNameSpace(kindLowerCased) ? "/namespaces/" + namespace : "") + "/"
                            + kindLowerCased);
        }
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

    private String findApiPrefix(final String kindLowerCased, final Map<String, Object> desc) {
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
                return "/apis/" + desc.get("apiVersion");
            default:
                return "/api/v1";
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> injectMetadata(
            final Map<String, Object> rawDesc, final Map<String, String> customLabels) {
        final var result = new LinkedHashMap<String, Object>();
        rawDesc.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        if (rawDesc.get("metadata") instanceof Map<?, ?> rawMetadata) {
            final var metadata = new LinkedHashMap<String, Object>();
            ((Map<String, Object>) rawMetadata)
                    .entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
            final var labels = new LinkedHashMap<String, Object>();
            if (metadata.get(customMetadataInjectionPoint) instanceof Map<?, ?> rawLabels) {
                ((Map<String, Object>) rawLabels)
                        .entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .filter(entry -> !customLabels.containsKey(entry.getKey()))
                                .forEach(entry -> labels.put(entry.getKey(), entry.getValue()));
            }
            customLabels.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> labels.put(entry.getKey(), entry.getValue()));
            metadata.put(customMetadataInjectionPoint, labels);
            result.put("metadata", metadata);
        }
        return result;
    }

    public static class DescriptorItem {
        private final Map<String, Object> raw;
        private final Map<String, Object> prepared;

        public DescriptorItem(final Map<String, Object> raw, final Map<String, Object> prepared) {
            this.raw = raw;
            this.prepared = prepared;
        }

        public Map<String, Object> getRaw() {
            return this.raw;
        }

        public Map<String, Object> getPrepared() {
            return this.prepared;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof DescriptorItem other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getRaw(), other.getRaw())
                    && Objects.equals(this.getPrepared(), other.getPrepared());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof KubeClient.DescriptorItem;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getRaw(), this.getPrepared());
        }

        @Override
        public String toString() {
            return "KubeClient.DescriptorItem(raw=" + this.getRaw() + ", prepared=" + this.getPrepared() + ")";
        }
    }

    private final DeploymentConfiguration configuration;
}
