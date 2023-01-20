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
package io.yupiik.bundlebee.operator.launcher;

import io.yupiik.bundlebee.core.kube.HttpKubeClient;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.operator.BundlebeeOperator;
import io.yupiik.bundlebee.operator.handler.ActionHandler;
import io.yupiik.bundlebee.operator.model.Event;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.bind.Jsonb;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static java.net.http.HttpResponse.BodySubscribers.discarding;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@ApplicationScoped
public class OperatorLoop {
    private final Logger logger = Logger.getLogger(getClass().getName());

    @Inject
    private HttpKubeClient client;

    @Inject
    private ActionHandler actionHandler;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    @ConfigProperty(name = "bundlebee.operator.storage", defaultValue = "/opt/yupiik/state/bundlebee-operator")
    private String stateLocation;

    public void run(final AtomicBoolean running) {
        logger.info("Started Bundlebee Operator");
        final var hook = new Thread(() -> running.set(false), BundlebeeOperator.class.getName() + "-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        final var pool = createThreadPool();
        final var lastResourceVersionLocation = Path.of(stateLocation).resolve("lastResourceVersion");

        try {
            final var httpClient = this.client.getClient();

            // ensure we have basic permissions (smoke test)
            checkPerms();

            while (running.get()) {
                try {
                    final var lastResourceVersion = readLastResourceVersion(lastResourceVersionLocation);
                    logger.info("Starting to watch alveoli (resource version=" + lastResourceVersion + ")");

                    final var request = client.prepareRequest(
                            HttpRequest.newBuilder().header("Accept", "application/json"),
                            "/apis/bundlebee.yupiik.io/v1/namespaces/" + client.getNamespace() + "/alveoli?" +
                                    "watch=true&" +
                                    "includeUninitialized=false&" +
                                    "allowWatchBookmarks=true&" +
                                    (lastResourceVersion.isBlank() ? "" : ("&resourceVersion=" + lastResourceVersion)));
                    httpClient.send(request, info -> {
                        if (info.statusCode() != 200) {
                            logger.info(() -> "Got watch response: " + info.statusCode() + ", skipping");
                            return discarding();
                        }
                        logger.info(() -> "Got watch response: " + info.statusCode() + ", starting to watch");
                        return HttpResponse.BodySubscribers.fromLineSubscriber(new Flow.Subscriber<>() {
                            private Flow.Subscription subscription;

                            @Override
                            public void onSubscribe(final Flow.Subscription subscription) {
                                if (this.subscription != null) {
                                    this.subscription.cancel();
                                }
                                this.subscription = subscription;
                                this.subscription.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(final String line) {
                                final var model = jsonb.fromJson(line, Event.class);
                                if ("BOOKMARK".equalsIgnoreCase(model.getType())) {
                                    if (model.getObject().getMetadata().getResourceVersion() != null) {
                                        syncResourceVersion(model.getObject().getMetadata().getResourceVersion(), lastResourceVersionLocation);
                                    }
                                } else if ("ERROR".equalsIgnoreCase(model.getType())) {
                                    logger.log(SEVERE, () -> "Error event: '" + line + "'");
                                } else if (model.getType() != null) {
                                    // set it before the execution in case it fails
                                    // note: not a real locking but sufficient for now
                                    String max;
                                    try {
                                        max = max(readLastResourceVersion(lastResourceVersionLocation), model.getObject().getMetadata().getResourceVersion());
                                    } catch (final IOException e) {
                                        max = model.getObject().getMetadata().getResourceVersion();
                                    }
                                    if (max != null && !max.isBlank()) {
                                        syncResourceVersion(max, lastResourceVersionLocation);
                                    }
                                    pool.submit(() -> { // todo: rate limiting?
                                        try {
                                            actionHandler.onEvent(model.getType(), model.getObject());
                                        } catch (final RuntimeException re) {
                                            // don't quit the loop
                                            logger.log(SEVERE, re, re::getMessage);
                                        }
                                    });
                                }
                            }

                            @Override
                            public void onError(final Throwable throwable) {
                                logger.log(SEVERE, throwable, throwable::getMessage);
                            }

                            @Override
                            public void onComplete() {
                                logger.finest(() -> "Ending watching current request");
                            }
                        });
                    });
                } catch (final RuntimeException | IOException ex) {
                    logger.log(WARNING, ex, ex::getMessage);
                    try { // just give some space to recover
                        Thread.sleep(500);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (final InterruptedException ie) {
                    logger.info("Application interrupted");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException ise) {
                // no-op
            }
            stopPool(pool);
        }
    }

    private String readLastResourceVersion(final Path lastResourceVersionLocation) throws IOException {
        return Files.exists(lastResourceVersionLocation) ? Files.readString(lastResourceVersionLocation).strip() : "";
    }

    private void syncResourceVersion(final String resourceVersion, final Path lastResourceVersionLocation) {
        synchronized (lastResourceVersionLocation) {
            try {
                Files.writeString(lastResourceVersionLocation, resourceVersion);
            } catch (final IOException e) {
                logger.log(WARNING, e, e::getMessage);
            }
        }
    }

    private String max(final String current, final String newOne) {
        if (current == null) {
            return newOne;
        }
        if (newOne == null) {
            return current;
        }
        try {
            return Integer.parseInt(current) - Integer.parseInt(newOne) < 0 ? newOne : current;
        } catch (final NumberFormatException nfe) {
            return current; // wait for next bookmark
        }
    }

    private void checkPerms() {
        try {
            if (client.execute(
                            HttpRequest.newBuilder().header("Accept", "application/json"),
                            "/apis/bundlebee.yupiik.io/v1/namespaces/" + client.getNamespace() + "/alveoli?limit=1")
                    .toCompletableFuture()
                    .get()
                    .statusCode() != 200) {
                throw new IllegalStateException("Can't call Kubernetes API to get alveoli, check your role setup.");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stopPool(final ExecutorService pool) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(1, TimeUnit.MINUTES)) {
                logger.warning("Didn't stop properly in 1mn, giving up");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ExecutorService createThreadPool() {
        final int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        return new ForkJoinPool(threads, new ForkJoinPool.ForkJoinWorkerThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public ForkJoinWorkerThread newThread(final ForkJoinPool pool) {
                final var name = BundlebeeOperator.class.getName() + "-" + counter.incrementAndGet();
                final var thread = new ForkJoinWorkerThread(pool) {
                };
                thread.setName(name);
                thread.setContextClassLoader(BundlebeeOperator.class.getClassLoader());
                return thread;
            }
        }, (t, e) -> logger.log(SEVERE, e, e::getMessage), true);
    }
}
