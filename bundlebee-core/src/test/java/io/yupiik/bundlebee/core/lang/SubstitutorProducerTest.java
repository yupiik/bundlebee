/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.lang;

import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.kube.HttpKubeClient;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import javax.enterprise.event.Event;
import javax.enterprise.event.NotificationOptions;
import javax.enterprise.util.TypeLiteral;
import javax.json.spi.JsonProvider;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
class SubstitutorProducerTest {
    private final Substitutor substitutor;

    public SubstitutorProducerTest() {
        final var client = new HttpKubeClient();
        try {
            final var namespace = HttpKubeClient.class.getDeclaredField("namespace");
            namespace.setAccessible(true);
            namespace.set(client, "default");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }

        final var producer = new SubstitutorProducer();
        final var provider = JsonProvider.provider();
        try {
            final var json = SubstitutorProducer.class.getDeclaredField("json");
            json.setAccessible(true);
            json.set(producer, provider);

            final var jsonBuilderFactory = SubstitutorProducer.class.getDeclaredField("jsonBuilderFactory");
            jsonBuilderFactory.setAccessible(true);
            jsonBuilderFactory.set(producer, provider.createBuilderFactory(Map.of()));

            final var httpKubeClient = SubstitutorProducer.class.getDeclaredField("httpKubeClient");
            httpKubeClient.setAccessible(true);
            httpKubeClient.set(producer, client);

            final var onPlaceholderEvent = SubstitutorProducer.class.getDeclaredField("onPlaceholderEvent");
            onPlaceholderEvent.setAccessible(true);
            onPlaceholderEvent.set(producer, new Event<OnPlaceholder>() {
                @Override
                public void fire(final OnPlaceholder event) {
                    // no-op
                }

                @Override
                public <U extends OnPlaceholder> CompletionStage<U> fireAsync(final U asyncEvent) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public <U extends OnPlaceholder> CompletionStage<U> fireAsync(final U asyncEvent, final NotificationOptions notificationOptions) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Event<OnPlaceholder> select(final Annotation... qualifiers) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public <U extends OnPlaceholder> Event<U> select(final Class<U> subtype, final Annotation... qualifiers) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public <U extends OnPlaceholder> Event<U> select(final TypeLiteral<U> subtype, final Annotation... qualifiers) {
                    throw new UnsupportedOperationException();
                }
            });

            substitutor = producer.substitutor(ConfigProvider.getConfig());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void directoryJsonKeyValuePairsContent() {
        assertEquals(
                "\"another/2.txt\":\"this\\nanother\\nfile = 2\\n\"," +
                        "\"file/1.txt\":\"this\\nis the file\\nnumber 1\\n\"",
                substitutor.getOrDefault("bundlebee-directory-json-key-value-pairs-content:src/test/resources/substitutor/json/content/*.txt", "failed"));
    }

    @Test
    void uppercase() {
        assertEquals(
                "UP",
                substitutor.getOrDefault("bundlebee-uppercase:up", "failed"));
    }

    @Test
    void digest() {
        assertEquals(
                "vo6GaAnToZqq622SpCHmng==",
                substitutor.getOrDefault("bundlebee-digest:base64,md5,was executed properly", "failed"));
    }

    @Test
    void jsr223() {
        assertEquals(
                "was executed properly",
                substitutor.getOrDefault("jsr223:'was executed properly';", "failed"));
    }

    @Test
    void logResolutions() {
        final var logger = Logger.getLogger(SubstitutorProducer.class.getName());
        final var captures = new ArrayList<String>();
        final var captureHandler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                captures.add(record.getMessage());
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() throws SecurityException {
                flush();
            }
        };
        final var level = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(captureHandler);
        try {
            substitutor.getOrDefault("bundlebee-base64:content", "failed");
        } finally {
            logger.setLevel(level);
            logger.removeHandler(captureHandler);
        }
        assertEquals(List.of("Resolved 'bundlebee-base64:content' to 'Y29udGVudA=='"), captures);
    }

    @Test
    void base64() {
        assertEquals(
                Base64.getEncoder().encodeToString("content".getBytes(StandardCharsets.UTF_8)),
                substitutor.getOrDefault("bundlebee-base64:content", "failed"));
    }

    @Test
    void base64File(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "content");
        assertEquals(
                Base64.getEncoder().encodeToString("content".getBytes(StandardCharsets.UTF_8)),
                substitutor.getOrDefault("bundlebee-base64-file:" + file, "failed"));
    }

    @Test
    void indent(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "content\n" +
                "  foo\n" +
                "bar\n" +
                "\n");
        assertEquals(
                "    content\n" +
                        "      foo\n" +
                        "    bar",
                substitutor.replace("{{bundlebee-strip-trailing:{{bundlebee-indent:4:{{bundlebee-inline-file:" + file + "}}}}}}"));
    }

    @Test
    void inlineFileFromFile(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "content");
        assertEquals(
                "content",
                substitutor.getOrDefault("bundlebee-inline-file:" + file, "failed"));
    }

    @Test
    void inlineFileFromResource() {
        assertEquals(
                "from \"resource\"",
                substitutor.getOrDefault("bundlebee-inline-file:substitutor/file", "failed"));
    }

    @Test
    void quotedFileFromFile(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "\"content\"");
        assertEquals(
                "\\\"content\\\"",
                substitutor.getOrDefault("bundlebee-quote-escaped-inline-file:" + file, "failed"));
    }

    @Test
    void quotedFileFromResource() {
        assertEquals(
                "from \\\"resource\\\"",
                substitutor.getOrDefault("bundlebee-quote-escaped-inline-file:substitutor/file", "failed"));
    }

    @Test
    void jsonFileFromFile(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "\"content\"");
        assertEquals(
                "\\\"content\\\"",
                substitutor.getOrDefault("bundlebee-json-inline-file:" + file, "failed"));
    }

    @Test
    void jsonFileFromResource() {
        assertEquals(
                "from \\\"resource\\\"",
                substitutor.getOrDefault("bundlebee-json-inline-file:substitutor/file", "failed"));
    }

    @Test
    void namespace() {
        assertEquals("default", substitutor.getOrDefault("bundlebee-kubernetes-namespace", "failed"));
    }
}
