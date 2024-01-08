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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import io.yupiik.bundlebee.core.test.http.SpyingResponseLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.talend.sdk.component.junit.http.api.HttpApiHandler;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.internal.impl.ResponseImpl;
import org.talend.sdk.component.junit.http.junit5.HttpApi;
import org.talend.sdk.component.junit.http.junit5.HttpApiInject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static java.util.logging.Level.INFO;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@HttpApi(useSsl = true)
class ApplyCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void patchCustomContentType(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch(
                "apply", "--alveolus", "customContentType", "--injectBundleBeeMetadata", "false", "--injectTimestamp", "false"));
        assertEquals("Deploying 'customContentType'\nApplying 's0' (kind=services) for namespace 'default'\n", logs);

        assertEquals(1, spyingResponseLocator.requests.size());
        assertEquals("custom/json", spyingResponseLocator.requests.get(0).headers().get("Content-Type"));
    }

    @Test
    void includeIfPatch(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        // apply it
        System.setProperty("ApplyCommandTest.includeIfPatch", "true");
        try {
            {
                executor.wrap(handler, INFO, () -> new BundleBee().launch(
                        "apply", "--alveolus", "ApplyCommandTest.includeIfPatch",
                        "--injectBundleBeeMetadata", "false", "--injectTimestamp", "false"));
                assertEquals(
                        Set.of("{\"apiVersion\":\"v1\",\"kind\":\"Service\"," +
                                "\"metadata\":{\"name\":\"s\",\"labels\":{\"app\":\"s-test\",\"patched\":\"true\"}}," +
                                "\"spec\":{\"type\":\"NodePort\",\"ports\":[{\"port\":1234,\"targetPort\":1234}],\"selector\":{\"app\":\"s-test\"}}}"),
                        spyingResponseLocator.requests.stream().map(Request::payload).collect(toSet()));
            }

            // skip it
            spyingResponseLocator.requests.clear();
            System.setProperty("ApplyCommandTest.includeIfPatch", "false");
            {
                executor.wrap(handler, INFO, () -> new BundleBee().launch(
                        "apply", "--alveolus", "ApplyCommandTest.includeIfPatch",
                        "--injectBundleBeeMetadata", "false", "--injectTimestamp", "false"));
                assertEquals(
                        Set.of("{\"apiVersion\":\"v1\",\"kind\":\"Service\"," +
                                "\"metadata\":{\"name\":\"s\",\"labels\":{\"app\":\"s-test\"}}," +
                                "\"spec\":{\"type\":\"NodePort\",\"ports\":[{\"port\":1234,\"targetPort\":1234}],\"selector\":{\"app\":\"s-test\"}}}"),
                        spyingResponseLocator.requests.stream().map(Request::payload).collect(toSet()));
            }
        } finally {
            System.clearProperty("ApplyCommandTest.includeIfPatch");
        }
    }

    @Test
    void fromTemplate(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch(
                "apply", "--alveolus", "ApplyCommandTest.fromTemplate",
                "--injectBundleBeeMetadata", "false", "--injectTimestamp", "false"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.fromTemplate'\n" +
                "Deploying 'ApplyCommandTest.simpleNestedDependencyWithReusingTheTemplate'\n" +
                "Deploying 'ApplyCommandTest.template'\n" +
                "Deploying 'ApplyCommandTest.template'\n" +
                "Applying 'bar' (kind=services) for namespace 'default'\n" +
                "Applying 'foo' (kind=services) for namespace 'default'\n" +
                "", logs);

        assertEquals(4, spyingResponseLocator.getFound().size());
        assertEquals(2, spyingResponseLocator.requests.size());
        assertEquals(
                Set.of(
                        "{\"apiVersion\":\"v1\",\"kind\":\"Service\",\"metadata\":{\"name\":\"bar\",\"labels\":{\"app\":\"my-app-2\",\"withdefault1\":\"with defaultvalue\",\"withdefault2\":\"with defaultvalue 2\"}},\"spec\":{\"type\":\"NodePort2\",\"ports\":[{\"port\":7070,\"targetPort\":7070}],\"selector\":{\"app\":\"my-app-2\"}}}",
                        "{\"apiVersion\":\"v1\",\"kind\":\"Service\",\"metadata\":{\"name\":\"foo\",\"labels\":{\"app\":\"my-app\",\"withdefault1\":\"with defaultvalue\",\"withdefault2\":\"with defaultvalue 2\"}},\"spec\":{\"type\":\"NodePort\",\"ports\":[{\"port\":9090,\"targetPort\":9090}],\"selector\":{\"app\":\"my-app\"}}}"),
                spyingResponseLocator.requests.stream().map(Request::payload).collect(toSet()));
    }

    @Test
    void apply(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch("apply", "--alveolus", "ApplyCommandTest.apply"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.apply'\n" +
                "Applying 's' (kind=services) for namespace 'default'\n" +
                "", logs);

        // ensure the expected number of requests was done - apply itself was tested in KubeClientTest
        assertEquals(2/*test exists + create*/, spyingResponseLocator.getFound().size());
    }

    @Test
    void applyAwait(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch("apply", "--alveolus", "ApplyCommandTest.applyAwait"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.applyAwait'\n" +
                "Applying 's' (kind=services) for namespace 'default'\n" +
                "", logs);

        assertEquals(3/*test exists + create + await*/, spyingResponseLocator.getFound().size());
    }

    @Test
    void applyAwaitCondition(final CommandExecutor executor, final TestInfo info) {
        final var retry = new AtomicInteger(2);
        final var spyingResponseLocator = new SpyingResponseLocator(
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName()) {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                switch (request.method()) {
                    case "CONNECT":
                        return Optional.empty();
                    case "GET":
                        if ("https://kubernetes.bundlebee.yupiik.test/api/v1/namespaces/default/services/s".equals(request.uri()) &&
                                retry.getAndDecrement() <= 0) {
                            return Optional.of(new ResponseImpl(Map.of(), 200, ("{\"status\":{\"phase\":\"Active\"}}").getBytes(StandardCharsets.UTF_8)));
                        }
                        return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                    case "PATCH":
                        return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                    default:
                        return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee()
                .launch("apply", "--alveolus", "ApplyCommandTest.applyAwaitCondition"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.applyAwaitCondition'\n" +
                "Applying 's' (kind=services) for namespace 'default'\n" +
                "", logs);

        assertEquals(4/*test exists + create + 2.await*/, spyingResponseLocator.getFound().size());
        assertEquals(-1, retry.get());
    }

    @Test
    void applyWithDependencies(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch("apply", "--alveolus", "ApplyCommandTest.withdep"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.withdep'\n" +
                "Deploying 'ApplyCommandTest.apply'\n" +
                "Applying 's' (kind=services) for namespace 'default'\n" +
                "Applying 's2' (kind=services) for namespace 'default'\n" +
                "", logs);

        // ensure the expected number of requests was done - apply itself was tested in KubeClientTest
        assertEquals(4/* 2 * (test exists + create)*/, spyingResponseLocator.getFound().size());
    }

    @Test
    void applyWithDependenciesWithExclude(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee()
                .launch("apply", "--alveolus", "ApplyCommandTest.withdep", "--excludedDescriptors", "ApplyCommandTest.d1"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.withdep'\n" +
                "Deploying 'ApplyCommandTest.apply'\n" +
                "Applying 's2' (kind=services) for namespace 'default'\n" +
                "", logs);
        assertEquals(2, spyingResponseLocator.getFound().size());
    }

    @Test
    void applyWithDependenciesAndExclude(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch("apply", "--alveolus", "ApplyCommandTest.withexclude"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.withexclude'\n" +
                "Deploying 'ApplyCommandTest.apply'\n" +
                "Applying 's2' (kind=services) for namespace 'default'\n" +
                "", logs);

        assertEquals(2, spyingResponseLocator.getFound().size());
    }

    @Test
    void applyWithDuplicatedDependencies(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch("apply", "--alveolus", "ApplyCommandTest.withsamedep"));
        assertEquals("" +
                "Deploying 'ApplyCommandTest.withsamedep'\n" +
                "Deploying 'ApplyCommandTest.apply'\n" +
                "Deploying 'ApplyCommandTest.withdep'\n" +
                "Deploying 'ApplyCommandTest.apply'\n" +
                "ApplyCommandTest.d1 already deployed, skipping\n" +
                "Applying 's' (kind=services) for namespace 'default'\n" +
                "Applying 's2' (kind=services) for namespace 'default'\n" +
                "Applying 's3' (kind=services) for namespace 'default'\n" +
                "", logs);

        assertEquals(6, spyingResponseLocator.getFound().size());
    }

    private StoringSpyingResponseLocator newSpyingHandler(final TestInfo info) {
        return new StoringSpyingResponseLocator(info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName());
    }

    private static class StoringSpyingResponseLocator extends SpyingResponseLocator {
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        public StoringSpyingResponseLocator(final String s) {
            super(s);
        }

        @Override
        protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                            final Predicate<String> headerFilter, final boolean exactMatching) {
            switch (request.method()) {
                case "CONNECT":
                    return Optional.empty();
                case "GET":
                    return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                case "PATCH":
                    requests.add(request);
                    assertFalse(request.payload().contains("$schema"), request::payload);
                    return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                default:
                    return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
