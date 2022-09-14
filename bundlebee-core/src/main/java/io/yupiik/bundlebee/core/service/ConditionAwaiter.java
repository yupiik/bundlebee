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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonObject;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.anyOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.toList;

@Log
@ApplicationScoped
public class ConditionAwaiter {
    @Inject
    private KubeClient kube;

    @Inject
    private ConditionJsonEvaluator jsonEvalutor;

    @Inject
    @Description("" +
            "How often to retry for a descriptor condition. " +
            "Increasing it will reduce the pressure on the Kubernetes REST API (rate limiting for example).")
    @ConfigProperty(name = "bundlebee.awaiter.retryInterval", defaultValue = "500")
    private long awaitTimeout;

    public CompletionStage<Void> await(final String command,
                                       final AlveolusHandler.LoadedDescriptor loadedDescriptor,
                                       final ScheduledExecutorService scheduledExecutorService,
                                       final long awaitTimeout) {
        final var descriptor = loadedDescriptor.getConfiguration();
        if (descriptor == null) {
            return completedFuture(null);
        }
        final List<Manifest.AwaitConditions> awaitConditions = descriptor.getAwaitConditions() != null && !descriptor.getAwaitConditions().isEmpty() ?
                descriptor.getAwaitConditions().stream()
                        .filter(it -> ofNullable(it.getCommand()).orElse("apply").equalsIgnoreCase(command))
                        .collect(toList()) :
                List.of();
        if (!descriptor.isAwait() && awaitConditions.isEmpty()) {
            return completedFuture(null);
        }

        final var timeout = Instant.now().plusMillis(awaitTimeout);
        if (descriptor.isAwait()) {
            return exists(loadedDescriptor, scheduledExecutorService, timeout, !"delete".equals(command))
                    .thenCompose(done -> await(awaitConditions, scheduledExecutorService, timeout, loadedDescriptor));
        }
        return await(awaitConditions, scheduledExecutorService, timeout, loadedDescriptor);
    }

    private CompletionStage<Void> await(final List<Manifest.AwaitConditions> awaitConditions,
                                        final ScheduledExecutorService scheduledExecutorService,
                                        final Instant timeout,
                                        final AlveolusHandler.LoadedDescriptor loadedDescriptor) {
        if (awaitConditions == null || awaitConditions.isEmpty()) {
            return completedFuture(null);
        }
        return all(
                awaitConditions.stream()
                        .map(conditions -> await(conditions, scheduledExecutorService, timeout, loadedDescriptor))
                        .collect(toList()),
                counting(),
                true)
                .thenApply(i -> null);
    }

    private CompletionStage<Void> await(final Manifest.AwaitConditions awaitConditions,
                                        final ScheduledExecutorService scheduledExecutorService,
                                        final Instant timeout,
                                        final AlveolusHandler.LoadedDescriptor loadedDescriptor) {
        if (awaitConditions.getConditions() == null || awaitConditions.getConditions().isEmpty()) {
            return completedFuture(null);
        }
        // todo: do we want to use an all/any impl as in CompletionFutures or is this one ok
        //       -> functionally it is okish but some task can somehow leak the promise which is not always great
        final Function<CompletableFuture[], CompletionStage<Void>> combiner = awaitConditions.getOperator() == Manifest.ConditionOperator.ALL ?
                CompletableFuture::allOf :
                f -> anyOf(f).thenApply(i -> null);
        final var promises = awaitConditions.getConditions().stream()
                .map(condition -> await(condition, scheduledExecutorService, timeout, loadedDescriptor))
                .toArray(CompletableFuture[]::new);
        return combiner.apply(promises);
    }

    private CompletableFuture<Void> await(final Manifest.AwaitCondition condition,
                                          final ScheduledExecutorService scheduledExecutorService,
                                          final Instant timeout,
                                          final AlveolusHandler.LoadedDescriptor loadedDescriptor) {
        return withRetry(
                scheduledExecutorService, timeout, loadedDescriptor, condition::toString,
                () -> kube.getResources(loadedDescriptor.getContent(), loadedDescriptor.getExtension())
                        .thenApply(it -> isDryRun(it) ||
                                (it.stream().noneMatch(r -> r.statusCode() != 200) &&
                                        it.stream().anyMatch(r -> evaluate(condition, r.body())))));
    }

    private boolean isDryRun(final List<HttpResponse<JsonObject>> it) {
        return it.stream().anyMatch(i -> i.headers().firstValue("x-dry-run").map(Boolean::parseBoolean).orElse(false));
    }

    private boolean evaluate(final Manifest.AwaitCondition condition, final JsonObject body) {
        try {
            return jsonEvalutor.evaluate(condition, body);
        } catch (final RuntimeException je) {
            if (condition.getOperatorType() == Manifest.JsonPointerOperator.MISSING) {
                return true;
            }
            log.finest(() -> je.getMessage() + " (awaiting on " + condition + ")");
            return false;
        }
    }

    private CancellableRetriableTask exists(final AlveolusHandler.LoadedDescriptor loadedDescriptor,
                                            final ScheduledExecutorService scheduledExecutorService,
                                            final Instant timeout, final boolean expected) {
        return withRetry(
                scheduledExecutorService, timeout, loadedDescriptor,
                () -> "resource exists",
                () -> kube.exists(loadedDescriptor.getContent(), loadedDescriptor.getExtension())
                        .thenApply(it -> expected == it));
    }

    private CancellableRetriableTask withRetry(final ScheduledExecutorService scheduledExecutorService,
                                               final Instant timeout,
                                               final AlveolusHandler.LoadedDescriptor descriptor,
                                               final Supplier<String> timeoutDescriptor,
                                               final Supplier<CompletionStage<Boolean>> evaluator) {
        final var result = new CancellableRetriableTask();
        result.task = scheduledExecutorService.scheduleAtFixedRate(() -> evaluator.get().whenComplete((ok, ko) -> {
            if (ko != null) {
                log.log(FINEST, ko, () -> "waiting for " + descriptor + ": " + ko.getMessage());
            } else if (result.isDone() || result.isCompletedExceptionally()) { // we schedule at fixed rate instead of chaining scheduling so can happen
                result.cancel();
            } else if (ok) {
                log.finest(() -> "Condition for descriptor " + descriptor + " reached: " + timeoutDescriptor.get());
                result.complete(null);
                result.cancel();
            } else if (Instant.now().isAfter(timeout)) {
                log.finest(() -> "Timeout on condition " + descriptor + ": " + timeoutDescriptor.get());
                result.completeExceptionally(new IllegalArgumentException("Timeout awaiting " + descriptor.getConfiguration().getName() + ", condition: " + timeoutDescriptor.get()));
                result.cancel();
            } else {
                log.finest(() -> "Will retry the condition " + timeoutDescriptor.get() + " for descriptor " + descriptor);
            }
        }), awaitTimeout, awaitTimeout, MILLISECONDS);
        return result;
    }

    @RequiredArgsConstructor
    private static class CancellableRetriableTask extends CompletableFuture<Void> {
        private ScheduledFuture<?> task;

        public void cancel() {
            task.cancel(true);
        }
    }
}
