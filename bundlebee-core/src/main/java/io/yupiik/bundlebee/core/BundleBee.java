/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.toMap;

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
                    .putAll(toProperties(cmd, args));

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
                Thread.currentThread().interrupt();
            } catch (final ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    private Map<String, String> toProperties(final String cmd, final String... args) {
        if ("completion".equals(cmd)) { // using complete -C will send an invalid command so fwd them manully for this one
            final var arguments = List.of(args);
            final var out = new HashMap<String, String>();
            final int pt = getArgIndex(arguments, "comp.point");
            if (pt >= 0 && args.length > pt + 1) {
                out.put("comp.point", arguments.get(pt + 1));
            }
            final int line = getArgIndex(arguments, "comp.line");
            if (line >= 0 && args.length > line + 1) {
                out.put("comp.line", arguments.get(line + 1));
            }
            final int useLogger = getArgIndex(arguments, "useLogger");
            if (useLogger >= 0 && args.length > useLogger + 1) {
                out.put("bundlebee.completion.useLogger", arguments.get(useLogger + 1));
            }
            return out;
        }
        if (args.length > 0 && (args.length % 2) == 0) {
            throw new IllegalArgumentException("" +
                    "Invalid argument parity, syntax is: <command> --<arg1> <value> --<arg2> <value> ..., got:\n" +
                    String.join(" ", args));
        }
        final var directMapping = IntStream.rangeClosed(0, (args.length / 2) - 1).boxed().collect(toMap(
                idx -> {
                    final var value = args[1 + idx * 2];
                    return value.startsWith("--") ? value.substring("--".length()) : value;
                },
                idx -> args[(1 + idx) * 2]));

        // the configuration convention for commands is "bundlebee.<command name>.<property>" so we enable
        // to only use "--property" on the CLI by prefixing the properties not starting with "bundlebee"
        return directMapping.entrySet().stream()
                .flatMap(it -> it.getKey().startsWith("bundlebee.") ?
                        Stream.of(it) : Stream.of(it, new AbstractMap.SimpleImmutableEntry<>("bundlebee." + cmd + "." + it.getKey(), it.getValue())))
                .flatMap(it -> !it.getKey().contains("-") ?
                        Stream.of(it) : Stream.of(it, new AbstractMap.SimpleImmutableEntry<>(it.getKey().replace('-', '.'), it.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    private int getArgIndex(List<String> arguments, final String name) {
        final var pt = arguments.indexOf("--" + name);
        if (pt < 0) {
            return arguments.indexOf("--" + name.replace('.', '-'));
        }
        return pt;
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
