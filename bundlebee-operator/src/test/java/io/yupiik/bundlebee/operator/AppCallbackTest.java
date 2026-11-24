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
package io.yupiik.bundlebee.operator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.bundlebee.core.service.CoreResources;
import io.yupiik.bundlebee.operator.handler.CommandRunner;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.kubernetes.operator.base.impl.OperatorRuntime;
import io.yupiik.kubernetes.operator.base.impl.Threads;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppCallbackTest {
    @TempDir
    private Path directory;

    @Test
    void applicationWatchReconnectsFromOpaqueBookmarkRecovers410AndDrainsOnClose() throws Exception {
        final var callbacks = new CopyOnWriteArrayList<List<String>>();
        final var queries = new CopyOnWriteArrayList<String>();
        final var authorizations = new CopyOnWriteArrayList<String>();
        final var applied = new CountDownLatch(3);
        final var deleted = new CountDownLatch(2);
        final var holdingWatch = new CountDownLatch(1);
        final var releaseWatch = new CountDownLatch(1);
        final var watches = new AtomicInteger();
        final var lists = new AtomicInteger();
        final var httpThreads = Executors.newCachedThreadPool();
        // Advance reconnect backoff immediately, without sleeps or wall-clock retry waits.
        final var scheduler = new ScheduledThreadPoolExecutor(4) {
            @Override
            public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
                return super.schedule(command, 0, TimeUnit.NANOSECONDS);
            }
        };
        final var runner = new CommandRunner(null, null, null) {
            @Override
            public void run(final String[] args) {
                callbacks.add(List.of(args));
                if ("delete".equals(args[0])) {
                    deleted.countDown();
                } else {
                    applied.countDown();
                }
            }
        };
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpThreads);
        server.createContext("/apis/bundlebee.yupiik.io/v1/namespaces/test/alveoli", exchange -> {
            try (exchange) {
                final var query = exchange.getRequestURI().getRawQuery();
                queries.add(query == null ? "" : query);
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                if (query == null || !query.contains("watch=true")) {
                    lists.incrementAndGet();
                    final var response =
                            "{\"metadata\":{\"resourceVersion\":\"list/opaque\"},\"items\":[]}".getBytes(UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    return;
                }
                exchange.sendResponseHeaders(200, 0);
                switch (watches.incrementAndGet()) {
                    case 1 -> {
                        exchange.getResponseBody()
                                .write((event("ADDED", "first") + event("MODIFIED", "updated")
                                                + "{\"type\":\"BOOKMARK\",\"object\":{\"metadata\":{\"resourceVersion\":\"bookmark/opaque\"}}}\n")
                                        .getBytes(UTF_8));
                    }
                    case 2 ->
                        exchange.getResponseBody()
                                .write("{\"type\":\"ERROR\",\"object\":{\"kind\":\"Status\",\"code\":410}}\n"
                                        .getBytes(UTF_8));
                    default -> {
                        exchange.getResponseBody()
                                .write((event("ADDED", "relisted") + event("DELETED", "last")).getBytes(UTF_8));
                        exchange.getResponseBody().flush();
                        holdingWatch.countDown();
                        try {
                            releaseWatch.await();
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        });
        server.start();
        final var settings = Map.of(
                "bundlebee.kube.api", "http://127.0.0.1:" + server.getAddress().getPort(),
                "bundlebee.kube.kubeconfig", "explicit",
                "bundlebee.kube.token", "startup-token",
                "bundlebee.operator.namespaces", "test",
                "operator.enabled", "true",
                "operator.probe-port", "-1",
                "operator.await", "false",
                "operator.storage", directory.toString());
        try {
            try (final var container = ConfiguringContainer.of()
                    .register(
                            new SelectedBean<>(
                                    Configuration.class, () -> key -> Optional.ofNullable(settings.get(key))),
                            new SelectedBean<>(Threads.class, () -> new Threads(scheduler)),
                            new SelectedBean<>(CommandRunner.class, () -> runner))
                    .start()) {
                assertTrue(
                        holdingWatch.await(5, TimeUnit.SECONDS),
                        () -> "Watch did not reconnect after 410: queries=" + queries + ", callbacks=" + callbacks);
                assertTrue(
                        applied.await(5, TimeUnit.SECONDS),
                        () -> "Apply callbacks were lost after watch recovery: " + callbacks);
                assertTrue(deleted.await(5, TimeUnit.SECONDS), () -> "Delete callback was lost: " + callbacks);
                try (final var selected = container.lookup(ScheduledExecutorService.class);
                        final var resources = container.lookup(CoreResources.class)) {
                    assertSame(scheduler, selected.instance());
                    assertSame(scheduler, resources.instance().scheduler());
                }
                assertEquals(
                        List.of(
                                List.of("apply", "--alveolus", "first"),
                                List.of("apply", "--alveolus", "updated"),
                                List.of("delete", "--alveolus", "updated"),
                                List.of("apply", "--alveolus", "relisted"),
                                List.of("delete", "--alveolus", "last")),
                        callbacks);
                assertEquals(2, lists.get());
                assertEquals(3, watches.get());
                assertTrue(
                        queries.stream().anyMatch(query -> query.contains("resourceVersion=bookmark%2Fopaque")),
                        queries.toString());
                assertTrue(authorizations.stream().allMatch("Bearer startup-token"::equals), authorizations.toString());
            }
            assertTrue(scheduler.isShutdown(), "The application must own and close its one scheduler");
            assertEquals(5, callbacks.size(), "Shutdown must not redeliver callbacks");
            assertFalse(Files.exists(directory.resolve("alveoli")), "Restart cursors must not be persisted");
            try (final var mapper = new JsonMapperImpl(List.of(), type -> Optional.empty())) {
                final var inventory =
                        mapper.fromString(Map.class, Files.readString(directory.resolve("alveoli.items")));
                assertEquals(List.of(), inventory.get("items"));
                assertEquals(Map.of("resourceVersion", "event/opaque"), inventory.get("metadata"));
            }
        } finally {
            releaseWatch.countDown();
            server.stop(0);
            httpThreads.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void applicationWatchStopsAfterFailedCallbackWithoutAdvancingInventory() throws Exception {
        final var callbacks = new CopyOnWriteArrayList<List<String>>();
        final var watches = new AtomicInteger();
        final var stopped = new CountDownLatch(1);
        final var releaseWatch = new CountDownLatch(1);
        final var scheduler = Executors.newScheduledThreadPool(2);
        final var httpThreads = Executors.newCachedThreadPool();
        final var runner = new CommandRunner(null, null, null) {
            @Override
            public void run(final String[] args) {
                callbacks.add(List.of(args));
                if (List.of(args).contains("fail")) {
                    throw new IllegalArgumentException("Rejected recipe");
                }
            }
        };
        final var diagnostics = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (record.getMessage().contains("handler failed; restart required; affected watch stopped")) {
                    stopped.countDown();
                }
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };
        final var logger = Logger.getLogger(OperatorRuntime.class.getName());
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpThreads);
        server.createContext("/apis/bundlebee.yupiik.io/v1/namespaces/test/alveoli", exchange -> {
            try (exchange) {
                final var query = exchange.getRequestURI().getRawQuery();
                if (query == null || !query.contains("watch=true")) {
                    final var response =
                            "{\"metadata\":{\"resourceVersion\":\"list/opaque\"},\"items\":[]}".getBytes(UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    return;
                }
                watches.incrementAndGet();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody()
                        .write((event("ADDED", "first") + event("MODIFIED", "fail") + event("MODIFIED", "must-not-run"))
                                .getBytes(UTF_8));
                exchange.getResponseBody().flush();
                try {
                    releaseWatch.await();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        logger.addHandler(diagnostics);
        server.start();
        final var settings = Map.of(
                "bundlebee.kube.api", "http://127.0.0.1:" + server.getAddress().getPort(),
                "bundlebee.kube.kubeconfig", "explicit",
                "bundlebee.operator.namespaces", "test",
                "operator.enabled", "true",
                "operator.probe-port", "-1",
                "operator.await", "false",
                "operator.storage", directory.toString());
        try {
            try (final var container = ConfiguringContainer.of()
                    .register(
                            new SelectedBean<>(
                                    Configuration.class, () -> key -> Optional.ofNullable(settings.get(key))),
                            new SelectedBean<>(Threads.class, () -> new Threads(scheduler)),
                            new SelectedBean<>(CommandRunner.class, () -> runner))
                    .start()) {
                assertTrue(
                        stopped.await(5, TimeUnit.SECONDS),
                        () -> "Failed callback did not stop its watch: " + callbacks);
            }
            assertEquals(
                    List.of(List.of("apply", "--alveolus", "first"), List.of("apply", "--alveolus", "fail")),
                    callbacks);
            assertEquals(1, watches.get(), "A failed deployment must not be silently skipped by reconnecting");
            assertTrue(scheduler.isShutdown());
            assertFalse(Files.exists(directory.resolve("alveoli")));
            try (final var mapper = new JsonMapperImpl(List.of(), type -> Optional.empty())) {
                final var inventory =
                        mapper.fromString(Map.class, Files.readString(directory.resolve("alveoli.items")));
                final var items = (List<?>) inventory.get("items");
                assertEquals(1, items.size());
                assertEquals(Map.of("args", List.of("--alveolus", "first")), ((Map<?, ?>) items.get(0)).get("spec"));
            }
        } finally {
            logger.removeHandler(diagnostics);
            releaseWatch.countDown();
            server.stop(0);
            httpThreads.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    private static String event(final String type, final String alveolus) {
        return "{\"type\":\"" + type
                + "\",\"object\":{\"metadata\":{\"uid\":\"uid\",\"name\":\"test\",\"namespace\":\"test\",\"resourceVersion\":\"event/opaque\"},\"spec\":{\"args\":[\"--alveolus\",\""
                + alveolus + "\"]}}}\n";
    }

    private static final class SelectedBean<T> extends ProvidedInstanceBean<T> {
        private SelectedBean(final Class<T> type, final java.util.function.Supplier<T> instance) {
            super(ApplicationScoped.class, type, instance);
        }

        @Override
        public int priority() {
            return 3000;
        }

        @Override
        public void destroy(final io.yupiik.fusion.framework.api.RuntimeContainer container, final T instance) {
            // ProvidedInstanceBean borrows by default; this fixture explicitly owns Threads.
            if (instance instanceof Threads threads) {
                try {
                    threads.close();
                } catch (final Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        }
    }
}
