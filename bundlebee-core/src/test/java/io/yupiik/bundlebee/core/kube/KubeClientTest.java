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
package io.yupiik.bundlebee.core.kube;

import io.yupiik.bundlebee.core.test.http.SpyingResponseLocator;
import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.talend.sdk.component.junit.http.api.HttpApiHandler;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.internal.impl.ResponseImpl;
import org.talend.sdk.component.junit.http.junit5.HttpApi;
import org.talend.sdk.component.junit.http.junit5.HttpApiInject;

import javax.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@HttpApi(useSsl = true)
@Cdi
class KubeClientTest {
    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Inject
    private KubeClient client;

    @Test
    void apply(final TestInfo info) throws Exception {
        final var spyingResponseLocator = new SpyingResponseLocator(
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName());

        handler.setResponseLocator(spyingResponseLocator);
        client.apply("" +
                "apiVersion: v1\n" +
                "kind: ConfigMap\n" +
                "metadata:\n" +
                "  name: test-config\n" +
                "  namespace: default\n" +
                "data:\n" +
                "  foo: bar" +
                "", "yaml", Map.of()).toCompletableFuture().get();

        final var mocks = spyingResponseLocator.getFound();
        assertEquals(2, mocks.size());
        assertEquals(404, mocks.get(0).status());
        assertEquals(201, mocks.get(1).status());
    }

    @Test
    void customLabels(final TestInfo info) throws Exception {
        final var spyingResponseLocator = new SpyingResponseLocator(
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName()) {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                switch (request.method()) {
                    case "CONNECT":
                        return Optional.empty();
                    case "GET":
                        return Optional.of(new ResponseImpl(Map.of(), 404, "{}".getBytes(StandardCharsets.UTF_8)));
                    case "POST": // forward request payload to assert it
                        return Optional.of(new ResponseImpl(Map.of(), 201, request.payload().getBytes(StandardCharsets.UTF_8)));
                    default:
                        return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };

        { // no label originally
            handler.setResponseLocator(spyingResponseLocator);
            client.apply("" +
                    "apiVersion: v1\n" +
                    "kind: ConfigMap\n" +
                    "metadata:\n" +
                    "  name: test-config\n" +
                    "  namespace: default\n" +
                    "data:\n" +
                    "  foo: bar" +
                    "", "yaml", Map.of("bundlebee.foo", "bar", "bundlebee.dummy", "true")).toCompletableFuture().get();

            final var mocks = spyingResponseLocator.getFound();
            assertEquals(2, mocks.size());
            assertEquals("{" +
                            "\"apiVersion\":\"v1\"," +
                            "\"data\":{\"foo\":\"bar\"}," +
                            "\"kind\":\"ConfigMap\"," +
                            "\"metadata\":{\"name\":\"test-config\",\"namespace\":\"default\"," +
                            "\"labels\":{\"bundlebee.dummy\":\"true\",\"bundlebee.foo\":\"bar\"}}}",
                    new String(mocks.get(1).payload(), StandardCharsets.UTF_8));
        }
        spyingResponseLocator.getFound().clear();
        { // merged labels
            handler.setResponseLocator(spyingResponseLocator);
            client.apply("" +
                    "apiVersion: v1\n" +
                    "kind: ConfigMap\n" +
                    "metadata:\n" +
                    "  name: test-config\n" +
                    "  namespace: default\n" +
                    "  labels:\n" +
                    "    first: true\n" +
                    "data:\n" +
                    "  foo: bar" +
                    "", "yaml", Map.of("bundlebee.foo", "bar", "bundlebee.dummy", "true")).toCompletableFuture().get();

            final var mocks = spyingResponseLocator.getFound();
            assertEquals(2, mocks.size());
            assertEquals("{" +
                            "\"apiVersion\":\"v1\"," +
                            "\"data\":{\"foo\":\"bar\"}," +
                            "\"kind\":\"ConfigMap\"," +
                            "\"metadata\":{" +
                            "\"labels\":{\"first\":true,\"bundlebee.dummy\":\"true\",\"bundlebee.foo\":\"bar\"}," +
                            "\"name\":\"test-config\",\"namespace\":\"default\"}}",
                    new String(mocks.get(1).payload(), StandardCharsets.UTF_8));
        }
    }
}
