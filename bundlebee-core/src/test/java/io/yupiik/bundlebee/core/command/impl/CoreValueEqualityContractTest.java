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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.yupiik.bundlebee.core.command.impl.lint.ContextualLintError;
import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.bundlebee.core.event.OnKubeRequest;
import io.yupiik.bundlebee.core.event.OnLoadArchive;
import io.yupiik.bundlebee.core.event.OnManifestRead;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.bundlebee.core.service.VersioningService;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

public class CoreValueEqualityContractTest {
    @Test
    void nullableConstructorContracts() throws Exception {
        for (final var name : List.of(
                "io.yupiik.bundlebee.core.event.OnManifestRead",
                "io.yupiik.bundlebee.core.event.OnKubeRequest",
                "io.yupiik.bundlebee.core.event.OnLoadArchive",
                "io.yupiik.bundlebee.core.command.impl.BuildCommand$BuildResult",
                "io.yupiik.bundlebee.core.command.impl.PlaceholderExtractorCommand$Placeholder",
                "io.yupiik.bundlebee.core.command.impl.LintCommand$DecoratedLintError",
                "io.yupiik.bundlebee.core.command.impl.HelmCommand$Placeholder",
                "io.yupiik.bundlebee.core.command.impl.DiffCommand$ActualState",
                "io.yupiik.bundlebee.core.command.impl.DiffCommand$Item",
                "io.yupiik.bundlebee.core.command.impl.lint.LintError",
                "io.yupiik.bundlebee.core.command.impl.lint.ContextualLintError",
                "io.yupiik.bundlebee.core.service.Maven$Versions",
                "io.yupiik.bundlebee.core.service.VersioningService$SemanticVersion",
                "io.yupiik.bundlebee.core.kube.KubeClient$DescriptorItem")) {
            // Reflection keeps private command value types private in production.
            final var constructor = Class.forName(name).getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            final var types = constructor.getParameterTypes();
            final var empty = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                empty[i] = types[i] == int.class ? 0 : types[i] == boolean.class ? false : null;
            }
            assertContract(
                    constructor.newInstance(empty), constructor.newInstance(empty), constructor.newInstance(empty));
            for (int i = 0; i < types.length; i++) {
                final var value = sample(types[i]);
                if (value != null) {
                    final var populated = empty.clone();
                    populated[i] = value;
                    assertContract(
                            constructor.newInstance(populated),
                            constructor.newInstance(populated),
                            constructor.newInstance(populated));
                    assertNotEquals(
                            constructor.newInstance(empty),
                            constructor.newInstance(populated),
                            name + " argument " + i);
                    assertNotEquals(
                            constructor.newInstance(populated),
                            constructor.newInstance(empty),
                            name + " argument " + i);
                }
            }
        }
    }

    @Test
    void mutableEventAndServiceValues() {
        final var request =
                HttpRequest.newBuilder(URI.create("https://example.test/")).build();
        final var left = new OnKubeRequest(request);
        final var right = new OnKubeRequest(request);
        assertEquals(left, right);
        left.setUserRequest(request);
        assertNotEquals(left, right);
        right.setUserRequest(request);
        assertEquals(left, right);
        final var response = new CompletableFuture<HttpResponse<String>>();
        left.setUserResponse(response);
        assertNotEquals(left, right);
        right.setUserResponse(response);
        assertEquals(left.hashCode(), right.hashCode());
        assertEquals(left, right);

        final var archive = new OnLoadArchive("g:a:1");
        final var otherArchive = new OnLoadArchive("g:a:1");
        archive.setLoader(new CompletableFuture<>());
        assertNotEquals(archive, otherArchive);
        otherArchive.setLoader(archive.getLoader());
        assertEquals(archive, otherArchive);
        assertEquals(archive.hashCode(), otherArchive.hashCode());

        final var server = new Maven.Server();
        final var otherServer = new Maven.Server();
        assertEquals(server, otherServer);
        server.setId("repo");
        assertNotEquals(server, otherServer);
        otherServer.setId("repo");
        server.setUsername("user");
        assertNotEquals(server, otherServer);
        otherServer.setUsername("user");
        server.setPassword("secret");
        assertNotEquals(server, otherServer);
        otherServer.setPassword("secret");
        assertEquals(server, otherServer);
        assertEquals(server.hashCode(), otherServer.hashCode());

        final var spy = new HelmCommand.SpyCollector();
        final var otherSpy = new HelmCommand.SpyCollector();
        assertEquals(spy, otherSpy);
        spy.getIgnoredKeys().add("ignored");
        assertNotEquals(spy, otherSpy);
        otherSpy.getIgnoredKeys().add("ignored");
        assertEquals(spy, otherSpy);
        assertEquals(spy.hashCode(), otherSpy.hashCode());
    }

    @Test
    void inheritanceAndGetterSemantics() {
        final var base = new LintError(LintError.LintLevel.ERROR, "message");
        final var contextual = new ContextualLintError(LintError.LintLevel.ERROR, "message", "a", "d");
        assertNotEquals(base, contextual);
        assertNotEquals(contextual, base);
        assertNotEquals(contextual, new ContextualLintError(LintError.LintLevel.WARNING, "message", "a", "d"));
        assertNotEquals(contextual, new ContextualLintError(LintError.LintLevel.ERROR, "other", "a", "d"));
        assertEquals(
                new ContextualLintError(LintError.LintLevel.ERROR, "message", "a", "d").hashCode(),
                contextual.hashCode());

        final var overridden = new LintError(null, "stored") {
            @Override
            public String getMessage() {
                return "visible";
            }
        };
        final var visible = new LintError(null, "visible");
        assertEquals(visible, overridden);
        assertEquals(overridden, visible);
        assertEquals(visible.hashCode(), overridden.hashCode());

        final var restricted = new OnManifestRead(null) {
            @Override
            protected boolean canEqual(final Object other) {
                return other == this;
            }
        };
        assertEquals(restricted, restricted);
        assertNotEquals(new OnManifestRead(null), restricted);

        final var item = new KubeClient.DescriptorItem(null, null) {
            @Override
            public Map<String, Object> getRaw() {
                return Map.of("visible", true);
            }
        };
        final var visibleItem = new KubeClient.DescriptorItem(Map.of("visible", true), null);
        assertEquals(visibleItem, item);
        assertEquals(item, visibleItem);
        assertEquals(visibleItem.hashCode(), item.hashCode());
    }

    @Test
    void placeholderArraysUseContents() throws Exception {
        final Constructor<?> constructor = Class.forName(HelmCommand.class.getName() + "$Placeholder")
                .getDeclaredConstructor(String[].class, OnPlaceholder.class, String.class);
        constructor.setAccessible(true);
        final var left = constructor.newInstance(new String[] {"a", null, "b"}, null, "comment");
        final var right = constructor.newInstance(new String[] {"a", null, "b"}, null, "comment");
        final var third = constructor.newInstance(new String[] {"a", null, "b"}, null, "comment");
        assertContract(left, right, third);
        assertNotEquals(left, constructor.newInstance(new String[] {"a", "b", null}, null, "comment"));
        assertNotEquals(left, constructor.newInstance(null, null, "comment"));
    }

    @Test
    void semanticVersionRetainsAllComponents() {
        final var left = new VersioningService.SemanticVersion("1.2", 1, 2, 0, false);
        final var service = new VersioningService();
        assertEquals(left, service.toSemanticVersion("1.2"));
        assertEquals(new VersioningService.SemanticVersion("1.2.3", 1, 2, 3, true), service.toSemanticVersion("1.2.3"));
        assertEquals(new VersioningService.SemanticVersion("1.2.", 1, 2, 0, false), service.toSemanticVersion("1.2."));
        assertNotEquals(left, new VersioningService.SemanticVersion("1.2.0", 1, 2, 0, false));
        assertNotEquals(left, new VersioningService.SemanticVersion("1.2", 1, 2, 0, true));
        assertEquals(service.toSemanticVersion("1.2").hashCode(), left.hashCode());
    }

    private Object sample(final Class<?> type) {
        if (type == String.class) {
            return "value";
        }
        if (type == int.class) {
            return 1;
        }
        if (type == boolean.class) {
            return true;
        }
        if (type == String[].class) {
            return new String[] {"a", null};
        }
        if (type == List.class) {
            return List.of("value");
        }
        if (type == Map.class) {
            return Map.of("key", "value");
        }
        if (type == Path.class) {
            return Path.of("value");
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type == LintError.class) {
            return new LintError(null, "value");
        }
        if (type == HttpRequest.class) {
            return HttpRequest.newBuilder(URI.create("https://example.test/")).build();
        }
        return null;
    }

    private void assertContract(final Object left, final Object right, final Object third) {
        assertEquals(left, left);
        assertEquals(left, right);
        assertEquals(right, left);
        assertEquals(right, third);
        assertEquals(left, third);
        assertEquals(left.hashCode(), right.hashCode());
        assertEquals(right.hashCode(), third.hashCode());
        assertNotEquals(left, null);
        assertNotEquals(left, new Object());
    }
}
