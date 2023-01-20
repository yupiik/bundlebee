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
package io.yupiik.bundlebee.operator;

import io.yupiik.bundlebee.core.command.impl.ApplyCommand;
import io.yupiik.bundlebee.operator.launcher.OperatorLoop;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.talend.sdk.component.junit.http.api.HttpApiHandler;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.api.ResponseLocator;
import org.talend.sdk.component.junit.http.internal.impl.DefaultResponseLocator;
import org.talend.sdk.component.junit.http.internal.impl.ResponseImpl;
import org.talend.sdk.component.junit.http.junit5.HttpApi;
import org.talend.sdk.component.junit.http.junit5.HttpApiInject;

import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.se.SeContainerInitializer;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessBeanAttributes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

@HttpApi(useSsl = true)
class BundlebeeOperatorTest {
    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void listen(final TestInfo info) throws InterruptedException {
        handler.setResponseLocator(newLocator(info));

        final var latch = new CountDownLatch(1);

        final var commands = new ArrayList<String>();

        try (final var container = SeContainerInitializer.newInstance()
                .addExtensions(new Extension() {
                    public void vetoDefaultCommands(@Observes final ProcessBeanAttributes<ApplyCommand> processBean) {
                        processBean.veto();
                    }

                    public void mockCommands(@Observes final AfterBeanDiscovery afterBeanDiscovery) {
                        afterBeanDiscovery.addBean()
                                .id("apply-mock")
                                .addQualifiers(Default.Literal.INSTANCE)
                                .scope(Dependent.class)
                                .beanClass(ApplyCommand.class)
                                .types(ApplyCommand.class, Object.class)
                                .createWith(c -> new ApplyCommand() {
                                    @Override
                                    public CompletionStage<?> execute() {
                                        final var marker = ConfigProvider.getConfig().getValue("bundlebee.apply.alveolus", String.class);
                                        synchronized (commands) {
                                            commands.add(marker);
                                        }
                                        latch.countDown();
                                        return completedFuture(true);
                                    }
                                });
                    }
                })
                .initialize()) {

            final var running = new AtomicBoolean(true);
            final var launcher = new Thread(() -> container.select(OperatorLoop.class).get().run(running));
            launcher.start();

            latch.await();

            running.set(false);
            launcher.join(TimeUnit.MINUTES.toMillis(5));
        }

        assertEquals(List.of("dummy"), commands);
    }

    private ResponseLocator newLocator(final TestInfo info) {
        final var counter = new AtomicInteger();
        return new DefaultResponseLocator(
                "yupiik",
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName()) {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                switch (request.method()) {
                    case "CONNECT":
                        return Optional.empty();
                    case "GET":
                        final var uri = request.uri();
                        if (uri.contains("watch") &&
                                uri.contains("/apis/bundlebee.yupiik.io/v1/namespaces/default/alveoli") &&
                                counter.compareAndSet(0, 1)) {
                            return Optional.of(new ResponseImpl(Map.of(), 200, ("" +
                                    "{\"type\":\"ADDED\",\"object\":{\"metadata\":{\"name\":\"bar\"},\"spec\":{\"args\":[\"--alveolus\",\"dummy\"]}}}\n" +
                                    "").getBytes(StandardCharsets.UTF_8)));
                        }
                        return Optional.of(new ResponseImpl(Map.of(), 200, "".getBytes(StandardCharsets.UTF_8)));
                    default:
                        return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };
    }
}
