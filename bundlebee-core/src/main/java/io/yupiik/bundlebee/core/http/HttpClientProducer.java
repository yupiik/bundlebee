/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.lang.ConfigHolder;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class HttpClientProducer implements ConfigHolder {
    @Inject
    @Description("How many threads are allocated to async HTTP client, negative or zero value means to use common pool.")
    @ConfigProperty(name = "bundlebee.httpclient.threads", defaultValue = "-1")
    private int threads;

    @Inject
    @Description("The HTTP client version, `none` mean the JVM default (v2), `HTTP_1_1` v1.1 and `HTTP_2` v2.0.")
    @ConfigProperty(name = "bundlebee.httpclient.forcedHttpVersion", defaultValue = "none")
    private String forcedHttpVersion;

    @Inject
    @Description("The HTTP client connect timeout (in java Duration format), `none` can be used to ignore this setting.")
    @ConfigProperty(name = "bundlebee.httpclient.connectTimeout", defaultValue = "none")
    private String connectTimeout;

    @Inject
    @Description("The HTTP client redirect policy. Default to `NORMAL`, can be set to `ALWAYS` or `NEVER`.")
    @ConfigProperty(name = "bundlebee.httpclient.followRedirects", defaultValue = "NORMAL")
    private String followRedirects;

    @Produces
    @BundleBee
    @ApplicationScoped
    public HttpClient httpClient() {
        final var executor = threads <= 0 && !isMaven() ?
                ForkJoinPool.commonPool() :
                Executors.newFixedThreadPool(Math.max(1, threads), new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable r) {
                        final var thread = new Thread(r, HttpClientProducer.class.getName() + "-" + counter.incrementAndGet());
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
        return builder.build();
    }

    private boolean isMaven() {
        return getClass().getClassLoader() != null &&
                getClass().getClassLoader().getClass() != null &&
                "org.codehaus.plexus.classworlds.realm.ClassRealm".equals(getClass().getClassLoader().getClass().getName());
    }

    public void release(@Disposes @BundleBee final HttpClient client) {
        if (threads <= 0) {
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
