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
package io.yupiik.bundlebee.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.lang.spi.BundleBeeResources;
import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import io.yupiik.bundlebee.lang.spi.PasswordResolver;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HelmFunctionRegistryTest {
    @Test
    void discoversAllFunctionsAndInjectsJsonMappers() {
        try (final var container = ConfiguringContainer.of().start();
                final var registry = container.lookup(HelmFunctionRegistry.class)) {
            final var functions = registry.instance().getFunctions();
            assertEquals(239, functions.size());
            assertEquals("HELLO", functions.get("upper").execute("hello"));
            for (final var name :
                    List.of("toJson", "mustToJson", "toPrettyJson", "mustToPrettyJson", "toRawJson", "mustToRawJson")) {
                final var encoded =
                        functions.get(name).execute(Map.of("message", "hello")).toString();
                for (final var decoder : List.of("fromJson", "mustFromJson")) {
                    assertEquals(
                            Map.of("message", "hello"), functions.get(decoder).execute(encoded));
                }
            }
        }
    }

    @Test
    void releasesCollectionDependenciesWithTheRegistry() {
        final var destroyed = new AtomicBoolean();
        final var function = new HelmFunction() {
            @Override
            public String name() {
                return "lifecycleProbe";
            }

            @Override
            public Object execute(final Object... args) {
                return "alive";
            }
        };
        final var bean = new FusionBean<HelmFunction>() {
            @Override
            public Type type() {
                return HelmFunction.class;
            }

            @Override
            public HelmFunction create(final RuntimeContainer container, final List<Instance<?>> dependents) {
                return function;
            }

            @Override
            public void destroy(final RuntimeContainer container, final HelmFunction instance) {
                destroyed.set(true);
            }
        };
        try (final var container = ConfiguringContainer.of().register(bean).start()) {
            try (final var registry = container.lookup(HelmFunctionRegistry.class)) {
                assertSame(function, registry.instance().getFunctions().get("lifecycleProbe"));
                assertFalse(destroyed.get());
            }
            assertTrue(destroyed.get(), "Closing the registry must release the injected function collection");
        }
    }

    @Test
    void rendersWithContainerNeutralServices(@TempDir final Path chart) throws Exception {
        Files.createDirectories(chart.resolve("templates"));
        Files.writeString(chart.resolve("Chart.yaml"), "name: test\nversion: 1.0.0\nappVersion: test\n");
        Files.writeString(
                chart.resolve("templates/resource.yaml"),
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: {{ .Release.Name }}-nginx\n"
                        + "  namespace: {{ .Release.Namespace }}\n");
        final var scheduler = Executors.newSingleThreadScheduledExecutor();
        final var client = HttpClient.newHttpClient();
        final var resources = new BundleBeeResources() {
            @Override
            public HttpClient httpClient() {
                return client;
            }

            @Override
            public ScheduledExecutorService scheduler() {
                return scheduler;
            }
        };
        try (final var container = ConfiguringContainer.of()
                .register(
                        new ProvidedInstanceBean<>(DefaultScoped.class, BundleBeeResources.class, () -> resources),
                        new ProvidedInstanceBean<>(
                                DefaultScoped.class, NamespaceProvider.class, () -> () -> "fusion-namespace"),
                        new ProvidedInstanceBean<>(
                                DefaultScoped.class, PasswordResolver.class, () -> id -> Optional.empty()))
                .start()) {
            try (final var renderer = container.lookup(HelmRenderer.class)) {
                final var rendered = renderer.instance()
                        .render(chart.toString(), Map.of(), "fusion-release", null)
                        .toCompletableFuture()
                        .get();
                final var yaml = String.join("\n---\n", rendered);
                assertTrue(yaml.contains("fusion-release-nginx"), yaml);
                assertTrue(yaml.contains("fusion-namespace"), yaml);
            }
            assertFalse(scheduler.isShutdown(), "Helm must not shut down borrowed resources");
        } finally {
            scheduler.shutdownNow();
        }
    }
}
