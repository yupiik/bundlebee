/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.junit5.impl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.bundlebee.junit5.KubernetesApi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

public class KubernetesMock implements KubernetesApi, AutoCloseable {
    private HttpServer server;
    private IOPredicate<HttpExchange> onExchange;
    private List<Request> captured;

    // empty payloads, equivalent to dry-run, can need tuning - onExchange - for some deployment using secrets for ex
    private void doDefaultMock(final HttpExchange httpExchange) throws IOException {
        if (captured != null) {
            captured.add(freeze(httpExchange));
        }
        switch (httpExchange.getRequestMethod()) {
            case "CONNECT":
                return;
            case "GET":
            case "PATCH":
                httpExchange.sendResponseHeaders(200, 2);
                httpExchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                break;
            default: // error
                httpExchange.sendResponseHeaders(500, 2);
                httpExchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private Request freeze(final HttpExchange httpExchange) throws IOException {
        final var requestBody = httpExchange.getRequestBody();
        return new DefaultRequest(
                httpExchange.getRequestMethod(),
                httpExchange.getRequestURI().toASCIIString(),
                requestBody == null ? null : new String(requestBody.readAllBytes(), UTF_8),
                httpExchange.getRequestHeaders().entrySet().stream()
                        .filter(it -> it.getKey() != null && it.getValue() != null)
                        .collect(toMap(Map.Entry::getKey, e -> String.join(",", e.getValue()), (a, b) -> a)));
    }

    private void onRequest(final HttpExchange httpExchange) {
        try {
            if (onExchange == null || !onExchange.test(httpExchange)) {
                doDefaultMock(httpExchange);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } finally {
            httpExchange.close();
        }
    }

    public synchronized void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 1024);
            server.createContext("/").setHandler(this::onRequest);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Override
    public KubernetesApi capture() {
        captured = new CopyOnWriteArrayList<>();
        return this;
    }

    @Override
    public List<Request> captured() {
        return captured;
    }

    @Override
    public String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public KubernetesApi exchangeHandler(final IOPredicate<HttpExchange> handler) {
        this.onExchange = handler;
        return this;
    }

    private static class DefaultRequest implements Request {
        private final String method;
        private final String uri;
        private final String payload;
        private final Map<String, String> headers;

        private DefaultRequest(final String method, final String uri, final String payload, final Map<String, String> headers) {
            this.method = method;
            this.uri = uri;
            this.payload = payload;
            this.headers = headers;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String uri() {
            return uri;
        }

        @Override
        public Map<String, String> headers() {
            return headers;
        }

        @Override
        public String payload() {
            return payload;
        }
    }
}
