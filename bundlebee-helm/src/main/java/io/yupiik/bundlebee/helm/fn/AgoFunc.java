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
package io.yupiik.bundlebee.helm.fn;

import io.yupiik.bundlebee.helm.HelmFunction;

import javax.enterprise.context.Dependent;

import io.yupiik.bundlebee.core.configuration.Description;

import java.time.Duration;
import java.time.Instant;

@Dependent
//metadata:start
// category = Date
//metadata:end
@Description("Returns the duration from a timestamp to now")
public class AgoFunc implements HelmFunction {
    @Override
    public String name() {
        return "ago";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1) {
            return "";
        }
        final var time = String.valueOf(args[0]);
        if (time.isEmpty()) {
            return "";
        }
        final var inputInstant = Instant.parse(time.trim());
        final var duration = Duration.between(inputInstant, Instant.now());
        if (duration.isNegative()) {
            return "0s";
        }
        return formatDuration(duration);
    }

    static String formatDuration(final Duration duration) {
        final var sb = new StringBuilder();
        long seconds = duration.getSeconds();
        final var days = seconds / 86400;
        seconds %= 86400;
        final var hours = seconds / 3600;
        seconds %= 3600;
        final var minutes = seconds / 60;
        seconds %= 60;
        if (days > 0) {
            sb.append(days).append('d');
        }
        if (hours > 0) {
            sb.append(hours).append('h');
        }
        if (minutes > 0) {
            sb.append(minutes).append('m');
        }
        if (seconds > 0 || sb.length() == 0) {
            sb.append(seconds).append('s');
        }
        return sb.toString();
    }
}
