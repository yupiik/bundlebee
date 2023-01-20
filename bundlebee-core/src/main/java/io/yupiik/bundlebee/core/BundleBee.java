/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.cli.Args;
import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.command.impl.HelpCommand;
import io.yupiik.bundlebee.core.configuration.ConfigurableConfigSource;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.inject.se.SeContainerInitializer;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ExecutionException;
import java.util.stream.StreamSupport;

import static java.util.Locale.ROOT;

@Log
public final class BundleBee {
    public static void main(final String... args) {
        initLogging();
        setupUserConfig();
        setupRandom();
        final var newArgs = setupArgConfig(args);
        new BundleBee().launch(newArgs);
    }

    public void launch(final String... args) {
        final var initializer = SeContainerInitializer.newInstance();
        final var cmd = args.length == 0 ? "help" : args[0];
        try (final var container = initializer.initialize()) {
            // enrich the config with cli args
            StreamSupport.stream(container.select(Config.class).get().getConfigSources().spliterator(), false)
                    .filter(ConfigurableConfigSource.class::isInstance)
                    .map(ConfigurableConfigSource.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .getProperties()
                    .putAll(Args.toProperties(cmd, args));

            boolean foundCommand = false;
            try { // we vetoed all other executable except the one we want
                final var executables = container.select(Executable.class);
                final var command = StreamSupport.stream(
                                // we can just do executables.stream() but maven 3.6 integration would be broken
                                Spliterators.spliteratorUnknownSize(executables.iterator(), Spliterator.IMMUTABLE), false)
                        .filter(it -> cmd.equals(it.name()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No command " + cmd));
                foundCommand = true;
                command.execute().toCompletableFuture().get();
            } catch (final RuntimeException re) {
                if (foundCommand) {
                    throw re;
                }
                log.warning("No command found for args: " + List.of(args));
                container.select(HelpCommand.class).get().execute();
            } catch (final InterruptedException e) {
                log.warning("Execution interrupted");
                Thread.currentThread().interrupt();
            } catch (final ExecutionException e) {
                final var cause = e.getCause();
                if (RuntimeException.class.isInstance(cause)) {
                    throw RuntimeException.class.cast(cause);
                }
                throw new IllegalStateException(cause);
            }
        }
    }

    private static void setupUserConfig() {
        final var userConfig = Paths.get(System.getProperty("user.home", "/home/user")).resolve(".bundlebeerc");
        if (Files.exists(userConfig)) {
            injectConfiguration(userConfig);
        }
    }

    private static void setupRandom() {
        if (System.getProperty("os.name", "blah").toLowerCase(ROOT).contains("linux")) {
            System.setProperty("securerandom.source", System.getProperty("securerandom.source", "file:/dev/./urandom"));
        }
    }

    private static String[] setupArgConfig(final String... args) {
        if (args.length < 2) {
            return args;
        }
        final var arguments = new ArrayList<>(List.of(args));
        final var i = arguments.indexOf("--config-file");
        if (i >= 0) {
            injectConfiguration(Paths.get(args[i + 1]));
            arguments.remove(i);
            arguments.remove(i); // i+1 but we just removed one
            return arguments.toArray(new String[0]);
        }
        return args;
    }

    private static void injectConfiguration(final Path location) {
        final var props = new Properties();
        try (final Reader reader = Files.newBufferedReader(location)) {
            props.load(reader);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        props.stringPropertyNames().forEach(key -> System.setProperty(key, props.getProperty(key)));
    }

    private static void initLogging() {
        final var logMgrClass = System.getProperty("java.util.logging.manager", "io.yupiik.logging.jul.YupiikLogManager");
        System.setProperty("java.util.logging.manager", logMgrClass);
        if ("io.yupiik.logging.jul.YupiikLogManager".equals(logMgrClass)) {
            System.setProperty("org.apache.webbeans.corespi.scanner.AbstractMetaDataDiscovery.level",
                    System.getProperty("org.apache.webbeans.corespi.scanner.AbstractMetaDataDiscovery.level", "SEVERE"));
            System.setProperty(".level", System.getProperty(".level", "WARNING"));
            System.setProperty("io.yupiik.bundlebee.level", System.getProperty("io.yupiik.bundlebee.level", "INFO"));
        }
    }
}
