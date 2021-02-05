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
import org.talend.sdk.component.junit.http.junit5.HttpApi;
import org.talend.sdk.component.junit.http.junit5.HttpApiInject;

import javax.inject.Inject;

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
                "", "yaml", false).toCompletableFuture().get();

        final var mocks = spyingResponseLocator.getFound();
        assertEquals(2, mocks.size());
        assertEquals(404, mocks.get(0).status());
        assertEquals(201, mocks.get(1).status());
    }
}
