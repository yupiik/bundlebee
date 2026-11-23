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
package io.yupiik.bundlebee.core.lang;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.yupiik.bundlebee.core.configuration.ThreadLocalConfigSource;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.tools.codec.simple.SimpleCodec;
import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(PER_CLASS)
class SubstitutorProducerTest {

    private final Substitutor substitutor;

    private final RuntimeContainer container = ConfiguringContainer.of()
            .register(new ProvidedInstanceBean<>(
                    ApplicationScoped.class,
                    ConfigurationSource.class,
                    () -> key -> Map.of("decipher.masterKey", "test", "bundlebee.kube.skipLoadConfig", "true")
                            .get(key)))
            .start();

    public SubstitutorProducerTest() {
        try (final var instance = container.lookup(SubstitutorProducer.class)) {
            substitutor = instance.instance().substitutor(Configuration.of(Map.of("decipher.masterKey", "test")));
        }
    }

    @AfterAll
    void close() {
        container.close();
    }

    @Test
    void nestedResolutionPropagatesIdsAndEmitsResolvedFallbacks() {
        final var events = new ArrayList<OnPlaceholder>();
        final var producer =
                new SubstitutorProducer(null, null, null, null, null, null, new ThreadLocalConfigSource()) {
                    @Override
                    protected void onPlaceholder(
                            final String name, final String fallback, final String value, final String id) {
                        events.add(new OnPlaceholder(name, fallback, value, id));
                    }
                };
        producer.setInvocationPlaceholders(Map.of("value", "invocation"));
        producer.getByIdContextualPlaceholders().put("one", Map.of("key", "value", "value", "first"));
        producer.getByIdContextualPlaceholders().put("two", Map.of("key", "value", "value", "second"));
        final var substitute = producer.substitutor(Configuration.of(Map.of("key", "value", "value", "configured")));
        assertEquals("first", substitute.replace("{{{{key}}:-fallback}}", "one"));
        assertEquals("second", substitute.replace("{{{{key}}:-fallback}}", "two"));
        assertEquals("invocation", substitute.replace("{{{{key}}:-fallback}}", null));
        assertEquals("fallback", substitute.replace("{{missing:-fallback}}", null));
        assertEquals("null", substitute.replace("{{missing}}", null));
        assertEquals(
                List.of(
                        new OnPlaceholder("key", null, "value", "one"),
                        new OnPlaceholder("value", "fallback", "first", "one"),
                        new OnPlaceholder("key", null, "value", "two"),
                        new OnPlaceholder("value", "fallback", "second", "two"),
                        new OnPlaceholder("key", null, "value", null),
                        new OnPlaceholder("value", "fallback", "invocation", null),
                        new OnPlaceholder("missing", "fallback", "fallback", null),
                        new OnPlaceholder("missing", null, null, null)),
                events);
    }

    @Test
    void observerFailuresPropagateAfterSuccessfulAndFallbackResolution() {
        final var events = new ArrayList<OnPlaceholder>();
        final var lookupFailure = new IllegalArgumentException("lookup");
        final var observerFailure = new IllegalStateException("observer");
        final var producer =
                new SubstitutorProducer(null, null, null, null, null, null, new ThreadLocalConfigSource()) {
                    @Override
                    protected String doSubstitute(
                            final AtomicReference<Substitutor> self,
                            final Configuration config,
                            final String placeholder,
                            final String id) {
                        if ("failure".equals(placeholder)) {
                            throw lookupFailure;
                        }
                        return "value".equals(placeholder) ? "resolved" : null;
                    }

                    @Override
                    protected void onPlaceholder(
                            final String name, final String fallback, final String value, final String id) {
                        events.add(new OnPlaceholder(name, fallback, value, id));
                        throw observerFailure;
                    }
                };
        final var substitute = producer.substitutor(Configuration.of(Map.of()));
        assertSame(
                observerFailure,
                assertThrows(IllegalStateException.class, () -> substitute.replace("{{value:-fallback}}", "run")));
        assertSame(
                observerFailure,
                assertThrows(IllegalStateException.class, () -> substitute.replace("{{missing:-fallback}}", null)));
        assertSame(
                observerFailure,
                assertThrows(IllegalStateException.class, () -> substitute.replace("{{failure:-fallback}}", "run")));
        assertSame(
                lookupFailure,
                assertThrows(IllegalArgumentException.class, () -> substitute.replace("{{failure}}", "run")));
        assertEquals(
                List.of(
                        new OnPlaceholder("value", "fallback", "resolved", "run"),
                        new OnPlaceholder("missing", "fallback", "fallback", null),
                        new OnPlaceholder("failure", "fallback", "fallback", "run")),
                events);
    }

