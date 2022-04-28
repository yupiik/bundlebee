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
package io.yupiik.bundlebee.core.http;

import lombok.RequiredArgsConstructor;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

import static java.util.concurrent.CompletableFuture.completedFuture;

public class DryRunClient extends DelegatingClient {
    private final boolean skipDryRunForGet;

    public DryRunClient(final HttpClient delegate, final boolean skipDryRunForGet) {
        super(delegate);
        this.skipDryRunForGet = skipDryRunForGet;
    }

    private <T> HttpResponse<T> mockResponse(final HttpRequest request,
                                             final HttpResponse.BodyHandler<T> bodyHandler) {
        return new MockResponse<>(
                request, 200,
                HttpHeaders.of(Map.of("X-Dry-Run", List.of("true")), (a, b) -> true),
                bodyHandler);
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
        if (passthrough(request)) {
            return super.send(request, responseBodyHandler);
        }
        return mockResponse(request, responseBodyHandler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler) {
        if (passthrough(request)) {
            return super.sendAsync(request, responseBodyHandler);
        }
        final var httpResponse = mockResponse(request, responseBodyHandler);
        return delegate.executor()
                .map(e -> { // try to respect async contract
                    final var res = new CompletableFuture<HttpResponse<T>>();
                    try {
                        e.execute(() -> res.complete(httpResponse));
                    } catch (final RuntimeException re) {
                        res.complete(httpResponse);
                    }
                    return res;
                })
                .orElseGet(() -> completedFuture(httpResponse));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> responseBodyHandler,
                                                            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        if (passthrough(request)) {
            return super.sendAsync(request, responseBodyHandler, pushPromiseHandler);
        }
        return sendAsync(request, responseBodyHandler);
    }

    private boolean passthrough(final HttpRequest request) {
        return "GET".equalsIgnoreCase(request.method()) && skipDryRunForGet;
    }

    @RequiredArgsConstructor
    private static class MockResponse<T> implements HttpResponse<T>, HttpResponse.ResponseInfo {
        private final HttpRequest request;
        private final int status;
        private final HttpHeaders headers;
        private final BodyHandler<T> bodyHandler;

        @Override
        public String toString() {
            final URI uri = request().uri();
            return '(' + request().method() + " " + (uri == null ? "" : uri.toString()) + ") " + statusCode();
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public T body() {
            try {
                final var handler = bodyHandler.apply(this);
                sendEmptyJson(handler);
                return handler.getBody().toCompletableFuture().get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (final ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        private void sendEmptyJson(final BodySubscriber<T> handler) {
            handler.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(final long n) {
                    // no-op
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });
            handler.onNext(List.of(ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8))));
            handler.onComplete();
        }
    }
}
