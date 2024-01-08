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
package io.yupiik.bundlebee.junit5.impl;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.junit5.CommandExecutor;
import io.yupiik.bundlebee.junit5.LogCapturer;
import io.yupiik.bundlebee.junit5.ValidateAlveolus;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.function.Function.identity;

public class DefaultCommandExecutor implements CommandExecutor {
    private final String[] command;
    private final LogCapturer logCapturer;
    private boolean executed;

    public DefaultCommandExecutor(final ValidateAlveolus config, final String mockServer, final LogCapturer logCapturer) {
        final var interpolator = new Substitutor((k, v) -> System.getProperty(k));
        this.command = Stream.of(
                        Stream.of(config.command()),
                        Stream.of(
                                "--bundlebee.kube.api", mockServer,
                                "--kubeconfig", "explicit"),
                        Stream.of(config.options())
                                .map(interpolator::replace),
                        Stream.of(config.placeholders())
                                .flatMap(it -> Stream.of("--" + it.key(), it.value())))
                .flatMap(identity())
                .toArray(String[]::new);
        this.logCapturer = logCapturer;
    }

    @Override
    public void run() {
        Runnable cleanup = null;
        if (logCapturer != null) {
            final var logger = Logger.getLogger("io.yupiik.bundlebee.core");
            final var handler = new Handler() {
                @Override
                public void publish(final LogRecord record) {
                    if (logCapturer.filter().test(record)) {
                        logCapturer.all().add(record);
                    }
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
            cleanup = () -> logger.removeHandler(handler);
        }
        try {
            BundleBee.main(command);
        } finally {
            executed = true;
            if (cleanup != null) {
                cleanup.run();
            }
        }
    }

    public boolean wasExecuted() {
        return executed;
    }
}
