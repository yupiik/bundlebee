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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;

class BaseMojoConcurrencyTest {
    @Test
    void overlappingOwnersRestoreAndRouteOnSuccessAndFailure() throws Exception {
        for (final var fail : List.of(false, true)) {
            for (final var firstFinishesFirst : List.of(false, true)) {
                for (final var defaultLogging : List.of(false, true)) {
                    overlap(fail, firstFinishesFirst, defaultLogging);
                }
            }
        }
    }

    private void overlap(final boolean fail, final boolean firstFinishesFirst, final boolean defaultLogging)
            throws Exception {
        final var logger = Logger.getLogger("io.yupiik.bundlebee");
        final var handlers = logger.getHandlers();
        final var level = logger.getLevel();
        final var parents = logger.getUseParentHandlers();
        final var originalMessages = new CopyOnWriteArrayList<String>();
        final var original = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                originalMessages.add(record.getMessage());
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
        final var executor = Executors.newFixedThreadPool(2);
        final var one = new BlockingMojo("one", fail);
        final var two = new BlockingMojo("two", fail);
        final var useDefault = BaseMojo.class.getDeclaredField("useDefaultLogging");
        useDefault.setAccessible(true);
        useDefault.set(two, defaultLogging);
        try {
            logger.addHandler(original);
            logger.setLevel(Level.FINE);
            logger.setUseParentHandlers(true);
            final var expectedHandlers = logger.getHandlers();
            final var first = executor.submit(one::execute);
            assertTrue(one.entered.await(5, TimeUnit.SECONDS));
            final var second = executor.submit(two::execute);
            assertTrue(two.entered.await(5, TimeUnit.SECONDS), "commands must actually overlap");
            final var early = firstFinishesFirst ? one : two;
            final var late = firstFinishesFirst ? two : one;
            early.release.countDown();
            completed(firstFinishesFirst ? first : second, fail);
            assertEquals(Level.FINE, logger.getLevel());
            assertTrue(logger.getUseParentHandlers(), "remaining owner must not change JUL routing");
            assertEquals(
                    expectedHandlers.length + (late == two && defaultLogging ? 0 : 1), logger.getHandlers().length);
            late.release.countDown();
            completed(firstFinishesFirst ? second : first, fail);
            assertArrayEquals(expectedHandlers, logger.getHandlers());
            assertEquals(Level.FINE, logger.getLevel());
            assertTrue(logger.getUseParentHandlers());
            assertEquals(List.of("one-before", "one-after"), one.messages);
            assertEquals(defaultLogging ? List.of() : List.of("two-before", "two-after"), two.messages);
            // Additive forwarding leaves the original JUL route active for every record.
            assertEquals(4, originalMessages.size());
            for (final var message : List.of("one-before", "one-after", "two-before", "two-after")) {
                assertEquals(1, Collections.frequency(originalMessages, message));
            }
            logger.info("after-cleanup");
            assertEquals("after-cleanup", originalMessages.get(originalMessages.size() - 1));
            assertEquals(2, one.messages.size(), "finished mojo must not receive later logs");
        } finally {
            one.release.countDown();
            two.release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
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

    private void completed(final Future<?> future, final boolean fail) throws Exception {
        if (fail) {
            assertInstanceOf(
                    IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS))
                            .getCause());
        } else {
            future.get(5, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingMojo extends BaseMojo {
        private final String name;
        private final boolean fail;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private BlockingMojo(final String name, final boolean fail) {
            this.name = name;
            this.fail = fail;
            setLog((Log) Proxy.newProxyInstance(
                    Log.class.getClassLoader(), new Class<?>[] {Log.class}, (proxy, method, args) -> {
                        if (method.getName().equals("info") && args != null) {
                            messages.add(args[0].toString());
                        }
                        return method.getReturnType() == boolean.class ? true : null;
                    }));
        }

        @Override
        protected void doExecute(final ConfigurationSource configuration) {
            final var logger = Logger.getLogger("io.yupiik.bundlebee.concurrent");
            logger.info(name + "-before");
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release command");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            logger.info(name + "-after");
            if (fail) {
                throw new IllegalStateException("expected");
            }
        }
    }
}
