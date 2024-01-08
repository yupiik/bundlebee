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
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

@HttpApi(useSsl = true)
class DiffCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void diff(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(
                "diff", "--alveolus", "ApplyCommandTest.withdep"));
        assertEquals("" +
                "Diff:\n" +
                "diff --ApplyCommandTest.apply a/bundlebee/kubernetes/ApplyCommandTest.d1.yaml b/https://kubernetes.bundlebee.yupiik.test/api/v1/namespaces/default/services/s\n" +
                "type: JSON-Patch\n" +
                "[\n" +
                "  {\n" +
                "    \"op\":\"add\",\n" +
                "    \"path\":\"/metadata/labels/app\",\n" +
                "    \"value\":\"s-test\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"op\":\"add\",\n" +
                "    \"path\":\"/spec\",\n" +
                "    \"value\":{\n" +
                "      \"ports\":[\n" +
                "        {\n" +
                "          \"port\":1234,\n" +
                "          \"targetPort\":1234\n" +
                "        }\n" +
                "      ],\n" +
                "      \"selector\":{\n" +
                "        \"app\":\"s-test\"\n" +
                "      },\n" +
                "      \"type\":\"NodePort\"\n" +
                "    }\n" +
                "  }\n" +
                "]\n" +
                "diff --ApplyCommandTest.withdep a/bundlebee/kubernetes/ApplyCommandTest.d2.yaml b/https://kubernetes.bundlebee.yupiik.test/api/v1/namespaces/default/services/s2\n" +
                "type: Missing\n" +
                "{\n" +
                "  \"apiVersion\":\"v1\",\n" +
                "  \"kind\":\"Service\",\n" +
                "  \"metadata\":{\n" +
                "    \"name\":\"s2\",\n" +
                "    \"labels\":{\n" +
                "      \"app\":\"s-test\"\n" +
                "    }\n" +
                "  },\n" +
                "  \"spec\":{\n" +
                "    \"type\":\"NodePort\",\n" +
                "    \"ports\":[\n" +
                "      {\n" +
                "        \"port\":1235,\n" +
                "        \"targetPort\":1235\n" +
                "      }\n" +
                "    ],\n" +
                "    \"selector\":{\n" +
                "      \"app\":\"s-test\"\n" +
                "    }\n" +
                "  }\n" +
                "}\n", logs.replace('\\', '/'));
    }

    private StoringSpyingResponseLocator newSpyingHandler(final TestInfo info) {
        return new StoringSpyingResponseLocator(info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName());
    }

    private static class StoringSpyingResponseLocator extends SpyingResponseLocator {
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
                    final var name = request.uri().substring(request.uri().lastIndexOf('/') + 1);
                    if ("s2".equals(name)) {
                        return Optional.of(new ResponseImpl(Map.of(), 404, "missing".getBytes(StandardCharsets.UTF_8)));
                    }
                    return Optional.of(new ResponseImpl(
                            Map.of(), 200,
                            ("{" +
                                    "\"apiVersion\":\"v1\"," +
                                    "\"kind\":\"Service\"," +
                                    "\"metadata\":{" +
                                    " \"name\":\"" + name + "\"," +
                                    " \"uid\":\"1234\"," +
                                    " \"creationTimestamp\":\"2023-12-02T18:36:27Z\"," +
                                    " \"resourceVersion\":\"456\"," +
                                    " \"annotations\":{" +
                                    "   \"kubectl.kubernetes.io/last-applied-configuration\":\"{}\"," +
                                    "   \"cni.projectcalico.org/test\":\"true\"," +
                                    "   \"foo.kubernetes.io/bar\":\"yes\"" +
                                    " }," +
                                    " \"labels\":{}" +
                                    "}," +
                                    "\"status\":{\"state\":\"ok\"}" +
                                    "}").getBytes(StandardCharsets.UTF_8)));
                default:
                    return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
