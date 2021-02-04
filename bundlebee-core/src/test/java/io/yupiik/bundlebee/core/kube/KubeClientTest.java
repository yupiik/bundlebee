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
