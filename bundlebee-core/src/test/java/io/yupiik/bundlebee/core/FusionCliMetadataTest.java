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

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.service.CompletionService;
import io.yupiik.bundlebee.core.service.ParameterExtractor;
import io.yupiik.bundlebee.extension.MetadataOnlyCommand;
import io.yupiik.fusion.cli.internal.BaseCliCommand;
import io.yupiik.fusion.cli.internal.CliCommand;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.Types.ParameterizedTypeImpl;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FusionCliMetadataTest {
    @Test
    void tokenFileAndCertificatesAreDiscoverableAsSharedOptions() {
        try (final var container = ConfiguringContainer.of().start();
                final var extractor = container.lookup(ParameterExtractor.class);
                final var completion = container.lookup(CompletionService.class)) {
            final var shared = extractor
                    .instance()
                    .sharedParameters()
                    .collect(toMap(CliCommand.Parameter::configName, parameter -> parameter));
            final var tokenFile = shared.get("bundlebee.kube.tokenFile");
            assertNotNull(tokenFile);
            assertEquals("--bundlebee-kube-tokenFile", tokenFile.cliName());
            assertEquals("none", extractor.instance().defaultValue(tokenFile));
            assertEquals(
                    "Optional service-account token file, refreshed by Fusion; none disables file authentication.",
                    tokenFile.description());
            final var certificates = shared.get("bundlebee.kube.certificates");
            assertNotNull(certificates);
            assertEquals("--bundlebee-kube-certificates", certificates.cliName());
            assertNull(extractor.instance().defaultValue(certificates));
            assertEquals("Optional CA certificate file or PEM certificates.", certificates.description());
            final var options = completion
                    .instance()
                    .getCommands()
                    .get("apply")
                    .get()
                    .map(ParameterExtractor.Parameter::getName)
                    .toList();
            assertTrue(options.contains("--" + tokenFile.configName()));
            assertTrue(options.contains("--" + certificates.configName()));
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    void descriptorHandlesLiveUntilTheExtractorIsDestroyed() {
        final var destructions = new AtomicInteger();
        final var bean =
                new ProvidedInstanceBean<CliCommand>(
                        DefaultScoped.class,
                        CliCommand.class,
                        () -> new BaseCliCommand<Configuration, Runnable>(
                                "lifetime-probe",
                                "Lifetime probe.",
                                configuration -> configuration,
                                (configuration, dependencies) -> () -> {},
                                List.of())) {
                    @Override
                    public void destroy(final RuntimeContainer container, final CliCommand instance) {
                        destructions.incrementAndGet();
                    }
                };
        try (final var container = ConfiguringContainer.of().register(bean).start();
                final var extractor = container.lookup(ParameterExtractor.class)) {
            final var definitions = extractor.instance().commands();
            assertTrue(definitions.stream().anyMatch(command -> "lifetime-probe".equals(command.name())));
            assertSame(definitions, extractor.instance().commands());
            assertEquals(0, destructions.get());
        }
        assertEquals(1, destructions.get());
    }

    @Test
    void extensionDiscoveryDoesNotConstructCommandsOrTheirDependencies() {
        try (final var container = ConfiguringContainer.of().start();
                final var extractor = container.lookup(ParameterExtractor.class)) {
            final var extension = extractor.instance().commands().stream()
                    .filter(command -> "metadata-only-extension".equals(command.name()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Extension metadata.", extension.description());
            final var option = extension.parameters().stream()
                    .filter(parameter -> "test.extension.value".equals(parameter.configName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("--test-extension-value", option.cliName());
            assertEquals("value", extractor.instance().defaultValue(option));
            assertEquals("An extension value.", option.description());
            assertEquals(0, MetadataOnlyCommand.constructions());
            assertEquals(0, MetadataOnlyCommand.UnavailableDependency.constructions());
        }
    }

    @Test
    void completionOptionDiscoveryIsOfflineAndRepeatable() {
        try (final var container = ConfiguringContainer.of().start();
                final var completion = container.lookup(CompletionService.class)) {
            final var commands = completion.instance().getCommands();
            final var extension = commands.get("metadata-only-extension");
            assertNotNull(extension);
            final var first = extension.get().toList();
            assertEquals(first, extension.get().toList());
            assertTrue(first.stream().anyMatch(parameter -> "--test-extension-value".equals(parameter.getName())));
            assertTrue(first.stream().anyMatch(parameter -> "--bundlebee.kube.kubeconfig".equals(parameter.getName())));
            assertEquals(0, MetadataOnlyCommand.constructions());
            assertEquals(0, MetadataOnlyCommand.UnavailableDependency.constructions());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void allPublicOptionMetadataMatchesTheOneXBaseline() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
                final var extractor = container.lookup(ParameterExtractor.class);
                final var mapper = container.lookup(JsonMapper.class);
                final var baseline = getClass().getResourceAsStream("cli-options-1x.json")) {
            assertNotNull(baseline);
            final List<BaselineOption> expected = mapper.instance()
                    .fromString(
                            new ParameterizedTypeImpl(List.class, BaselineOption.class),
                            new String(baseline.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(209, expected.size());
            // Legacy names remain runtime aliases, while metadata documents canonical keys.
            final var migrated = expected.stream()
                    .map(option -> "@global".equals(option.owner()) && "kubeconfig".equals(option.name())
                            ? new BaselineOption(
                                    option.owner(),
                                    "bundlebee.kube.kubeconfig",
                                    option.defaultValue(),
                                    option.description())
                            : "@global".equals(option.owner())
                                            && "bundlebee.maven.http.connectTimeout".equals(option.name())
                                    ? new BaselineOption(
                                            option.owner(),
                                            "bundlebee.maven.http.timeout",
                                            option.defaultValue(),
                                            "When fetching a dependency using HTTP, the request timeout in milliseconds.")
                                    : option)
                    .toList();
            final var metadata = extractor.instance().metadata();
            final var options = Stream.concat(
                            ((List<Map<String, Object>>) metadata.get("commands"))
                                    .stream()
                                            .flatMap(command -> ((List<Map<String, Object>>) command.get("options"))
                                                    .stream()
                                                            .map(option -> Map.entry(
                                                                    command.get("name") + ":" + option.get("name"),
                                                                    option))),
                            ((List<Map<String, Object>>) metadata.get("globalOptions"))
                                    .stream().map(option -> Map.entry("@global:" + option.get("name"), option)))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            // ConditionAwaiter's one internal setting belongs to generated configuration docs, not CLI help.
            final var visible = migrated.stream()
                    .filter(option -> options.containsKey(option.owner() + ":" + option.name()))
                    .toList();
            assertEquals(208, visible.size());
            for (final var option : visible) {
                final var actual = options.get(option.owner() + ":" + option.name());
                // The original CheckUpdate source is a Maven-filtered Java template.
                final var expectedDefault = option.defaultValue() == null
                        ? null
                        : option.defaultValue()
                                .replace("${project.groupId}", "io.yupiik")
                                .replace("${project.artifactId}", "bundlebee-core");
                assertEquals(expectedDefault, actual.get("defaultValue"), option.name());
                assertEquals(option.description(), actual.get("description"), option.name());
            }
        }
    }

    @Test
    void asynchronousExecutionPropagatesFailuresAndPreservesInterruption() {
        final var failure = new IllegalArgumentException("failure");
        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () -> executable(CompletableFuture.failedFuture(failure))
                                .run()));
        final var error = new AssertionError("error");
        assertSame(
                error,
                assertThrows(
                        AssertionError.class,
                        () -> executable(CompletableFuture.failedFuture(error)).run()));
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> executable(new CompletableFuture<>()).run());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @JsonModel
    public record BaselineOption(String owner, String name, String defaultValue, String description) {}

    private Executable executable(final CompletionStage<?> stage) {
        return new Executable() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public CompletionStage<?> execute() {
                return stage;
            }
        };
    }
}
