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
package io.yupiik.bundlebee.junit5.impl;

import io.yupiik.bundlebee.junit5.LogCapturer;

import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.LogRecord;

import static java.util.Optional.ofNullable;

public class DefaultLogCapturer implements LogCapturer {
    private Predicate<LogRecord> filter = l -> ofNullable(l.getLoggerName())
            .or(() -> ofNullable(l.getSourceClassName()))
            .orElse("unknown")
            .startsWith("io.yupiik.bundlebee.core.");

    private final Collection<LogRecord> capture = new CopyOnWriteArrayList<>();


    @Override
    public LogCapturer useFilter(final Predicate<LogRecord> filter) {
        this.filter = filter;
        return this;
    }

    @Override
    public Predicate<LogRecord> filter() {
        return filter;
    }

    @Override
    public Collection<LogRecord> all() {
        return capture;
    }
}
