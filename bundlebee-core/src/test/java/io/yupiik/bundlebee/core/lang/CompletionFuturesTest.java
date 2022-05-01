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
package io.yupiik.bundlebee.core.lang;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.chain;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletionFuturesTest {
    @Test
    void emptyChain() throws ExecutionException, InterruptedException {
        // ensure it completes
        assertNotNull(chain(new Iterator<>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Supplier<CompletionStage<?>> next() {
                throw new UnsupportedOperationException();
            }
        }, true).toCompletableFuture().get());
    }

    @Test
    void oneTaskChain() throws ExecutionException, InterruptedException {
        final var counter = new AtomicInteger();
        assertNotNull(chain(List.<Supplier<CompletionStage<?>>>of(
                () -> completedFuture(counter.incrementAndGet())
        ).iterator(), true).toCompletableFuture().get());
        assertEquals(1, counter.get());
    }

    @Test
    void oneTaskErrorChain() {
        assertEquals(
                "test error",
                assertThrows(ExecutionException.class, () -> chain(List.<Supplier<CompletionStage<?>>>of(
                        () -> {
                            final var promise = new CompletableFuture<>();
                            promise.completeExceptionally(new IllegalArgumentException("test error"));
                            return promise;
                        }
                ).iterator(), true).toCompletableFuture().get()).getCause().getMessage());
    }

    @Test
    void twoTasksChain() throws ExecutionException, InterruptedException {
        final var counter = new AtomicInteger();
        assertNotNull(chain(List.<Supplier<CompletionStage<?>>>of(
                () -> completedFuture(counter.incrementAndGet()),
                () -> completedFuture(counter.incrementAndGet())
        ).iterator(), true).toCompletableFuture().get());
        assertEquals(2, counter.get());
    }

    @Test
    void twoTasksErrorChainStopOnError() {
        final var counter = new AtomicInteger();
        assertEquals(
                "test error 2",
                assertThrows(ExecutionException.class, () -> chain(List.<Supplier<CompletionStage<?>>>of(
                        () -> {
                            counter.incrementAndGet();
                            return completedFuture(true);
                        },
                        () -> {
                            final var promise = new CompletableFuture<>();
                            promise.completeExceptionally(new IllegalArgumentException("test error " + counter.incrementAndGet()));
                            return promise;
                        }
                ).iterator(), true).toCompletableFuture().get()).getCause().getMessage());
    }

    @Test
    void twoTasksFirstErrorChainStopOnError() {
        final var counter = new AtomicInteger();
        assertEquals(
                "test error 1",
                assertThrows(ExecutionException.class, () -> chain(List.<Supplier<CompletionStage<?>>>of(
                        () -> {
                            final var ctr = counter.incrementAndGet();
                            final var promise = new CompletableFuture<>();
                            promise.completeExceptionally(new IllegalArgumentException("test error " + ctr));
                            return promise;
                        },
                        () -> {
                            counter.incrementAndGet();
                            return completedFuture(true);
                        }
                ).iterator(), true).toCompletableFuture().get()).getCause().getMessage());
        assertEquals(1, counter.get());
    }

    @Test
    void twoTasksFirstErrorChainDontStopOnError() throws ExecutionException, InterruptedException {
        final var counter = new AtomicInteger();
        chain(List.<Supplier<CompletionStage<?>>>of(
                () -> {
                    final var ctr = counter.incrementAndGet();
                    final var promise = new CompletableFuture<>();
                    promise.completeExceptionally(new IllegalArgumentException("test error " + ctr));
                    return promise;
                },
                () -> {
                    counter.incrementAndGet();
                    return completedFuture(true);
                }
        ).iterator(), false).toCompletableFuture().get();
        assertEquals(2, counter.get());
    }
}
