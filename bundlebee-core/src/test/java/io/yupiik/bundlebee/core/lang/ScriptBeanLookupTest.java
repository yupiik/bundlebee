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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.configuration.ThreadLocalConfigSource;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.bundlebee.extension.ScriptExtension;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.script.ScriptEngineManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptBeanLookupTest {
    @Test
    void installedEngineSupportsExtensionNameAndMimeType() {
        final var manager = new ScriptEngineManager();
        assertTrue(manager.getEngineFactories().stream()
                .anyMatch(factory -> factory.getEngineName().equals("OpenJDK Nashorn")));
        try (final var container = container(new AtomicInteger())) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            for (final var language : List.of("js", "javascript", "application/javascript")) {
                assertEquals(
                        "ok", substitutor.replace("{{jsr223:// bundlebee.language: " + language + "\n'ok';}}", null));
            }
        }
    }

    @Test
    void evaluatesScriptFile(@TempDir final Path directory) throws Exception {
        final var script = directory.resolve("lookup.js");
        Files.writeString(script, "lookupByType.apply(Java.type('java.lang.StringBuilder').class).toString();");
        final var destroyed = new AtomicInteger();
        try (final var container = container(destroyed)) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            assertEquals("bean", substitutor.replace("{{jsr223:" + script + "}}", null));
            assertEquals(1, destroyed.get());
        }
    }

    @Test
    void reportsMissingEngineWithoutFallingBackToJavascript() {
        try (final var container = container(new AtomicInteger())) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            final var failure = assertThrows(
                    IllegalStateException.class,
                    () -> substitutor.replace(
                            "{{jsr223:// bundlebee.language: bundlebee-missing-engine\n'ok';}}", null));
            assertTrue(failure.getMessage().contains("No engine matching lang: 'bundlebee-missing-engine'"));
        }
    }

    @Test
    void nameLookupIsNotExposedAndEarlierTypeLookupsAreClosed() {
        final var destroyed = new AtomicInteger();
        try (final var container = container(destroyed)) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            final var failure = assertThrows(
                    RuntimeException.class,
                    () -> substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('java.lang.StringBuilder').class); lookupByName.apply('releaseValues');}}",
                            null));
            assertTrue(failure.toString().contains("lookupByName"), failure::toString);
            assertEquals("undefined", substitutor.replace("{{jsr223:typeof lookupByName;}}", null));
            assertEquals(1, destroyed.get());
        }
    }

    @Test
    void generatedExtensionIsDiscoverableAndObserversAreSynchronous() {
        try (final var container = ConfiguringContainer.of().start()) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, container, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            assertEquals(
                    "extension",
                    substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('" + ScriptExtension.class.getName()
                                    + "').class).value();}}",
                            null));
            final var placeholders = new HashMap<String, String>();
            container.emit(new OnPrepareDescriptor("script-extension", "app", "resource", "", placeholders));
            assertEquals("first,second", placeholders.get("observer.order"));
            assertEquals(Thread.currentThread().getName(), placeholders.get("observer.thread"));
            final var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> substitutor.replace("{{script-extension.fail:-value}}", "script-extension"));
            assertEquals("extension rejected placeholder", failure.getMessage());
        }
    }

    @Test
    void closesTypeLookupsAfterEvaluation() {
        final var destroyed = new AtomicInteger();
        try (final var container = container(destroyed)) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            assertEquals(
                    "bean",
                    substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('java.lang.StringBuilder').class).toString();}}",
                            null));
            assertEquals(1, destroyed.get());
            assertEquals(
                    "bean",
                    substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('java.lang.StringBuilder').class).toString();}}",
                            null));
            assertEquals(2, destroyed.get());
        }
    }

    @Test
    void closesLookupsWhenScriptFails() {
        final var destroyed = new AtomicInteger();
        try (final var container = container(destroyed)) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            assertThrows(
                    IllegalStateException.class,
                    () -> substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('java.lang.StringBuilder').class); throw new Error('expected');}}",
                            null));
            assertEquals(1, destroyed.get());
        }
    }

    @Test
    void recognizesLanguageMarker() {
        try (final var container = container(new AtomicInteger())) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            assertEquals("ok", substitutor.replace("{{jsr223:// bundlebee.language: javascript\n'ok';}}", null));
        }
    }

    @Test
    void closesLookupsOnlyAfterResultStringConversion() {
        final var value = new StringBuilder("bean");
        try (final var container = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, StringBuilder.class, () -> value) {
                    @Override
                    public void destroy(final RuntimeContainer container, final StringBuilder instance) {
                        instance.setLength(0);
                    }
                })
                .start()) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            assertEquals(
                    "bean",
                    substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('java.lang.StringBuilder').class);}}", null));
            assertEquals(0, value.length());
        }
    }

    @Test
    void closesEveryLookupInReverseOrderEvenIfDestructionFails() {
        final var created = new AtomicInteger();
        final var destroyed = new ArrayList<String>();
        try (final var container = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(
                        new ProvidedInstanceBean<>(
                                DefaultScoped.class,
                                StringBuilder.class,
                                () -> new StringBuilder("bean" + created.incrementAndGet())) {
                            @Override
                            public void destroy(final RuntimeContainer container, final StringBuilder instance) {
                                destroyed.add(instance.toString());
                                throw new IllegalArgumentException("close " + instance);
                            }
                        })
                .start()) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            final var failure = assertThrows(
                    IllegalStateException.class,
                    () -> substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('java.lang.StringBuilder').class); "
                                    + "lookupByType.apply(Java.type('java.lang.StringBuilder').class); throw new Error('evaluation failed');}}",
                            null));
            assertTrue(failure.getMessage().contains("evaluation failed"), failure::toString);
            assertEquals(List.of("bean2", "bean1"), destroyed);
            assertEquals("close bean2", failure.getSuppressed()[0].getMessage());
            assertEquals("close bean1", failure.getSuppressed()[0].getSuppressed()[0].getMessage());
        }
    }

    @Test
    void closesLookupsWhenResultStringConversionFails() {
        final var destroyed = new AtomicInteger();
        try (final var container = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(
                        new ProvidedInstanceBean<>(DefaultScoped.class, Object.class, () -> new Object() {
                            @Override
                            public String toString() {
                                throw new AssertionError("string conversion failed");
                            }
                        }) {
                            @Override
                            public void destroy(final RuntimeContainer container, final Object instance) {
                                destroyed.incrementAndGet();
                                throw new IllegalArgumentException("cleanup failed");
                            }
                        })
                .start()) {
            final var substitutor = new SubstitutorProducer(
                            null, null, null, container, null, null, new ThreadLocalConfigSource())
                    .substitutor(Configuration.of(Map.of()));
            final var failure = assertThrows(
                    AssertionError.class,
                    () -> substitutor.replace(
                            "{{jsr223:lookupByType.apply(Java.type('java.lang.Object').class);}}", null));
            assertEquals("string conversion failed", failure.getMessage());
            assertEquals(1, destroyed.get());
            assertEquals("cleanup failed", failure.getSuppressed()[0].getMessage());
        }
    }

    private RuntimeContainer container(final AtomicInteger destroyed) {
        return ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(
                        new ProvidedInstanceBean<>(
                                DefaultScoped.class, StringBuilder.class, () -> new StringBuilder("bean")) {
                            @Override
                            public void destroy(final RuntimeContainer container, final StringBuilder instance) {
                                destroyed.incrementAndGet();
                            }
                        })
                .start();
    }
}
