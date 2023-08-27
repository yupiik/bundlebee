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
package io.yupiik.bundlebee.core.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class RateLimitedClient extends DelegatingClient {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final ReentrantLock lock = new ReentrantLock();

    private final RateLimiter clientRateLimiter;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean stopped = false;

    public RateLimitedClient(final HttpClient delegate, final RateLimiter clientRateLimiter) {
        super(delegate);
        this.clientRateLimiter = clientRateLimiter;
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
        final var pause = clientRateLimiter.before();
        try {
            if (pause > 0) {
                log(request, pause);
                Thread.sleep(pause);
                return send(request, responseBodyHandler);
            }
            final var res = super.send(request, responseBodyHandler);
            if (isRateLimited(res)) {
                Thread.sleep(findPause(res));
                return send(request, responseBodyHandler);
            }
            return res;
        } finally {
            clientRateLimiter.after();
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) {
        final var pause = clientRateLimiter.before();
        return wrap(pause, request, () -> super.sendAsync(request, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler, final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        final var pause = clientRateLimiter.before();
        return wrap(pause, request, () -> super.sendAsync(request, responseBodyHandler, pushPromiseHandler));
    }

    private void log(final HttpRequest request, final long pause) {
        logger.warning(() -> "Rate limiting (client side) " + request.method() + " " + request.uri() + " for " + pause + "ms");
    }

    private <T> CompletableFuture<HttpResponse<T>> wrap(final long pause, final HttpRequest request,
                                                        final Supplier<CompletableFuture<HttpResponse<T>>> promise) {
        if (pause == 0) {
            return promise.get().whenComplete((ok, ko) -> clientRateLimiter.after());
        }

        log(request, pause);
        final var scheduler = scheduledExecutorService();
        final var facade = new CompletableFuture<HttpResponse<T>>();
        scheduler.schedule(() -> wrap(clientRateLimiter.before(), request, () -> promise.get().whenComplete((ok, ko) -> {
            try {
                if (isRateLimited(ok)) {
                    final long newPause = findPause(ok);
                    wrap(newPause, request, promise);
                    return;
                }
                if (ko != null) {
                    facade.completeExceptionally(ko);
                } else {
                    facade.complete(ok);
                }
            } finally {
                clientRateLimiter.after();
            }
        })), pause, MILLISECONDS);
        clientRateLimiter.after();
        return facade;
    }

    private <T> long findPause(final HttpResponse<T> res) {
        final var headers = res.headers();
        return headers.firstValue("Retry-After")
                .map(a -> OffsetDateTime.parse(a.strip(), RFC_1123_DATE_TIME))
                .map(d -> Math.max(0, d.toInstant().toEpochMilli() - clientRateLimiter.getClock().millis()))
                .or(() -> headers.firstValue("X-Rate-Limit-Reset-Ms")
                        .map(Long::parseLong))
                .or(() -> headers.firstValue("X-Rate-Limit-Reset")
                        .map(it -> TimeUnit.SECONDS.toMillis(Long.parseLong(it))))
                .or(() -> headers.firstValue("Rate-Limit-Reset")
                        .map(it -> TimeUnit.SECONDS.toMillis(Long.parseLong(it))))
                .orElseGet(() -> (long) clientRateLimiter.getWindow());
    }

    private <T> boolean isRateLimited(final HttpResponse<T> ok) {
        return ok.statusCode() == 429;
    }

    private ScheduledExecutorService scheduledExecutorService() { // lazy to avoid to create it if never needed
        if (!stopped && scheduler == null) {
            lock.lock();
            try {
                if (!stopped && scheduler == null) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        final var thread = new Thread(r, RateLimitedClient.class.getName());
                        thread.setContextClassLoader(RateLimitedClient.class.getClassLoader());
                        return thread;
                    });
                }
            } finally {
                lock.unlock();
            }
        }
        return scheduler;
    }

    @Override
    public void close() throws Exception {
        stopped = true;
        final var ref = scheduler;
        if (ref != null) {
            ref.shutdownNow();
        }
        super.close();
    }
}
