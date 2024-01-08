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
package io.yupiik.bundlebee.maven.mojo;

import io.yupiik.bundlebee.core.lang.Tuple2;
import io.yupiik.bundlebee.maven.interpolation.MavenConfigSource;
import lombok.RequiredArgsConstructor;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * Enables to have some global extension points - to change the classloader for ex.
 */
public abstract class BaseMojo extends AbstractMojo {
    /**
     * If `true`, bundlebee lookup in maven context will be one level only which means `foo`
     * will be looked up as such in maven and not ``${foo}` which will work.
     */
    @Parameter(property = "bundlebee.skipMavenForcedFilteringForPlaceholders", defaultValue = "false")
    private boolean skipMavenForcedFilteringForPlaceholders;

    /**
     * Skip execution.
     */
    @Parameter(property = "bundlebee.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Should JUL logging redirected to Maven logging (enable it only if you set up maven to use JUL).
     */
    @Parameter(property = "bundlebee.useDefaultLogging", defaultValue = "false")
    private boolean useDefaultLogging;

    /**
     * Skipped packaging types.
     */
    @Parameter(property = "bundlebee.skipPackaging", defaultValue = "pom")
    private List<String> skipPackaging;

    /**
     * Current module packaging.
     */
    @Parameter(defaultValue = "${project.packaging}")
    private String packaging;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    private MojoExecution mojoExecution;

    protected abstract void doExecute();

    @Override
    public void execute() {
        final var reset = Stream.of(
                        setupLogger("org.apache.webbeans", Level.WARNING),
                        setupLogger("io.yupiik.bundlebee", Level.INFO))
                .collect(toList());
        if (skip || (skipPackaging != null && skipPackaging.contains(packaging))) {
            getLog().info(getClass().getName() + " execution skipped");
            return;
        }

        if (session == null) {
            doExecute();
            return;
        }
        synchronized (session) { // todo: better locking
            final var old = new Tuple2<>(MavenConfigSource.expressionEvaluator, MavenConfigSource.config);
            MavenConfigSource.expressionEvaluator = new PluginParameterExpressionEvaluator(session, mojoExecution);
            MavenConfigSource.config = new MavenConfigSource.Config(!skipMavenForcedFilteringForPlaceholders);
            try {
                doExecute();
            } finally {
                reset.forEach(Runnable::run);
                MavenConfigSource.expressionEvaluator = old.getFirst();
                MavenConfigSource.config = old.getSecond();
            }
        }
    }

    protected Stream<String> toArgs(final Map<String, String> placeholders) {
        return placeholders == null ?
                Stream.empty() :
                placeholders.entrySet().stream()
                        .flatMap(this::loadPlaceholders)
                        .flatMap(it -> Stream.of("--" + it.getKey(), it.getValue()));
    }

    private Stream<Map.Entry<String, String>> loadPlaceholders(final Map.Entry<String, String> entry) {
        return !entry.getKey().startsWith("bundlebee-placeholder-import") ?
                Stream.of(entry) :
                loadProperties(entry.getValue());
    }

    private Stream<Map.Entry<String, String>> loadProperties(final String value) {
        final var props = new Properties();
        try (final var reader = resolveReader(value)) {
            props.load(reader);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
        final var base = props.stringPropertyNames().stream()
                .map(k -> entry(k, props.getProperty(k)));
        if (session == null) {
            return base;
        }
        final var interpolator = new PluginParameterExpressionEvaluator(session, mojoExecution);
        return base.map(e -> entry(e.getKey(), interpolate(interpolator, e.getValue())));
    }

    private String interpolate(final PluginParameterExpressionEvaluator interpolator, final String value) {
        try {
            final var evaluate = interpolator.evaluate(value);
            return evaluate == null ? value : String.valueOf(evaluate);
        } catch (final ExpressionEvaluationException | RuntimeException e) {
            getLog().debug(e);
            return value;
        }
    }

    private Reader resolveReader(final String value) throws IOException {
        final var path = Path.of(value);
        if (Files.exists(path)) {
            return Files.newBufferedReader(path, UTF_8);
        }
        final var from = requireNonNull(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(value),
                () -> "Can't find '" + value + "'");
        return new InputStreamReader(from, UTF_8);
    }

    // forward jul to maven logs to have a nicer output
    private Runnable setupLogger(final String name, final Level newLevel) {
        final var logger = Logger.getLogger(name);

        final var originalUseParent = logger.getUseParentHandlers();

        final Handler[] originalHandlers;
        if (!useDefaultLogging) {
            originalHandlers = logger.getHandlers();
            Stream.of(originalHandlers).forEach(logger::removeHandler);

            logger.setUseParentHandlers(false);

            logger.addHandler(new ForwardingHandler(getLog()));
        } else {
            originalHandlers = new Handler[0];
        }

        final var oldLevel = logger.getLevel();
        logger.setLevel(newLevel);

        return () -> {
            logger.setLevel(oldLevel);
            logger.setUseParentHandlers(originalUseParent);
            Stream.of(originalHandlers).forEach(logger::addHandler);
        };
    }

    @RequiredArgsConstructor
    private static class ForwardingHandler extends Handler {
        private final Log log;

        @Override
        public void publish(final LogRecord record) {
            switch (record.getLevel().getName()) {
                case "INFO":
                    log.info(toMessage(record), record.getThrown());
                    break;
                case "WARNING":
                    log.warn(toMessage(record), record.getThrown());
                    break;
                case "SEVERE":
                    log.error(toMessage(record), record.getThrown());
                    break;
                default:
                    log.debug(toMessage(record), record.getThrown());
            }
        }

        private String toMessage(final LogRecord record) {
            final var catalog = record.getResourceBundle();
            var format = record.getMessage();
            if (catalog != null) {
                try {
                    format = catalog.getString(record.getMessage());
                } catch (final MissingResourceException ex) {
                    format = record.getMessage();
                }
            }
            try {
                final var parameters = record.getParameters();
                if (parameters == null || parameters.length == 0) {
                    return format;
                }
                if (format.contains("{0") || format.contains("{1")
                        || format.contains("{2") || format.contains("{3")) {
                    return java.text.MessageFormat.format(format, parameters);
                }
                return format;
            } catch (final Exception ex) {
                return format;
            }
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() throws SecurityException {
            flush();
        }
    }
}
