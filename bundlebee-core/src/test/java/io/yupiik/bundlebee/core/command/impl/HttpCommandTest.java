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
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

@HttpApi(useSsl = true)
class HttpCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void get(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = new SpyingResponseLocator(
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName()) {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                switch (request.method()) {
                    case "CONNECT":
                        return Optional.empty();
                    case "GET":
                        return Optional.of(new ResponseImpl(Map.of(), 200, "{\"msg\":\"ok\"}".getBytes(StandardCharsets.UTF_8)));
                    default:
                        return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };
        handler.setResponseLocator(spyingResponseLocator);

        // payload only
        assertEquals("{\"msg\":\"ok\"}\n", executor.wrap(handler, INFO, () -> new BundleBee().launch("http")));
        assertEquals("" +
                "HTTP/1.1\n" +
                "content-length: 12\n" +
                "x-talend-proxy-junit: true\n" +
                "\n" +
                "{\"msg\":\"ok\"}\n", executor.wrap(handler, INFO, () -> new BundleBee().launch("http", "--payloadOnly", "false")));

        // ensure the expected number of requests was done - apply itself was tested in KubeClientTest
        assertEquals(2/*test exists + create*/, spyingResponseLocator.getFound().size(),
                () -> spyingResponseLocator.getFound().stream()
                        .map(r -> r.status() + "\n" + new String(r.payload(), StandardCharsets.UTF_8))
                        .collect(joining("\n----\n")));
    }
}
