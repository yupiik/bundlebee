/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.test;

import org.talend.sdk.component.junit.http.api.HttpApiHandler;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class CommandExecutor {
    public String wrap(final HttpApiHandler<?> mock, final Level level, final Runnable task) {
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
                if ("io.yupiik.bundlebee.core.kube.KubeClient".equals(record.getLoggerName()) &&
                        record.getMessage() != null &&
                        record.getMessage().startsWith("java.net.ConnectException")) {
                    return;
                }
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
            return content.toString().replace('\\', '/');
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
        }
    }
}
