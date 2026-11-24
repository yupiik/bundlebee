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
package io.yupiik.bundlebee.junit5.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.junit5.ValidateAlveolus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("bundlebee-jul-handlers")
class DefaultCommandExecutorTest {
    @Test
    void failurePropagatesAndRemovesLogHandler() throws Exception {
        final var logger = Logger.getLogger("io.yupiik.bundlebee.core");
        final var before = Arrays.asList(logger.getHandlers());
        final var executor =
                new DefaultCommandExecutor(configuration("invalid"), "http://localhost:1", new DefaultLogCapturer());
        assertThrows(RuntimeException.class, executor::run);
        assertTrue(executor.wasExecuted());
        assertEquals(before, Arrays.asList(logger.getHandlers()));

        final var healthy =
                new DefaultCommandExecutor(configuration("help"), "http://localhost:1", new DefaultLogCapturer());
        healthy.run();
        assertTrue(healthy.wasExecuted());
        assertEquals(before, Arrays.asList(logger.getHandlers()));
    }

    @Test
    void concurrentExecutorsDoNotCrossCapture() throws Exception {
        final var firstCapture = new DefaultLogCapturer();
        final var secondCapture = new DefaultLogCapturer();
        final var first = new DefaultCommandExecutor(configuration("help"), "http://localhost:1", firstCapture);
        final var second = new DefaultCommandExecutor(configuration("help"), "http://localhost:1", secondCapture);
        final var pool = Executors.newFixedThreadPool(2);
        try {
            final var a = pool.submit(first::run);
            final var b = pool.submit(second::run);
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(first.wasExecuted());
        assertTrue(second.wasExecuted());
        assertTrue(!firstCapture.all().isEmpty());
        assertEquals(
                firstCapture.all().stream().map(it -> it.getMessage()).toList(),
                secondCapture.all().stream().map(it -> it.getMessage()).toList());
        assertTrue(firstCapture.all().stream().noneMatch(secondCapture.all()::contains));
    }

    @Test
    void mockServerAcceptsRequests() throws Exception {
        try (final var mock = new KubernetesMock()) {
            mock.start();
            mock.capture();
            final var response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(mock.base() + "/test"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(1, mock.captured().size());
            assertEquals("/test", mock.captured().get(0).uri());
        }
    }

    private ValidateAlveolus configuration(final String name) throws Exception {
        return Configurations.class.getDeclaredMethod(name).getAnnotation(ValidateAlveolus.class);
    }

    private static class Configurations {
        @ValidateAlveolus(
                value = "ignored",
                command = "apply",
                options = {"--manifest", "/not/a/bundlebee/manifest.json", "--alveolus", "missing"})
        private void invalid() {
            // Annotation fixture, not a test method.
        }

        @ValidateAlveolus(value = "ignored", command = "help")
        private void help() {
            // Annotation fixture, not a test method.
        }
    }
}
