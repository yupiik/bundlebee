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

import lombok.NoArgsConstructor;

import javax.enterprise.inject.Vetoed;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static lombok.AccessLevel.PRIVATE;

@Vetoed
@NoArgsConstructor(access = PRIVATE)
public final class CompletionFutures {
    private static final Logger LOGGER = Logger.getLogger(CompletionFutures.class.getName());

    public static <T> CompletionStage<T> handled(final Supplier<CompletionStage<T>> provider) {
        try {
            return provider.get();
        } catch (final RuntimeException re) {
            final var future = new CompletableFuture<T>();
            future.completeExceptionally(re);
            return future;
        }
    }

    // CompletableFuture.allOf() fails if any fails, here we enable to still return a result
    public static CompletionStage<?> chain(final Iterator<Supplier<CompletionStage<?>>> promises,
                                           final boolean stopOnError) {
        final var result = new CompletableFuture<>();
        if (!promises.hasNext()) {
            result.complete(true);
            return result;
        }
        try {
            promises.next().get()
                    .thenCompose(done -> chain(promises, stopOnError))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            LOGGER.log(Level.FINEST, e.getMessage(), e);
                            if (stopOnError || !promises.hasNext()) {
                                result.completeExceptionally(e);
                            } else {
                                chain(promises, false).whenComplete((r2, e2) -> {
                                    if (e2 != null) {
                                        LOGGER.log(Level.SEVERE, e2.getMessage(), e2);
                                        result.completeExceptionally(e2);
                                    } else {
                                        result.complete(r2);
                                    }
                                });
                            }
                        } else {
                            result.complete(r);
                        }
                    });
        } catch (final RuntimeException re) {
            if (stopOnError || !promises.hasNext()) {
                result.completeExceptionally(re);
            } else {
                chain(promises, false).whenComplete((r, e) -> {
                    if (e != null) {
                        result.completeExceptionally(e);
                    } else {
                        result.complete(r);
                    }
                });
            }
        }
        return result;
    }

    // CompletableFuture.allOf() fails if any fails, here we enable to still return a result
    public static <T, A, R> CompletionStage<R> all(final Collection<CompletionStage<T>> promises,
                                                   final Collector<T, A, R> collector,
                                                   final boolean failOnError) {
        if (promises.isEmpty()) {
            LOGGER.finest(() -> "Skipping execution since there is no promise");
            return completedFuture(collector.finisher().apply(collector.supplier().get()));
        }

        final var agg = collector.supplier().get();
        final var finisher = collector.finisher();
        final var accumulator = collector.accumulator();
        final var errors = new IllegalStateException("Invalid execution");
        final var remaining = new AtomicInteger(promises.size());
        final var result = new CompletableFuture<R>();

        LOGGER.finest(() -> "Aggregating " + promises.size() + " promises, aggregation id=" + System.identityHashCode(result));
        promises.forEach(promise -> promise.whenComplete((res, err) -> {
            LOGGER.finest(() -> "Got result, aggregation id=" + System.identityHashCode(result) + ", error=" + err + ", result=" + res);
            synchronized (agg) {
                if (err == null) {
                    accumulator.accept(agg, res);
                } else {
                    LOGGER.log(Level.FINEST, err.getMessage(), err);
                    errors.addSuppressed(CompletionException.class.isInstance(err) ?
                            err.getCause() : err);
                }
                if (remaining.decrementAndGet() == 0) {
                    if (!failOnError || errors.getSuppressed().length == 0) {
                        result.complete(finisher.apply(agg));
                    } else {
                        // recreate the exception to ensure the message is more readable
                        final var thrown = new IllegalStateException(Stream.of(errors.getSuppressed())
                                .map(t -> ofNullable(t.getMessage()).orElseGet(() -> t.getClass().getName()))
                                .collect(joining("\n")), null);
                        Stream.of(errors.getSuppressed()).forEach(thrown::addSuppressed);
                        result.completeExceptionally(thrown);
                    }
                }
            }
        }));
        return result;
    }
}