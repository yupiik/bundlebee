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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;

import io.yupiik.bundlebee.maven.interpolation.MavenConfigSource;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;

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
     * If true, use JUL only. Otherwise also forward INFO and higher records published on
     * the execution thread to Maven, subject to the existing JUL logger levels and filters.
     * Unowned async callbacks (including child threads and pooled workers) use JUL only:
     * ownership cannot be inferred without explicit propagation.
     * JUL handlers, levels and parent routing are never replaced or reconfigured, so an
     * owner-thread record can appear through both JUL and Maven if JUL has an output handler.
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

    /**
     * Executes with an invocation-local Maven configuration source.
     *
     * @param configuration lazy Maven expression lookup for this invocation
     */
    protected abstract void doExecute(final ConfigurationSource configuration);

    @Override
    public void execute() {
        if (skip || (skipPackaging != null && skipPackaging.contains(packaging))) {
            getLog().info(getClass().getName() + " execution skipped");
            return;
        }
        final var reset = setupLogger("io.yupiik.bundlebee");
        try {
            final ConfigurationSource source = session == null
                    ? key -> null
                    : new MavenConfigSource(
                            new PluginParameterExpressionEvaluator(session, mojoExecution),
                            !skipMavenForcedFilteringForPlaceholders);
            doExecute(source);
        } finally {
            reset.run();
        }
    }

    protected Stream<String> toArgs(final Map<String, String> placeholders) {
        return placeholders == null
                ? Stream.empty()
                : placeholders.entrySet().stream()
                        .flatMap(this::loadPlaceholders)
                        .flatMap(it -> Stream.of("--" + it.getKey(), it.getValue()));
    }

    private Stream<Map.Entry<String, String>> loadPlaceholders(final Map.Entry<String, String> entry) {
        return !entry.getKey().startsWith("bundlebee-placeholder-import")
                ? Stream.of(entry)
                : loadProperties(entry.getValue());
    }

    private Stream<Map.Entry<String, String>> loadProperties(final String value) {
        final var props = new Properties();
        try (var reader = resolveReader(value)) {
            props.load(reader);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
        final var base = props.stringPropertyNames().stream().map(k -> entry(k, props.getProperty(k)));
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
                () -> "Can\'t find \'" + value + "\'");
        return new InputStreamReader(from, UTF_8);
    }

    private Runnable setupLogger(final String name) {
        if (useDefaultLogging) {
            return () -> {};
        }
        final var logger = Logger.getLogger(name);
        final var forwarding = new ForwardingHandler(getLog(), Thread.currentThread());
        logger.addHandler(forwarding);
        return () -> {
            logger.removeHandler(forwarding);
            forwarding.close();
        };
    }

    private static final class ForwardingHandler extends Handler {
        private Log log;
        private volatile Thread owner;

        private ForwardingHandler(final Log log, final Thread owner) {
            this.log = log;
            this.owner = owner;
            setLevel(Level.INFO);
        }

        @Override
        public void publish(final LogRecord record) {
            // Match the publishing thread, not the record's (potentially supplied) thread id.
            if (Thread.currentThread() != owner || !isLoggable(record)) {
                return;
            }
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
                if (format.contains("{0") || format.contains("{1") || format.contains("{2") || format.contains("{3")) {
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
            // No thread-local context is installed or inherited by worker threads.
            owner = null;
            log = null;
        }
    }
}
