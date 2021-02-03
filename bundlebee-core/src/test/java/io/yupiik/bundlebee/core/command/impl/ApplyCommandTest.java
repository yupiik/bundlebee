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
class ApplyCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void apply(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(INFO, () -> new BundleBee().launch("apply", "--alveolus", "ApplyCommandTest.apply"));
        assertEquals("" +
                "Starting to deploy 'ApplyCommandTest.apply'\n" +
                "Applying 's' (kind=service) for namespace 'default'\n" +
                "", logs);

        // ensure the expected number of requests was done - apply itself was tested in KubeClientTest
        assertEquals(2/*test exists + create*/, spyingResponseLocator.getFound().size());
    }

    @Test
    void applyWithDependencies(final CommandExecutor executor, final TestInfo info) {
        final var spyingResponseLocator = newSpyingHandler(info);
        handler.setResponseLocator(spyingResponseLocator);

        final var logs = executor.wrap(INFO, () -> new BundleBee().launch("apply", "--alveolus", "ApplyCommandTest.withdep"));
        assertEquals("" +
                "Starting to deploy 'ApplyCommandTest.withdep'\n" +
                "Starting to deploy 'ApplyCommandTest.apply'\n" +
                "Applying 's' (kind=service) for namespace 'default'\n" +
                "Applying 's2' (kind=service) for namespace 'default'\n" +
                "", logs);

        // ensure the expected number of requests was done - apply itself was tested in KubeClientTest
        assertEquals(4/* 2 * (test exists + create)*/, spyingResponseLocator.getFound().size());
    }

    private SpyingResponseLocator newSpyingHandler(final TestInfo info) {
        return new SpyingResponseLocator(
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName()) {
            @Override
            protected Optional<Response> doFind(Request request, String pref, ClassLoader loader, Predicate<String> headerFilter, boolean exactMatching) {
                switch (request.method()) {
                    case "CONNECT":
                        return Optional.empty();
                    case "GET":
                    case "PATCH":
                        return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                    default:
                        return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };
    }
}
