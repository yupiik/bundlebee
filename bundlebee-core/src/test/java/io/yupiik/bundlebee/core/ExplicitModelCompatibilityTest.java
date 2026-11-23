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
package io.yupiik.bundlebee.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.event.OnKubeRequest;
import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.bundlebee.core.lang.Tuple2;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import io.yupiik.fusion.httpclient.core.DelegatingHttpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ExplicitModelCompatibilityTest {
    @Test
    void recordValueMethodsAndNullSemantics() {
        final var tuple = new Tuple2<String, String>("first", null);
        assertTrue(tuple.getClass().isRecord());
        assertEquals("first", tuple.first());
        assertNull(tuple.second());
        assertEquals(new Tuple2<>("first", null), tuple);
        assertEquals(new Tuple2<>("first", null).hashCode(), tuple.hashCode());
        assertEquals("Tuple2[first=first, second=null]", tuple.toString());
        assertNotEquals(new Tuple2<>("first", "second"), tuple);
        assertNotEquals(new Tuple2<>(null, "first"), tuple);
        assertFalse(tuple.equals(null));
        assertFalse(tuple.equals("first"));
        assertEquals(new Tuple2<>(null, null), new Tuple2<>(null, null));
    }

    @Test
    void recordComponentsRetainArrayIdentitySemantics() {
        final var values = new String[] {"first"};
        final var tuple = new Tuple2<>(values, "second");
        assertSame(values, tuple.first());
        assertEquals(tuple, new Tuple2<>(values, "second"));
        assertEquals(tuple.hashCode(), new Tuple2<>(values, "second").hashCode());
        assertNotEquals(tuple, new Tuple2<>(new String[] {"first"}, "second"));
    }

    @Test
    void eventValuesPreserveNullGetterAndSubclassContracts() {
        final var empty = new OnPlaceholder(null, null, null, null);
        assertEquals(empty, new OnPlaceholder(null, null, null, null));
        assertEquals(empty.hashCode(), new OnPlaceholder(null, null, null, null).hashCode());
        assertNull(empty.getId());
        assertNotEquals(empty, new OnPlaceholder(null, null, null, "run"));
        final var getterBased = new OnPlaceholder("stored", null, null, null) {
            @Override
            public String getName() {
                return "visible";
            }
        };
        final var visible = new OnPlaceholder("visible", null, null, null);
        assertEquals(visible, getterBased);
        assertEquals(getterBased, visible);
        assertEquals(visible.hashCode(), getterBased.hashCode());
        final var restricted = new OnPlaceholder("visible", null, null, null) {
            @Override
            protected boolean canEqual(final Object other) {
                return other == this;
            }
        };
        assertNotEquals(visible, restricted);
        final var placeholders = new HashMap<String, String>();
        final var prepare = new OnPrepareDescriptor(null, "app", "resource", "content", placeholders);
        final var equal = new OnPrepareDescriptor(null, "app", "resource", "content", Map.of());
        assertEquals(equal, prepare);
        assertEquals(equal.hashCode(), prepare.hashCode());
        assertSame(placeholders, prepare.getPlaceholders());
        placeholders.put("key", "value");
        assertNotEquals(equal, prepare);
        assertNotEquals(prepare, new OnPrepareDescriptor("run", "app", "resource", "content", placeholders));
        assertFalse(prepare.equals(null));
    }

    @Test
    void serviceValuesPreserveGetterAndNullEquality() {
        final var loaded = new AlveolusHandler.LoadedDescriptor(null, null, null, null, null);
        final var loadedEqual = new AlveolusHandler.LoadedDescriptor(null, null, null, null, null);
        assertEquals(loadedEqual, loaded);
        assertEquals(loadedEqual.hashCode(), loaded.hashCode());
        assertNotEquals(loaded, new AlveolusHandler.LoadedDescriptor(null, "content", null, null, null));
        final var archive = new ArchiveReader.Archive(null, null, null);
        final var getterBased = new ArchiveReader.Archive(Path.of("stored"), null, null) {
            @Override
            public Path getLocation() {
                return null;
            }
        };
        assertEquals(archive, getterBased);
        assertEquals(archive.hashCode(), getterBased.hashCode());
        final var restricted = new ArchiveReader.Archive(null, null, null) {
            @Override
            protected boolean canEqual(final Object other) {
                return other == this;
            }
        };
        assertNotEquals(archive, restricted);
        final var reader = new ArchiveReader(null, null, null);
        final var cache = reader.newCache();
        final var cacheEqual = reader.newCache();
        assertEquals(cacheEqual, cache);
        assertEquals(cacheEqual.hashCode(), cache.hashCode());
        cache.getCache().put("archive", CompletableFuture.completedFuture(archive));
        assertNotEquals(cacheEqual, cache);
        final var context = new AlveolusHandler.AlveolusContext(null, null, null, null, null, null, null);
        final var contextEqual = new AlveolusHandler.AlveolusContext(null, null, null, null, null, null, null);
        assertEquals(contextEqual, context);
        assertEquals(contextEqual.hashCode(), context.hashCode());
        assertNotEquals(context, new AlveolusHandler.AlveolusContext(null, null, null, null, null, null, "run"));
        final var pair = new AlveolusHandler.ManifestAndAlveolus(null, null);
        assertEquals(pair, new AlveolusHandler.ManifestAndAlveolus(null, null));
        assertEquals(pair.hashCode(), new AlveolusHandler.ManifestAndAlveolus(null, null).hashCode());
    }

    @Test
    void interceptionEventsRemainMutable() {
        final var request =
                HttpRequest.newBuilder(URI.create("http://localhost/original")).build();
        final var replacement = HttpRequest.newBuilder(URI.create("http://localhost/replacement"))
                .build();
        final var event = new OnKubeRequest(request);
        final var equal = new OnKubeRequest(request);
        assertEquals(equal, event);
        event.setUserRequest(replacement);
        event.setUserResponse(CompletableFuture.completedFuture(null));
        assertSame(request, event.getRequest());
        assertSame(replacement, event.getUserRequest());
        assertNotEquals(equal, event);
        equal.setUserRequest(replacement);
        equal.setUserResponse(event.getUserResponse());
        assertEquals(equal, event);
        assertEquals(equal.hashCode(), event.hashCode());
    }

    @Test
    void explicitHttpDelegationRetainsClientSettings() throws Exception {
        final var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        try (final var delegate = new DelegatingHttpClient(client)) {
            assertEquals(client.connectTimeout(), delegate.connectTimeout());
            assertEquals(client.followRedirects(), delegate.followRedirects());
            assertEquals(client.version(), delegate.version());
            assertSame(client.sslContext(), delegate.sslContext());
            assertEquals(client.executor(), delegate.executor());
            assertEquals(client.authenticator(), delegate.authenticator());
            assertEquals(client.cookieHandler(), delegate.cookieHandler());
            assertEquals(client.proxy(), delegate.proxy());
        }
    }
}
