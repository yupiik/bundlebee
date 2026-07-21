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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Dependent
//metadata:start
// category = Date
//metadata:end
@Description("Modifies a date by a Go-style duration")
public class DateModifyFunc implements HelmFunction {
    @Override
    public String name() {
        return "dateModify";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 2) {
            return "";
        }
        final var modification = String.valueOf(args[0]);
        final var time = String.valueOf(args[1]);
        if (modification.isEmpty() || time.isEmpty()) {
            return "";
        }
        try {
            return applyModification(modification, time);
        } catch (final Exception e) {
            return time;
        }
    }

    static String applyModification(final String modification, final String time) {
        final var instant = Instant.parse(time.trim());
        final var duration = parseGoDuration(modification.trim());
        return instant.plus(duration).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static Duration parseGoDuration(final String goDuration) {
        final var trimmed = goDuration.trim();
        final var sign = trimmed.startsWith("-") ? -1 : 1;
        final var value = trimmed.startsWith("-") || trimmed.startsWith("+") ? trimmed.substring(1) : trimmed;
        final var numBuilder = new StringBuilder();
        final var unitBuilder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                numBuilder.append(c);
            } else {
                unitBuilder.append(c);
            }
        }
        final var num = Double.parseDouble(numBuilder.toString());
        final var unit = unitBuilder.toString();
        final long totalNanos;
        switch (unit) {
            case "ns":
                totalNanos = (long) (num * sign);
                return Duration.ofNanos(totalNanos);
            case "us":
            case "µs":
                totalNanos = (long) (num * 1000 * sign);
                return Duration.ofNanos(totalNanos);
            case "ms":
                totalNanos = (long) (num * 1_000_000 * sign);
                return Duration.ofNanos(totalNanos);
            case "s":
                return Duration.ofMillis((long) (num * 1000 * sign));
            case "m":
                return Duration.ofMillis((long) (num * 60_000 * sign));
            case "h":
                return Duration.ofMillis((long) (num * 3_600_000 * sign));
            case "d":
                return Duration.ofMillis((long) (num * 86_400_000 * sign));
            default:
                throw new IllegalArgumentException("Unknown duration unit: " + unit);
        }
    }
}
