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
package io.yupiik.bundlebee.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.cli.internal.BaseCliCommand;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParameterExtractorTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void metadataOutlivesCallerOwnedLookupAndContainerWithoutConstructingCommands() {
        final var destructions = new AtomicInteger();
        final var bean =
                new ProvidedInstanceBean<CliCommand>(
                        DefaultScoped.class,
                        CliCommand.class,
                        () -> new BaseCliCommand<Configuration, Runnable>(
                                "detached-probe",
                                "Detached metadata.",
                                configuration -> configuration,
                                (configuration, dependencies) -> {
                                    throw new AssertionError("Metadata must not construct commands");
                                },
                                List.of())) {
                    @Override
                    public void destroy(final RuntimeContainer container, final CliCommand instance) {
                        destructions.incrementAndGet();
                    }
                };
        final Map<String, Object> metadata;
        try (final var container = ConfiguringContainer.of().register(bean).start()) {
            try (final var extractor = container.lookup(ParameterExtractor.class)) {
                metadata = extractor.instance().metadata();
                assertEquals(0, destructions.get());
                assertEquals(metadata, extractor.instance().metadata());
            }
            // Closing the caller's lookup must not close its application-owned container.
            try (final var extractor = container.lookup(ParameterExtractor.class)) {
                assertEquals(metadata, extractor.instance().metadata());
                assertEquals(0, destructions.get());
            }
        }
        assertEquals(1, destructions.get());
        final var commands = (List<Map<String, Object>>) metadata.get("commands");
        final var probe = commands.stream()
                .filter(command -> "detached-probe".equals(command.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("Detached metadata.", probe.get("description"));
        assertEquals(Boolean.FALSE, probe.get("hidden"));
        assertEquals(List.of(), probe.get("options"));
        final var globals = (List<Map<String, Object>>) metadata.get("globalOptions");
        final var tokenFile = globals.stream()
                .filter(option -> "bundlebee.kube.tokenFile".equals(option.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("none", tokenFile.get("defaultValue"));
        assertEquals("--bundlebee-kube-tokenFile", tokenFile.get("cliName"));
        final var certificates = globals.stream()
                .filter(option -> "bundlebee.kube.certificates".equals(option.get("name")))
                .findFirst()
                .orElseThrow();
        assertNull(certificates.get("defaultValue"));
        assertTrue(certificates.containsKey("defaultValue"));
    }

    @Test
    void parameterEqualityIsNullSafeAndIncludesEveryProperty() {
        final var parameter = new ParameterExtractor.Parameter("name", null, "description");
        final var equal = new ParameterExtractor.Parameter("name", null, "description");
        assertEquals(parameter, parameter);
        assertEquals(parameter, equal);
        assertEquals(equal, parameter);
        assertEquals(parameter.hashCode(), equal.hashCode());
        assertNotEquals(parameter, null);
        assertNotEquals(parameter, "name");
        assertNotEquals(parameter, new ParameterExtractor.Parameter("other", null, "description"));
        assertNotEquals(parameter, new ParameterExtractor.Parameter("name", "value", "description"));
        assertNotEquals(parameter, new ParameterExtractor.Parameter("name", null, "other"));
        final var empty = new ParameterExtractor.Parameter(null, null, null);
        final var anotherEmpty = new ParameterExtractor.Parameter(null, null, null);
        assertEquals(empty, anotherEmpty);
        assertEquals(empty.hashCode(), anotherEmpty.hashCode());
    }

    @Test
    void parameterEqualityRetainsGetterAndCanEqualExtensionPoints() {
        final var parameter = new ParameterExtractor.Parameter("name", null, "description");
        final var overridden = new ParameterExtractor.Parameter("ignored", "ignored", "ignored") {
            @Override
            public String getName() {
                return "name";
            }

            @Override
            public String getDefaultValue() {
                return null;
            }

            @Override
            public String getDescription() {
                return "description";
            }
        };
        assertEquals(parameter, overridden);
        assertEquals(overridden, parameter);
        assertEquals(parameter.hashCode(), overridden.hashCode());
        final var restricted = new ParameterExtractor.Parameter("name", null, "description") {
            @Override
            protected boolean canEqual(final Object other) {
                return other == this;
            }
        };
        assertFalse(parameter.equals(restricted));
        assertTrue(restricted.equals(restricted));
    }
}
