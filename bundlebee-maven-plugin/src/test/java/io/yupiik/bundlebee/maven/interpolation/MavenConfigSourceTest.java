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
package io.yupiik.bundlebee.maven.interpolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MavenConfigSourceTest {
    @ParameterizedTest
    @CsvSource({
        "prop.name,ok-for-test",
        "${prop.name},ok-for-test",
        "prop.name.missing,null",
        "${prop.name.missing},null",
    })
    void filter(final String key, final String value) {
        final var actual = source("ok-for-test", true).get(key);
        assertEquals(value, actual == null ? "null" : actual);
    }

    @Test
    void forcedFilteringCanBeDisabled() {
        final var source = source("one", false);
        assertNull(source.get("prop.name"));
        assertEquals("one", source.get("${prop.name}"));
        assertNull(source.get("missing"));
    }

    @Test
    void invocationIsolation() {
        final var first = source("first", true);
        final var second = source("second", true);
        final var one = CompletableFuture.supplyAsync(() -> first.get("prop.name"));
        final var two = CompletableFuture.supplyAsync(() -> second.get("prop.name"));
        assertEquals("first", one.join());
        assertEquals("second", two.join());
        assertEquals("first", first.get("prop.name"));
    }

    private MavenConfigSource source(final String value, final boolean force) {
        final var session =
                new MavenSession(null, null, null, null, null, List.of("g"), null, new Properties(), new Date(0));
        session.getUserProperties().setProperty("prop.name", value);
        return new MavenConfigSource(
                new PluginParameterExpressionEvaluator(session, new MojoExecution(new MojoDescriptor())), force);
    }
}
