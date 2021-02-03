package io.yupiik.bundlebee.core.test;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class CommandExecutor {
    public String wrap(final Level level, final Runnable task) {
        final var logger = Logger.getLogger("io.yupiik.bundlebee.core");
        final var oldLevel = logger.getLevel();
        logger.setLevel(level);
        final var content = new StringBuilder();
        final var handler = new Handler() {
            {
                setFormatter(new Formatter() {
                    @Override
                    public String format(final LogRecord record) {
                        return record.getMessage();
                    }
                });
            }

            @Override
            public void publish(final LogRecord record) {
                content.append(getFormatter().format(record)).append('\n');
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                flush();
            }
        };
        logger.addHandler(handler);
        try {
            task.run();
            return content.toString();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
        }
    }
}
