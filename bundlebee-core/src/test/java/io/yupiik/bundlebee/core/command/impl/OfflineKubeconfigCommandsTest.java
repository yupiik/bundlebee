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

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OfflineKubeconfigCommandsTest {
    @RegisterExtension
    final BundleBeeExtension extension = new BundleBeeExtension();

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void processDoesNotReadMalformedKubeconfig(
            final boolean verbose, final boolean dryRun, final CommandExecutor executor) throws IOException {
        final var kubeconfig = malformedKubeconfig();
        final var output = directory.resolve("processed");
        final var logs = executor.wrap(
                null,
                INFO,
                () -> new BundleBee()
                        .launch(
                                "process",
                                "--alveolus",
                                "ApplyCommandTest.apply",
                                "--kubeconfig",
                                kubeconfig.toString(),
                                "--output",
                                output.toString(),
                                "--injectTimestamp",
                                "false",
                                "--bundlebee-kube-verbose",
                                Boolean.toString(verbose),
                                "--bundlebee-kube-dryRun",
                                Boolean.toString(dryRun)));
        final var descriptor = Files.readString(output.resolve("ApplyCommandTest.d1"));
        assertTrue(descriptor.contains("\"kind\": \"Service\""), descriptor);
        assertTrue(descriptor.contains("\"name\": \"s\""), descriptor);
        assertTrue(logs.contains("Dumping '"), logs);
        if (verbose || dryRun) {
            assertTrue(logs.contains("Loaded descriptor(s)"), logs);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void lintDoesNotReadMalformedKubeconfig(final boolean verbose, final boolean dryRun, final CommandExecutor executor)
            throws IOException {
        final var kubeconfig = malformedKubeconfig();
        final var logs = executor.wrap(
                null,
                INFO,
                () -> new BundleBee()
                        .launch(
                                "lint",
                                "--alveolus",
                                "ApplyCommandTest.withdep",
                                "--kubeconfig",
                                kubeconfig.toString(),
                                "--bundlebee-kube-verbose",
                                Boolean.toString(verbose),
                                "--bundlebee-kube-dryRun",
                                Boolean.toString(dryRun)));
        assertTrue(logs.contains("No linting error."), logs);
        if (verbose || dryRun) {
            assertTrue(logs.contains("Loaded descriptor(s)"), logs);
        }
    }

    @Test
    void networkOperationStillRejectsMalformedKubeconfig() throws IOException {
        final var kubeconfig = malformedKubeconfig();
        final var failure = assertThrows(
                RuntimeException.class,
                () -> new BundleBee()
                        .launch(
                                "apply",
                                "--alveolus",
                                "ApplyCommandTest.apply",
                                "--kubeconfig",
                                kubeconfig.toString()));
        var cause = (Throwable) failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("expected"), cause.toString());
    }

    private Path malformedKubeconfig() throws IOException {
        return Files.writeString(directory.resolve("malformed-kubeconfig.yaml"), "clusters: [unterminated\n");
    }
}
