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
package io.yupiik.bundlebee.maven.mojo;

import lombok.RequiredArgsConstructor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.MissingResourceException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Enables to have some global extension points - to change the classloader for ex.
 */
public abstract class BaseMojo extends AbstractMojo {
    /**
     * Skip execution.
     */
    @Parameter(property = "bundlebee.skip", defaultValue = "false")
    private boolean skip;

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
        try {
            doExecute();
        } finally {
            reset.forEach(Runnable::run);
        }
    }

    // forward jul to maven logs to have a nicer output
    private Runnable setupLogger(final String name, final Level newLevel) {
        final var logger = Logger.getLogger(name);

        final var originalHandlers = logger.getHandlers();
        Stream.of(originalHandlers).forEach(logger::removeHandler);

        final var originalUseParent = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);

        final var oldLevel = logger.getLevel();
        logger.setLevel(newLevel);

        logger.addHandler(new ForwardingHandler(getLog()));
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
