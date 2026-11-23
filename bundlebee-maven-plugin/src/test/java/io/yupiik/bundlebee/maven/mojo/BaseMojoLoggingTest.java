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
package io.yupiik.bundlebee.maven.mojo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;

class BaseMojoLoggingTest {
    @Test
    void preservesMavenFormattingAndSeverityWithoutOverridingJulLevels() {
        try (final var jul = new JulState()) {
            final var messages = new CopyOnWriteArrayList<String>();
            final var failure = new IllegalStateException("expected");
            final var mojo = new LoggingMojo(() -> {
                jul.logger.log(Level.INFO, "hello {0}", "Maven");
                jul.logger.log(Level.WARNING, "warning", failure);
                jul.logger.severe("error");
                jul.logger.fine("not-forwarded");
                jul.logger.setLevel(Level.SEVERE);
                jul.logger.info("filtered-by-existing-level");
            });
            mojo.setLog((Log) Proxy.newProxyInstance(
                    Log.class.getClassLoader(), new Class<?>[] {Log.class}, (proxy, method, args) -> {
                        if (args != null && args.length == 2) {
                            messages.add(method.getName() + ":" + args[0]);
                            if (method.getName().equals("warn")) {
                                assertEquals(failure, args[1]);
                            }
                        }
                        return method.getReturnType() == boolean.class ? true : null;
                    }));
            mojo.execute();
            assertEquals(List.of("info:hello Maven", "warn:warning", "error:error"), messages);
            assertEquals(List.of("hello {0}", "warning", "error", "not-forwarded"), jul.messages);
            assertEquals(Level.SEVERE, jul.logger.getLevel(), "cleanup must not undo another logger change");
        }
    }

    @Test
    void sharedWorkersNeverInheritInvocationOwnership() throws Exception {
        for (final var prewarmed : List.of(false, true)) {
            try (final var jul = new JulState()) {
                final var worker = Executors.newSingleThreadExecutor();
                try {
                    final var owners = Executors.newSingleThreadExecutor();
                    try {
                        if (prewarmed) {
                            get(worker.submit(() -> {}));
                        }
                        final var a = new CopyOnWriteArrayList<String>();
                        final var b = new CopyOnWriteArrayList<String>();
                        final var entered = new CountDownLatch(1);
                        final var release = new CountDownLatch(1);
                        final var first = owners.submit(() -> mojo(
                                        () -> {
                                            jul.logger.info("A-owner");
                                            // Also covers a worker first created during A, then reused by B.
                                            get(worker.submit(() -> jul.logger.info("A-worker")));
                                            entered.countDown();
                                            await(release);
                                        },
                                        a)
                                .execute());
                        try {
                            await(entered);
                            mojo(
                                            () -> {
                                                jul.logger.info("B-owner-active-A");
                                                get(worker.submit(() -> jul.logger.info("B-worker-active-A")));
                                            },
                                            b)
                                    .execute();
                            jul.assertUnchanged(1);
                        } finally {
                            release.countDown();
                        }
                        get(first);
                        mojo(
                                        () -> {
                                            jul.logger.info("B-owner-closed-A");
                                            get(worker.submit(() -> jul.logger.info("B-worker-closed-A")));
                                            final var child = new Thread(() -> jul.logger.info("B-child"));
                                            child.start();
                                            try {
                                                child.join(5000);
                                                assertTrue(!child.isAlive());
                                            } catch (final InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new IllegalStateException(e);
                                            }
                                        },
                                        b)
                                .execute();
                        get(worker.submit(() -> jul.logger.info("worker-after-all")));
                        jul.logger.info("after-all");
                        assertEquals(List.of("A-owner"), a);
                        assertEquals(List.of("B-owner-active-A", "B-owner-closed-A"), b);
                        assertEquals(
                                List.of(
                                        "A-owner",
                                        "A-worker",
                                        "B-owner-active-A",
                                        "B-worker-active-A",
                                        "B-owner-closed-A",
                                        "B-worker-closed-A",
                                        "B-child",
                                        "worker-after-all",
                                        "after-all"),
                                jul.messages);
                        jul.assertUnchanged(0);
                    } finally {
                        shutdown(owners);
                    }
                } finally {
                    shutdown(worker);
                }
            }
        }
    }

