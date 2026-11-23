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

import static java.util.Comparator.comparing;
import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.kube.model.APIResourceList;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.lifecycle.Init;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Logger;

@ApplicationScoped
public class ApiPreloader {
    private final Logger log = Logger.getLogger(ApiPreloader.class.getName());

    public ApiPreloader(final JsonMapper jsonb, final KubernetesClient api) {
        this.jsonb = jsonb;
        this.api = api;
    }

    private final JsonMapper jsonb;
    private final KubernetesClient api;
    // we don't want to do 2 calls to get base urls at the same time
    private volatile CompletionStage<?> pending;
    private final Collection<String> fetchedResourceLists = new HashSet<>();
    private final Map<String, String> baseUrls = new ConcurrentHashMap<>();

    @Init
    protected void init() {
        // preload default resources
        chainedAPIResourceListFetch("/api/v1", () -> fetch("/api/v1"));
    }

    private CompletionStage<?> fetch(final String path) {
        return api.sendAsync(
                        HttpRequest.newBuilder(URI.create(api.base().toString() + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(r -> processResourceListDefinition(path, r));
    }

    @Destroy
    protected void destroy() {
        // pending can be resetted concurrently and we can't synchronized(this)
        // to avoid deadlocks so we just test the ref, this is sufficient here
        final CompletionStage<?> current = pending;
        if (current != null) {
            try {
                current.toCompletableFuture().get(1, TimeUnit.MINUTES);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final ExecutionException e) {
                log.log(SEVERE, e.getMessage(), e.getCause());
            } catch (final TimeoutException e) {
                log.log(SEVERE, e.getMessage(), e);
            }
        }
    }

    public CompletionStage<?> ensureResourceSpec(final Map<String, Object> desc, final String kindLowerCased) {
        final CompletionStage<?> ready;
        if (!baseUrls.containsKey(kindLowerCased)
                && desc.containsKey("apiVersion")
                && !"v1".equals(desc.get("apiVersion"))) {
            final var base = "/apis/" + desc.get("apiVersion");
            ready = chainedAPIResourceListFetch(base, () -> fetch(base));
        } else {
            ready = ofNullable(pending).orElseGet(() -> completedStage(null));
        }
        return ready;
    }

    private void processResourceListDefinition(final String base, final HttpResponse<String> response) {
        log.finest(() -> "Fetched " + response.uri() + ", status=" + response.statusCode());
        switch (response.statusCode()) {
            case 200:
                doProcessResourceListDefinition(base, jsonb.fromString(APIResourceList.class, response.body()));
                break;
            case 404:
                log.warning(() -> "Didn\'t find apiVersion \'" + response.uri() + "\', using default mapping");
                break;
            default:
                log.finest(() -> "Can\'t get apiVersion \'" + response.uri() + "\', status=" + response.statusCode()
                        + "\n" + response.body());
        }
    }

    // more accurate impl is
    // https://github.com/kubernetes/apimachinery/blob/dd0b9a0a73d89b90dbc4930db4f1e7dbdc6eb8c3/pkg/api/meta/restmapper.go#L192
    private void doProcessResourceListDefinition(final String base, final APIResourceList list) {
        if (list.resources() == null) {
            return;
        }
        final var
                newMappings = // sort to find the smallest first, this way if 2 equal kind exist we take the minimal one
                        // (Namespace name=namespaces vs name=namespaces/status for ex)
                        list.resources().stream()
                                .filter(it -> it.kind() != null)
                                .sorted(comparing(it -> ofNullable(it.name())
                                        .filter(v -> !v.isBlank())
                                        .or(() -> ofNullable(it.singularName()))
                                        .filter(v -> !v.isBlank())
                                        .orElse(it.kind())))
                                .collect(toMap(
                                        i -> i.kind().toLowerCase(ROOT) + 's',
                                        i -> ""
                                                + (i.group() != null && i.version() != null
                                                        ? "/apis/" + i.group() + "/" + i.version()
                                                        : base)
                                                + (i.namespaced() ? "/namespaces/${namespace}" : "") + '/'
                                                + ofNullable(i.name())
                                                        .filter(it -> !it.isBlank())
                                                        .orElse(i.singularName()), // since we sorted the list we
                                        // can take the first one
                                        // securely normally
                                        (a, b) -> a));
        // /!\ some url will be wrong but shouldn't be used like podexecoptions ->
        // /api/v1/namespaces/${namespace}/pods/exec
        // which is actually /api/v1/namespaces/${namespace}/pods/${name}/exec
        baseUrls.putAll(newMappings);
    }

    // we don't want to fetch twice the same api resource list
    private CompletionStage<?> chainedAPIResourceListFetch(
            final String marker, final Supplier<CompletionStage<?>> supplier) {
        synchronized (this) {
            if (!fetchedResourceLists.add(marker)) {
                return ofNullable(pending).orElseGet(() -> completedStage(null));
            }
            final CompletionStage<?> root = supplier.get();
            root.whenComplete((r, e) -> {
                synchronized (ApiPreloader.this) {
                    if (ApiPreloader.this.pending == root) {
                        // let it be gc
                        ApiPreloader.this.pending = null;
                    }
                }
                if (e != null) {
                    log.severe(e.getMessage());
                }
            });
            pending = pending == null ? root : pending.thenCompose(ignored -> root);
            return this.pending;
        }
    }

    public Map<String, String> getBaseUrls() {
        return this.baseUrls;
    }
}
