/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.kube.model.APIResourceList;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import lombok.Getter;
import lombok.extern.java.Log;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static java.util.Comparator.comparing;
import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toMap;

@Log
@ApplicationScoped
public class ApiPreloader {
    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    private HttpKubeClient api;

    private volatile CompletionStage<?> pending; // we don't want to do 2 calls to get base urls at the same time
    private final Collection<String> fetchedResourceLists = new HashSet<>();

    @Getter
    private final Map<String, String> baseUrls = new ConcurrentHashMap<>();

    @PostConstruct
    private void init() {
        // preload default resources
        chainedAPIResourceListFetch("/api/v1", () -> api.execute(HttpRequest.newBuilder(), "/api/v1")
                .thenAccept(r -> processResourceListDefinition("/api/v1", r)));
    }

    @PreDestroy
    private void destroy() {
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

    public CompletionStage<?> ensureResourceSpec(final JsonObject desc, final String kindLowerCased) {
        final CompletionStage<?> ready;
        if (!baseUrls.containsKey(kindLowerCased) && desc.containsKey("apiVersion") && !"v1".equals(desc.getString("apiVersion"))) {
            final var base = "/apis/" + desc.getString("apiVersion");
            ready = chainedAPIResourceListFetch(base, () -> api.execute(HttpRequest.newBuilder(), base)
                    .thenAccept(r -> processResourceListDefinition(base, r)));
        } else {
            ready = ofNullable(pending).orElseGet(() -> completedStage(null));
        }
        return ready;
    }

    private void processResourceListDefinition(final String base, final HttpResponse<String> response) {
        log.finest(() -> "Fetched " + response.uri() + ", status=" + response.statusCode());
        switch (response.statusCode()) {
            case 200:
                doProcessResourceListDefinition(base, jsonb.fromJson(response.body(), APIResourceList.class));
                break;
            case 404:
                log.warning(() -> "Didn't find apiVersion '" + response.uri() + "', using default mapping");
                break;
            default:
                log.finest(() -> "Can't get apiVersion '" + response.uri() + "', status=" + response.statusCode() + "\n" + response.body());
        }
    }

    // more accurate impl is https://github.com/kubernetes/apimachinery/blob/dd0b9a0a73d89b90dbc4930db4f1e7dbdc6eb8c3/pkg/api/meta/restmapper.go#L192
    private void doProcessResourceListDefinition(final String base, final APIResourceList list) {
        if (list.getResources() == null) {
            return;
        }
        final var newMappings = list.getResources().stream()
                .filter(it -> it.getKind() != null)
                // sort to find the smallest first, this way if 2 equal kind exist we take the minimal one
                // (Namespace name=namespaces vs name=namespaces/status for ex)
                .sorted(comparing(it -> ofNullable(it.getName())
                        .filter(v -> !v.isBlank())
                        .or(() -> ofNullable(it.getSingularName()))
                        .filter(v -> !v.isBlank())
                        .orElse(it.getKind())))
                .collect(toMap(i -> i.getKind().toLowerCase(ROOT) + 's', i -> "" +
                                (i.getGroup() != null && i.getVersion() != null ?
                                        "/apis/" + i.getGroup() + "/" + i.getVersion() :
                                        base) +
                                (i.isNamespaced() ? "/namespaces/${namespace}" : "") +
                                '/' + ofNullable(i.getName())
                                .filter(it -> !it.isBlank())
                                .orElse(i.getSingularName()),
                        // since we sorted the list we can take the first one securely normally
                        (a, b) -> a));
        // /!\ some url will be wrong but shouldn't be used like podexecoptions -> /api/v1/namespaces/${namespace}/pods/exec
        // which is actually /api/v1/namespaces/${namespace}/pods/${name}/exec
        baseUrls.putAll(newMappings);
    }

    // we don't want to fetch twice the same api resource list
    private CompletionStage<?> chainedAPIResourceListFetch(final String marker, final Supplier<CompletionStage<?>> supplier) {
        synchronized (this) {
            if (!fetchedResourceLists.add(marker)) {
                return ofNullable(pending).orElseGet(() -> completedStage(null));
            }
            final CompletionStage<?> root = supplier.get();
            root.whenComplete((r, e) -> {
                synchronized (ApiPreloader.this) {
                    if (ApiPreloader.this.pending == root) {
                        ApiPreloader.this.pending = null; // let it be gc
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
}
