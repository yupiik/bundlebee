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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.lifecycle.Init;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class HttpClientProducer {

    private HttpClient client;

    private final HttpClientConfiguration configuration;

    public HttpClientProducer(final HttpClientConfiguration configuration) {
        this.configuration = configuration;
    }

    @Init
    protected void init() {
        this.threads = configuration.threads();
        this.forcedHttpVersion = configuration.forcedHttpVersion();
        this.connectTimeout = configuration.connectTimeout();
        this.followRedirects = configuration.followRedirects();
    }

    private int threads;

    private String forcedHttpVersion;

    private String connectTimeout;

    private String followRedirects;

    @Bean
    @ApplicationScoped
    public HttpClient httpClient() {
        final var executor = threads <= 0 && !isMaven()
                ? ForkJoinPool.commonPool()
                : Executors.newFixedThreadPool(Math.max(1, threads), new ThreadFactory() {

                    private final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable r) {
                        final var thread =
                                new Thread(r, HttpClientProducer.class.getName() + "-" + counter.incrementAndGet());
                        thread.setContextClassLoader(HttpClientProducer.class.getClassLoader());
                        return thread;
                    }
                });
        final var builder = HttpClient.newBuilder().executor(executor);
        if (forcedHttpVersion != null && !forcedHttpVersion.isBlank() && !"none".equals(forcedHttpVersion)) {
            builder.version(HttpClient.Version.valueOf(forcedHttpVersion));
        }
        if (connectTimeout != null && !connectTimeout.isBlank() && !"none".equals(connectTimeout)) {
            builder.connectTimeout(Duration.parse(connectTimeout));
        }
        if (followRedirects != null && !followRedirects.isBlank()) {
            builder.followRedirects(HttpClient.Redirect.valueOf(followRedirects));
        }
        return client = new DelegatingHttpClient(builder.build()) {

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                    final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) {
                return // enforce the right classloader
                delegate.sendAsync(request, responseBodyHandler)
                        .whenCompleteAsync((r, t) -> {}, delegate.executor().orElseGet(ForkJoinPool::commonPool));
            }
        };
    }

    private boolean isMaven() {
        return getClass().getClassLoader() != null
                && getClass().getClassLoader().getClass() != null
                && "org.codehaus.plexus.classworlds.realm.ClassRealm"
                        .equals(getClass().getClassLoader().getClass().getName());
    }

    @Destroy
    public void release() {
        final var client = this.client;
        if (client == null) {
            return;
        }
        if (client.executor().orElse(null) == ForkJoinPool.commonPool()) {
            return;
        }
        final var es = ExecutorService.class.cast(client.executor().orElseThrow());
        es.shutdownNow();
        try {
            es.awaitTermination(2, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
