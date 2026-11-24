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
package io.yupiik.bundlebee.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.service.MavenConfiguration;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacyConfigurationAliasesTest {
    @Test
    void legacyKeyPopulatesTypedConfigurationInAnOrdinaryContainer() {
        try (final var container = ConfiguringContainer.of()
                        .register(source(100, Map.of("bundlebee.maven.http.connectTimeout", "1234")::get))
                        .start();
                final var maven = container.lookup(MavenConfiguration.class)) {
            assertEquals(1234, maven.instance().timeout());
        }
    }

    @Test
    void canonicalKeyWinsEvenWhenLegacyKeyComesFromAHigherPrioritySource() {
        try (final var container = ConfiguringContainer.of()
                        .register(source(100, Map.of("bundlebee.maven.http.connectTimeout", "1234")::get))
                        .register(source(200, Map.of("bundlebee.maven.http.timeout", "5678")::get))
                        .start();
                final var maven = container.lookup(MavenConfiguration.class)) {
            assertEquals(5678, maven.instance().timeout());
        }
    }

    @Test
    void legacyCliNameResolvesAndUnrelatedOrAbsentKeysDoNotRecurse() {
        try (final var container = ConfiguringContainer.of()
                        .register(source(
                                100, new ArgsConfigSource(List.of("--bundlebee-maven-http-connectTimeout=4321"))))
                        .start();
                final var configuration = container.lookup(Configuration.class)) {
            assertEquals(
                    "4321",
                    configuration.instance().get("bundlebee.maven.http.timeout").orElseThrow());
            assertTrue(configuration.instance().get("bundlebee.test.absent").isEmpty());
        }
        try (final var container = ConfiguringContainer.of().start();
                final var configuration = container.lookup(Configuration.class)) {
            assertTrue(
                    configuration.instance().get("bundlebee.maven.http.timeout").isEmpty());
        }
    }

    private ProvidedInstanceBean<ConfigurationSource> source(final int priority, final ConfigurationSource source) {
        return new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> source) {
            @Override
            public int priority() {
                return priority;
            }
        };
    }
}