    @Test
    void nestedFileResolutionRetainsExecutionId(@TempDir final Path root) throws IOException {
        final var file = Files.writeString(root.resolve("nested.txt"), "{{value}}/{{executionId}}");
        final var events = new ArrayList<OnPlaceholder>();
        try (final var json = container.lookup(JsonMapper.class)) {
            final var withJson =
                    new SubstitutorProducer(
                            json.instance(), null, null, null, null, null, new ThreadLocalConfigSource()) {
                        @Override
                        protected void onPlaceholder(
                                final String name, final String fallback, final String value, final String id) {
                            events.add(new OnPlaceholder(name, fallback, value, id));
                        }
                    };
            withJson.getByIdContextualPlaceholders().put("run", Map.of("value", "contextual"));
            final var recursive = withJson.substitutor(Configuration.of(Map.of("value", "configured")));
            assertEquals("contextual/run", recursive.replace("{{bundlebee-json-inline-file:" + file + "}}", "run"));
            assertEquals("configured/", recursive.replace("{{bundlebee-json-inline-file:" + file + "}}", null));
        }
        assertEquals(
                List.of(
                        new OnPlaceholder("value", null, "contextual", "run"),
                        new OnPlaceholder("bundlebee-json-inline-file:" + file, null, "contextual/run", "run"),
                        new OnPlaceholder("value", null, "configured", null),
                        new OnPlaceholder("bundlebee-json-inline-file:" + file, null, "configured/", null)),
                events);
    }

    @Test
    void handlebarsPlaceholderHelperAcceptsExplicitNullId() {
        final var producer = new SubstitutorProducer(null, null, null, null, null, null, new ThreadLocalConfigSource());
        producer.getByIdContextualPlaceholders().put("run", Map.of("value", "contextual"));
        final var substitute = producer.substitutor(Configuration.of(Map.of("value", "configured")));
        try (final var json = container.lookup(JsonMapper.class)) {
            final var alveolus = json.instance()
                    .fromString(
                            Manifest.Alveolus.class, "{\"name\":\"app\",\"placeholders\":{\"input\":\"{{value}}\"}}");
            final var descriptor = new AlveolusHandler.LoadedDescriptor(null, "", "hb", null, null);
            final var template = "{{placeholder alveolus.input}}";
            assertEquals("contextual", substitute.replace(alveolus, descriptor, template, "run"));
            assertEquals("configured", substitute.replace(alveolus, descriptor, template, null));
        }
    }

    @Test
    void invocationPlaceholdersAreCopiedAndFallbackAfterContextualValues() {
        final var producer =
                new SubstitutorProducer(null, null, null, container, null, null, new ThreadLocalConfigSource());
        final var values = new HashMap<>(Map.of("message", "invocation", "kubeconfig", "placeholder-only"));
        producer.setInvocationPlaceholders(values);
        values.put("message", "mutated");
        final var config =
                Configuration.of(Map.of("message", "config", "fallback", "configured", "kubeconfig", "explicit"));
        final var substitute = producer.substitutor(config);
        producer.getByIdContextualPlaceholders().put("execution", Map.of("message", "context"));
        assertEquals("invocation", substitute.replace("{{message}}", null));
        assertEquals(
                "context/placeholder-only/configured",
                substitute.replace("{{message}}/{{kubeconfig}}/{{fallback}}", "execution"));
        assertEquals("placeholder-only", substitute.replace("{{kubeconfig}}", null));
        assertEquals("explicit", config.get("kubeconfig").orElseThrow());
        assertEquals(
                "config",
                new SubstitutorProducer(null, null, null, container, null, null, new ThreadLocalConfigSource())
                        .substitutor(config)
                        .replace("{{message}}", null));
    }

    @Test
    void executionPlaceholdersAreCopiedIsolatedAndReleased() {
        final var producer =
                new SubstitutorProducer(null, null, null, container, null, null, new ThreadLocalConfigSource());
        final var values = new HashMap<>(Map.of("message", "first"));
        producer.setInvocationPlaceholders("first-id", values);
        producer.setInvocationPlaceholders("second-id", Map.of("message", "second"));
        values.put("message", "mutated");
        final var config = Configuration.of(Map.of("message", "configured"));
        final var substitute = producer.substitutor(config);
        assertEquals("first", substitute.replace("{{message}}", "first-id"));
        assertEquals("second", substitute.replace("{{message}}", "second-id"));
        assertEquals("configured", substitute.replace("{{message}}", null));
        producer.getByIdContextualPlaceholders().put("first-id", Map.of("message", "context"));
        assertEquals("context", substitute.replace("{{message}}", "first-id"));
        producer.clearInvocationPlaceholders("first-id");
        assertEquals("configured", substitute.replace("{{message}}", "first-id"));
        assertEquals("second", substitute.replace("{{message}}", "second-id"));
        producer.clearInvocationPlaceholders("second-id");
        assertEquals("configured", substitute.replace("{{message}}", "second-id"));
    }

    @Test
    void decipher() {
        final var ciphered = new SimpleCodec(SimpleCodecConfiguration.builder()
                        .masterPassword("test")
                        .build())
                .encrypt("foo");
        assertEquals(
                "result=foo",
                substitutor.replace("result={{bundlebee-decipher:{{decipher.masterKey}}, " + ciphered + "  }}", null));
    }

