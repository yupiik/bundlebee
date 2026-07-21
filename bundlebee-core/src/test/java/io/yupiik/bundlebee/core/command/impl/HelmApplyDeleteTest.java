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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@HttpApi(useSsl = true)
class HelmApplyDeleteTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void applyHelmChart(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee()
                .launch("apply", "--alveolus", "ApplyCommandTest.helm"));

        assertTrue(logs.contains("Deploying 'ApplyCommandTest.helm'"), logs);
        assertTrue(logs.contains("Applying"), logs);
        // Deployment and Service should be applied (2 resources)
        assertTrue(spyingResponseLocator.getRequests().size() >= 2,
                "Should apply at least deployment and service, got: " + spyingResponseLocator.getRequests().size());
    }

    @Test
    void deleteHelmChart(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee()
                .launch("delete", "--alveolus", "DeleteCommandTest.helm"));

        assertTrue(logs.contains("Deleting 'ApplyCommandTest.helm'"), logs);
        assertTrue(logs.contains("Deleting 'DeleteCommandTest.helm'"), logs);
        // DELETE requests for the resources
        assertTrue(spyingResponseLocator.getRequests().size() >= 2,
                "Should delete at least deployment and service");
    }

    private HelmSpyingResponseLocator newSpyingHandler(final TestInfo info) {
        return new HelmSpyingResponseLocator(info.getTestClass().orElseThrow().getName()
                + "_" + info.getTestMethod().orElseThrow().getName());
    }

    private static class HelmSpyingResponseLocator extends SpyingResponseLocator {
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        public HelmSpyingResponseLocator(final String s) {
            super(s);
        }

        public List<Request> getRequests() {
            return requests;
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
                case "POST":
                    requests.add(request);
                    return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                case "DELETE":
                    requests.add(request);
                    return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                default:
                    return Optional.of(new ResponseImpl(Map.of(), 500, "unsupported".getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