    @Test
    void isolatedPluginRealmsDoNotSnapshotOrRestoreEachOthersHandlers() throws Exception {
        for (final var firstFinishesFirst : List.of(false, true)) {
            try (final var jul = new JulState();
                    final var one = new Realm();
                    final var two = new Realm()) {
                final var owners = Executors.newFixedThreadPool(2);
                try {
                    final var a = new CopyOnWriteArrayList<String>();
                    final var b = new CopyOnWriteArrayList<String>();
                    final var enteredA = new CountDownLatch(1);
                    final var enteredB = new CountDownLatch(1);
                    final var releaseA = new CountDownLatch(1);
                    final var releaseB = new CountDownLatch(1);
                    final var classA = one.loadClass(LoggingMojo.class.getName());
                    final var classB = two.loadClass(LoggingMojo.class.getName());
                    assertNotSame(
                            classA.getSuperclass(), classB.getSuperclass(), "BaseMojo must really be realm-local");
                    final var mojoA = realmMojo(classA, () -> body(jul.logger, "A", enteredA, releaseA), a);
                    final var mojoB = realmMojo(classB, () -> body(jul.logger, "B", enteredB, releaseB), b);
                    final var first = owners.submit(() -> execute(mojoA));
                    Future<?> second = null;
                    try {
                        await(enteredA);
                        second = owners.submit(() -> execute(mojoB));
                        await(enteredB);
                        jul.assertUnchanged(2);
                        jul.logger.info("unowned-during-realms");
                        (firstFinishesFirst ? releaseA : releaseB).countDown();
                        get(firstFinishesFirst ? first : second);
                        jul.assertUnchanged(1);
                        (firstFinishesFirst ? releaseB : releaseA).countDown();
                        get(firstFinishesFirst ? second : first);
                        jul.assertUnchanged(0);
                        jul.logger.info("after-realms");
                        assertEquals(List.of("A-before", "A-after"), a);
                        assertEquals(List.of("B-before", "B-after"), b);
                        assertEquals(6, jul.messages.size());
                        for (final var message : List.of(
                                "A-before",
                                "A-after",
                                "B-before",
                                "B-after",
                                "unowned-during-realms",
                                "after-realms")) {
                            assertEquals(1, Collections.frequency(jul.messages, message));
                        }
                    } finally {
                        releaseA.countDown();
                        releaseB.countDown();
                        get(first);
                        if (second != null) {
                            get(second);
                        }
                    }
                } finally {
                    shutdown(owners);
                }
            }
        }
    }

    private static void shutdown(final ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "executor did not terminate");
            }
        } catch (final InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void body(
            final Logger logger, final String name, final CountDownLatch entered, final CountDownLatch release) {
        logger.info(name + "-before");
        entered.countDown();
        await(release);
        logger.info(name + "-after");
    }

    private static LoggingMojo mojo(final Runnable body, final List<String> messages) {
        final var mojo = new LoggingMojo(body);
        mojo.setLog(log(messages));
        return mojo;
    }

    private static AbstractMojo realmMojo(final Class<?> type, final Runnable body, final List<String> messages)
            throws Exception {
        final var mojo = (AbstractMojo) type.getConstructor(Runnable.class).newInstance(body);
        mojo.setLog(log(messages));
        return mojo;
    }

    private static void execute(final AbstractMojo mojo) {
        try {
            mojo.execute();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Log log(final List<String> messages) {
        return (Log) Proxy.newProxyInstance(
                Log.class.getClassLoader(), new Class<?>[] {Log.class}, (proxy, method, args) -> {
                    if (method.getName().equals("info") && args != null) {
                        messages.add(args[0].toString());
                    }
                    return method.getReturnType() == boolean.class ? true : null;
                });
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "test did not release execution");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void get(final Future<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Realm extends URLClassLoader {
        private Realm() {
            super(
                    new URL[] {
                        LoggingMojo.class.getProtectionDomain().getCodeSource().getLocation(),
                        BaseMojo.class.getProtectionDomain().getCodeSource().getLocation()
                    },
                    BaseMojoLoggingTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.equals(LoggingMojo.class.getName())
                    || name.equals(BaseMojo.class.getName())
                    || name.startsWith(BaseMojo.class.getName() + "$")) {
                var type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class JulState implements AutoCloseable {
        private final Logger logger = Logger.getLogger("io.yupiik.bundlebee");
        private final Handler[] handlers = logger.getHandlers();
        private final Level level = logger.getLevel();
        private final boolean parents = logger.getUseParentHandlers();
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final Handler original = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                messages.add(record.getMessage());
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
        private final Handler[] expected;

        private JulState() {
            logger.addHandler(original);
            logger.setLevel(Level.FINE);
            logger.setUseParentHandlers(false);
            expected = logger.getHandlers();
        }

        private void assertUnchanged(final int additions) {
            assertEquals(Level.FINE, logger.getLevel());
            assertEquals(false, logger.getUseParentHandlers());
            final var actual = logger.getHandlers();
            assertEquals(expected.length + additions, actual.length);
            assertArrayEquals(expected, java.util.Arrays.copyOf(actual, expected.length));
        }

        @Override
        public void close() {
            for (final var handler : logger.getHandlers()) {
                logger.removeHandler(handler);
            }
            for (final var handler : handlers) {
                logger.addHandler(handler);
            }
            logger.setLevel(level);
            logger.setUseParentHandlers(parents);
        }
    }
}
