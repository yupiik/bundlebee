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

import static java.util.logging.Level.INFO;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class BundleBeeTest {

    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void loggingDefaultsWithoutOverridingEmbeddingChoices() {
        final var defaults = Map.of(
                "java.util.logging.manager", "io.yupiik.logging.jul.YupiikLogManager",
                ".level", "WARNING",
                "io.yupiik.bundlebee.level", "INFO");
        final var explicit = Map.of(
                "java.util.logging.manager", "java.util.logging.LogManager",
                ".level", "SEVERE",
                "io.yupiik.bundlebee.level", "FINE");
        final var previous = defaults.keySet().stream()
                .collect(toMap(key -> key, key -> Optional.ofNullable(System.getProperty(key))));
        try {
            defaults.keySet().forEach(System::clearProperty);
            try (final var container = ConfiguringContainer.of().start()) {
                defaults.forEach((key, value) -> Assertions.assertEquals(value, System.getProperty(key), key));
            }
            explicit.forEach(System::setProperty);
            try (final var container = ConfiguringContainer.of().start()) {
                explicit.forEach((key, value) -> Assertions.assertEquals(value, System.getProperty(key), key));
            }
        } finally {
            previous.forEach((key, value) -> {
                if (value.isEmpty()) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value.orElseThrow());
                }
            });
        }
    }

    @Test
    void loggingDefaultsApplyBeforeJulInitialization(@TempDir final Path directory) throws Exception {
        for (final var explicit : List.of(false, true)) {
            final var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Duser.home=" + directory);
            if (explicit) {
                command.addAll(List.of(
                        "-Djava.util.logging.manager=java.util.logging.LogManager",
                        "-D.level=SEVERE",
                        "-Dio.yupiik.bundlebee.level=FINE"));
            }
            command.addAll(List.of(
                    "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    BundleBeeTest.class.getName(),
                    Boolean.toString(explicit)));
            final var log = directory.resolve(explicit + ".log");
            final var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            try {
                assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Logging startup timed out");
                assertEquals(0, process.exitValue(), () -> {
                    try {
                        return Files.readString(log);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public static void main(final String[] args) {
        final var explicit = Boolean.parseBoolean(args[0]);
        BundleBee.main("help");
        assertEquals(
                explicit ? "java.util.logging.LogManager" : "io.yupiik.logging.jul.YupiikLogManager",
                LogManager.getLogManager().getClass().getName());
        assertEquals(explicit ? "SEVERE" : "WARNING", System.getProperty(".level"));
        assertEquals(explicit ? "FINE" : "INFO", System.getProperty("io.yupiik.bundlebee.level"));
    }

    @Test
    void help(final CommandExecutor executor) {
        final var noArg = executor.wrap(null, INFO, () -> new BundleBee().launch());
        final var explicitHelp = executor.wrap(null, INFO, () -> new BundleBee().launch("help"));
        final var missingCommand = executor.wrap(null, INFO, () -> new BundleBee().launch("missing"));
        Stream.of(noArg, explicitHelp, missingCommand)
                .forEach(content ->
                        assertTrue(content.contains("  [] help: print help for all available commands.\n"), content));
        // missing command has an additional error message
        assertTrue(missingCommand.contains("No command found for args: [missing]\n"), missingCommand);
    }
}
