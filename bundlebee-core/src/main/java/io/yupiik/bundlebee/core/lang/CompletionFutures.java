/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static lombok.AccessLevel.PRIVATE;

@Vetoed
@NoArgsConstructor(access = PRIVATE)
public final class CompletionFutures {
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
    public static <T, A, R> CompletionStage<R> all(final Collection<CompletionStage<T>> promises,
                                                   final Collector<T, A, R> collector,
                                                   final boolean failOnError) {
        if (promises.isEmpty()) {
            return completedFuture(collector.finisher().apply(collector.supplier().get()));
        }

        final var agg = collector.supplier().get();
        final var finisher = collector.finisher();
        final var accumulator = collector.accumulator();
        final var errors = new IllegalStateException("Invalid execution");
        final var remaining = new AtomicInteger(promises.size());
        final var result = new CompletableFuture<R>();
        promises.forEach(promise -> promise.whenComplete((res, err) -> {
            synchronized (agg) {
                if (err == null) {
                    synchronized (agg) {
                        accumulator.accept(agg, res);
                    }
                } else {
                    errors.addSuppressed(err);
                }
                if (remaining.decrementAndGet() == 0) {
                    if (!failOnError || errors.getSuppressed().length == 0) {
                        result.complete(finisher.apply(agg));
                    } else {
                        result.completeExceptionally(errors);
                    }
                }
            }
        }));
        return result;
    }
}