    @Test
    void directoryJsonKeyValuePairsContent() {
        assertEquals(
                "\"another/2.txt\":\"this\\nanother\\nfile = 2\\n\","
                        + "\"file/1.txt\":\"this\\nis the file\\nnumber 1\\n\"",
                substitutor.getOrDefault(
                        "bundlebee-directory-json-key-value-pairs-content:src/test/resources/substitutor/json/content/*.txt",
                        "failed",
                        null));
    }

    @Test
    void ignoresMalformedSecretReferencesAndSelectsFirstMatchingName() {
        final var requested = new ArrayList<String>();
        final var client = new KubeClient(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public CompletionStage<Map<String, Object>> findServiceAccount(final String namespace, final String name) {
                return completedFuture(Map.of(
                        "secrets",
                        Arrays.asList(
                                null,
                                true,
                                42,
                                "invalid",
                                Map.of("name", 12),
                                Map.of(),
                                Map.of("name", "other"),
                                Map.of("name", "prefix-z"),
                                Map.of("name", "prefix-a"))));
            }

            @Override
            public CompletionStage<Map<String, Object>> findSecret(final String namespace, final String name) {
                requested.add(name);
                return completedFuture(Map.of("data", Map.of("token", "dmFsdWU=")));
            }
        };
        final var producer =
                new SubstitutorProducer(null, client, null, container, null, null, new ThreadLocalConfigSource());
        assertEquals(
                "value",
                producer.doSubstitute(
                        null,
                        Configuration.of(Map.of()),
                        "kubernetes/ns/serviceaccount/account/secrets/prefix-/data/token/0",
                        null));
        assertEquals(List.of("prefix-a"), requested);
    }

    @Test
    void uppercase() {
        assertEquals("UP", substitutor.getOrDefault("bundlebee-uppercase:up", "failed", null));
    }

    @Test
    void digest() {
        assertEquals(
                "vo6GaAnToZqq622SpCHmng==",
                substitutor.getOrDefault("bundlebee-digest:base64,md5,was executed properly", "failed", null));
    }

    @Test
    void jsr223() {
        assertEquals(
                "was executed properly", substitutor.getOrDefault("jsr223:'was executed properly';", "failed", null));
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
            substitutor.getOrDefault("bundlebee-base64:content", "failed", null);
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
                substitutor.getOrDefault("bundlebee-base64:content", "failed", null));
    }

    @Test
    void debase64() {
        assertEquals(
                "content",
                substitutor.getOrDefault(
                        "bundlebee-base64-decode:"
                                + Base64.getEncoder().encodeToString("content".getBytes(StandardCharsets.UTF_8)),
                        "failed",
                        null));
    }

    @Test
    void base64File(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "content");
        assertEquals(
                Base64.getEncoder().encodeToString("content".getBytes(StandardCharsets.UTF_8)),
                substitutor.getOrDefault("bundlebee-base64-file:" + file, "failed", null));
    }

    @Test
    void indent(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "content\n" + "  foo\n" + "bar\n" + "\n");
        assertEquals(
                "    content\n" + "      foo\n" + "    bar",
                substitutor.replace(
                        "{{bundlebee-strip-trailing:{{bundlebee-indent:4:{{bundlebee-inline-file:" + file + "}}}}}}",
                        null));
    }

    @Test
    void inlineFileFromFile(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "content");
        assertEquals("content", substitutor.getOrDefault("bundlebee-inline-file:" + file, "failed", null));
    }

    @Test
    void inlineFileFromResource() {
        assertEquals(
                "from \"resource\"",
                substitutor.getOrDefault("bundlebee-inline-file:substitutor/file", "failed", null));
    }

    @Test
    void quotedFileFromFile(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "\"content\"");
        assertEquals(
                "\\\"content\\\"",
                substitutor.getOrDefault("bundlebee-quote-escaped-inline-file:" + file, "failed", null));
    }

    @Test
    void quotedFileFromResource() {
        assertEquals(
                "from \\\"resource\\\"",
                substitutor.getOrDefault("bundlebee-quote-escaped-inline-file:substitutor/file", "failed", null));
    }

    @Test
    void jsonFileFromFile(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "\"content\"");
        assertEquals("\\\"content\\\"", substitutor.getOrDefault("bundlebee-json-inline-file:" + file, "failed", null));
    }

    @Test
    void jsonFileFromFileWithEscaping(@TempDir final Path root) throws IOException {
        final var file = root.resolve("test.txt");
        Files.writeString(file, "{\"foo\":\\{{dontescape}}}");
        assertEquals(
                "{\\\"foo\\\":{{dontescape}}}",
                substitutor.getOrDefault("bundlebee-json-inline-file:" + file, "failed", null));
    }

    @Test
    void jsonFileFromResource() {
        assertEquals(
                "from \\\"resource\\\"",
                substitutor.getOrDefault("bundlebee-json-inline-file:substitutor/file", "failed", null));
    }

    @Test
    void namespace() {
        assertEquals("default", substitutor.getOrDefault("bundlebee-kubernetes-namespace", "failed", null));
    }
}
