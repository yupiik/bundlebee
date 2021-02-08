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
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

@HttpApi(useSsl = true)
class DeleteCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void deleteMaven(final CommandExecutor executor, final TestInfo info) {
        {
            final var spyingResponseLocator = newSpyingHandler(info, false);
            handler.setResponseLocator(spyingResponseLocator);

            final var logs = executor.wrap(INFO, () -> new BundleBee()
                    .launch("delete", "--alveolus", "DeleteCommandTest.deleteMaven"));
            assertEquals("" +
                    "Deleting 'DeleteCommandTest.deleteMaven'\n" +
                    "Deleting 'ApplyCommandTest.apply'\n" +
                    "Deleting 's' (kind=services) for namespace 'default'\n" +
                    "Deleting 's2' (kind=services) for namespace 'default'\n" +
                    "", logs);
            assertEquals(2, spyingResponseLocator.getFound().size());
        }
        {
            final var spyingResponseLocator = newSpyingHandler(info, true);
            handler.setResponseLocator(spyingResponseLocator);

            final var logs = executor.wrap(INFO, () -> new BundleBee()
                    .launch("delete", "--alveolus", "DeleteCommandTest.deleteMaven"));
            assertEquals("" +
                    "Deleting 'DeleteCommandTest.deleteMaven'\n" +
                    "Deleting 'ApplyCommandTest.apply'\n" +
                    "Deleting 's' (kind=services) for namespace 'default'\n" +
                    "Deleting 's2' (kind=services) for namespace 'default'\n" +
                    "", logs);
            assertEquals(2, spyingResponseLocator.getFound().size());
        }
    }

    private SpyingResponseLocator newSpyingHandler(final TestInfo info, final boolean fail) {
        return new SpyingResponseLocator(
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName()) {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                switch (request.method()) {
                    case "CONNECT":
                        return Optional.empty();
                    case "DELETE":
                    case "GET":
                        if (fail) {
                            return Optional.of(new ResponseImpl(Map.of(), 404, "{}".getBytes(StandardCharsets.UTF_8)));
                        }
                        return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                    default:
                        return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };
    }
}
