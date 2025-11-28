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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenConfigSourceTest {
    @BeforeAll
    static void before() {
        final var session = new MavenSession(
                null, null, null, null, null,
                List.of("g"), null, new Properties(), new Date(0));
        session.getUserProperties().setProperty("prop.name", "ok-for-test");
        MavenConfigSource.expressionEvaluator = new PluginParameterExpressionEvaluator(
                session,
                new MojoExecution(new MojoDescriptor()));
        MavenConfigSource.config = new MavenConfigSource.Config(true);
    }

    @AfterAll
    static void after() {
        MavenConfigSource.expressionEvaluator = null;
        MavenConfigSource.config = null;
    }

    @ParameterizedTest
    @CsvSource({
            "prop.name,ok-for-test",
            "${prop.name},ok-for-test",
            "prop.name.missing,null",
    })
    void filter(final String key, final String value) {
        assertEquals(value, ConfigProvider.getConfig().getOptionalValue(key, String.class).orElse("null"));
    }
}
