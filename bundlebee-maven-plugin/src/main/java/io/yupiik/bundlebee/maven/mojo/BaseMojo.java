package io.yupiik.bundlebee.maven.mojo;

import lombok.RequiredArgsConstructor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.logging.Log;

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
    protected abstract void doExecute();

    @Override
    public void execute() {
        final var reset = Stream.of(
                setupLogger("org.apache.webbeans", Level.WARNING),
                setupLogger("io.yupiik.bundlebee", Level.INFO))
                .collect(toList());
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
