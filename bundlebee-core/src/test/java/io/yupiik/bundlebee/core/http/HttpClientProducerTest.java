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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HttpClientProducerTest {
    @Test
    void typedConfigurationProducerAndExecutorLifecycleWorkInContainer() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        final ExecutorService executor;
        try {
            try (final var container = ConfiguringContainer.of()
                            .register(new ProvidedInstanceBean<>(
                                    ApplicationScoped.class,
                                    ConfigurationSource.class,
                                    () -> Map.of(
                                            "bundlebee.httpclient.threads", "2",
                                            "bundlebee.httpclient.forcedHttpVersion", "HTTP_1_1",
                                            "bundlebee.httpclient.connectTimeout", "PT3S",
                                            "bundlebee.httpclient.followRedirects", "NEVER")::get))
                            .start();
                    final var first = container.lookup(HttpClient.class);
                    final var second = container.lookup(HttpClient.class)) {
                final var client = first.instance();
                assertSame(client, second.instance());
                assertEquals(HttpClient.Version.HTTP_1_1, client.version());
                assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
                assertEquals(Duration.ofSeconds(3), client.connectTimeout().orElseThrow());
                executor = (ExecutorService) client.executor().orElseThrow();
                assertEquals(
                        204,
                        client.sendAsync(
                                        HttpRequest.newBuilder(URI.create("http://localhost:"
                                                        + server.getAddress().getPort()))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .get(10, TimeUnit.SECONDS)
                                .statusCode());
            }
            assertTrue(executor.isShutdown());
        } finally {
            server.stop(0);
        }
    }
}
