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
package io.yupiik.bundlebee.core.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

public class LoggingClient extends DelegatingClient {
    private final Logger logger;

    public LoggingClient(final Logger logger, final HttpClient delegate) {
        super(delegate);
        this.logger = logger;
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
        try {
            final var response = delegate.send(request, responseBodyHandler);
            logSuccess(request, response);
            return response;
        } catch (final RuntimeException re) {
            logError(request, re);
            throw re;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler) {
        return delegate.sendAsync(request, responseBodyHandler).whenComplete((r, e) -> {
            if (e != null) {
                logError(request, e);
            } else {
                logSuccess(request, r);
            }
        });
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler).whenComplete((r, e) -> {
            if (e != null) {
                logError(request, e);
            } else {
                logSuccess(request, r);
            }
        });
    }

    private String toCurl(final HttpRequest request) {
        return "curl -X" + request.method() +
                request.headers().map().entrySet().stream()
                        .map(h -> "-H '" + h.getKey() + ": " + String.join(", ", h.getValue()) + "'")
                        .collect(joining(" ", " ", "")) +
                " " + request.uri().toASCIIString() +
                (request.bodyPublisher().map(it -> {
                    try { // we only use ofString() publisher so we can re-read it this way safely
                        final var delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
                        it.subscribe(new Flow.Subscriber<>() {
                            @Override
                            public void onSubscribe(final Flow.Subscription subscription) {
                                delegate.onSubscribe(subscription);
                            }

                            @Override
                            public void onNext(final ByteBuffer item) {
                                delegate.onNext(List.of(item));
                            }

                            @Override
                            public void onError(final Throwable throwable) {
                                delegate.onError(throwable);
                            }

                            @Override
                            public void onComplete() {
                                delegate.onComplete();
                            }
                        });
                        return " -d '" + delegate.getBody().toCompletableFuture().get().replace("'", "\\'") + "'";
                    } catch (final RuntimeException | ExecutionException re) {
                        return " -d '?'";
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "";
                    }
                }).orElse(""));
    }

    private <T> String toResponse(final HttpResponse<T> response) {
        return "HTTP " + response.version() + "\n" +
                response.headers().map().entrySet().stream()
                        .map(h -> h.getKey() + ": " + String.join(", ", h.getValue()) + "")
                        .collect(joining("\n", "", "\n")) + '\n' +
                ofNullable(response.body()).map(String::valueOf).orElse(""); // simplified flavor
    }

    private void logError(final HttpRequest request, final Throwable re) {
        logger.log(Level.SEVERE, re, () -> "Request failed:\n" + toCurl(request));
    }

    private <T> void logSuccess(final HttpRequest request, final HttpResponse<T> response) {
        logger.info(() -> "Request succeeded:\n" + toCurl(request) + "\n" + toResponse(response));
    }
}
