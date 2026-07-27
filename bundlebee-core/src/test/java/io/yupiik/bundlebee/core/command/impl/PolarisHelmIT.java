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

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that installs the Fairwinds Polaris Helm chart via bundlebee
 * on a real minikube cluster, verifies it is running, then cleans up namespaces.
 * <p>
 * Requirements: minikube and kubectl must be installed on the machine.
 * To run: {@code mvn verify -pl bundlebee-core -Dit.test=PolarisHelmIT}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolarisHelmIT {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    private static final String POLARIS_NAMESPACE = "polaris";
    private static final String MANIFEST_PATH = "src/test/resources/bundlebee/polaris-manifest.json";
    private static final int POD_READY_TIMEOUT_SECONDS = 180;

    private static final List<String> namespacesToDelete = new ArrayList<>();

    @BeforeAll
    static void ensureMinikubeRunning() {
        Assumptions.assumeTrue(isCommandAvailable("minikube"), "minikube is not installed, skipping integration test");
        Assumptions.assumeTrue(isCommandAvailable("kubectl"), "kubectl is not installed, skipping integration test");

        final var status = execCommand("minikube", "status");
        if (status != 0) {
            final var start = execCommand("minikube", "start");
            assertEquals(0, start, "Failed to start minikube");
        }
    }

    @AfterAll
    static void cleanupNamespaces() {
        // delete all created namespaces (all but kube-system works)
        final var exclude = Stream.of("kube-system", "default", "kube-public", "kube-node-lease").collect(toList());
        for (final var ns : namespacesToDelete) {
            if (exclude.contains(ns)) {
                continue;
            }
            System.getLogger(PolarisHelmIT.class.getName()).log(System.Logger.Level.INFO,
                    "Deleting namespace '" + ns + "'");
            execCommand("kubectl", "delete", "namespace", ns, "--ignore-not-found", "--wait=false");
        }
        // explicit polaris cleanup in case namespace listing missed it
        execCommand("kubectl", "delete", "namespace", POLARIS_NAMESPACE, "--ignore-not-found", "--wait=false");
    }

    @Test
    @Order(1)
    void applyPolarisHelmChart(final CommandExecutor executor) {
        // create the target namespace so resources referencing it can be applied
        final var nsResult = execCommand("kubectl", "create", "namespace", POLARIS_NAMESPACE);
        assertTrue(nsResult == 0 || execCommand("kubectl", "get", "namespace", POLARIS_NAMESPACE) == 0,
                "Failed to create namespace '" + POLARIS_NAMESPACE + "'");

        final var manifest = Path.of(MANIFEST_PATH).toAbsolutePath().toString();
        final var logs = executor.wrap(null, Level.INFO, () -> new BundleBee()
                .launch("apply", "--alveolus", "polaris-test", "--manifest", manifest));

        assertTrue(logs.contains("Deploying"), "Expected 'Deploying' in logs:\n" + logs);
        assertTrue(logs.contains("polaris"), "Expected 'polaris' in logs:\n" + logs);
    }

    @Test
    @Order(2)
    void polarisIsRunning() {
        // collect namespaces created by the chart (polaris namespace)
        namespacesToDelete.add(POLARIS_NAMESPACE);

        final var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(POD_READY_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            final var output = execCommandOutput("kubectl", "get", "pods",
                    "-n", POLARIS_NAMESPACE,
                    "-o", "jsonpath={range .items[*]}{.status.phase}{' '}{end}");
            if (output != null && !output.isBlank()
                    && Stream.of(output.trim().split("\\s+")).allMatch("Running"::equals)
                    && !output.trim().isEmpty()) {
                final var count = output.trim().split("\\s+").length;
                assertTrue(count > 0, "At least one polaris pod should be running");
                return;
            }
            try {
                Thread.sleep(5000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        // dump pod status for diagnostics
        final var debug = execCommandOutput("kubectl", "get", "pods", "-n", POLARIS_NAMESPACE, "-o", "wide");
        throw new AssertionError("Polaris pods did not become Running within " + POD_READY_TIMEOUT_SECONDS
                + "s. Pod status:\n" + debug);
    }

    @Test
    @Order(3)
    void deletePolarisHelmChart(final CommandExecutor executor) {
        final var manifest = Path.of(MANIFEST_PATH).toAbsolutePath().toString();
        final var logs = executor.wrap(null, Level.INFO, () -> new BundleBee()
                .launch("delete", "--alveolus", "polaris-test", "--manifest", manifest));

        assertTrue(logs.contains("Deleting"), "Expected 'Deleting' in logs:\n" + logs);
    }

    private static boolean isCommandAvailable(final String cmd) {
        try {
            final var pb = new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            final var p = pb.start();
            final var exited = p.waitFor(5, TimeUnit.SECONDS);
            return exited && p.exitValue() == 0;
        } catch (final Exception e) {
            return false;
        }
    }

    private static int execCommand(final String... command) {
        try {
            final var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            final var p = pb.start();
            try (final var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                reader.lines().forEach(l -> System.getLogger(PolarisHelmIT.class.getName())
                        .log(System.Logger.Level.INFO, l));
            }
            final var finished = p.waitFor(5, TimeUnit.MINUTES);
            return finished ? p.exitValue() : -1;
        } catch (final Exception e) {
            System.getLogger(PolarisHelmIT.class.getName())
                    .log(System.Logger.Level.ERROR, "Failed to execute: " + String.join(" ", command), e);
            return -1;
        }
    }

    private static String execCommandOutput(final String... command) {
        try {
            final var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            final var p = pb.start();
            final var sb = new StringBuilder();
            try (final var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                reader.lines().forEach(l -> sb.append(l).append('\n'));
            }
            final var finished = p.waitFor(30, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return sb.toString();
            }
            return null;
        } catch (final Exception e) {
            return null;
        }
    }
}
