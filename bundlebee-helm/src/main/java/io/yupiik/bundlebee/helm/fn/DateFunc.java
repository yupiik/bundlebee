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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;

@Dependent
//metadata:start
// category = Date
//metadata:end
@Description("Formats a date using a Go-style layout")
public class DateFunc implements HelmFunction {
    @Override
    public String name() {
        return "date";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 2) {
            return "";
        }
        final var layout = String.valueOf(args[0]);
        final var time = String.valueOf(args[1]);
        if (layout.isEmpty() || time.isEmpty()) {
            return "";
        }
        return formatWithLayout(layout, parseTime(time));
    }

    static ZonedDateTime parseTime(final String time) {
        final var trimmed = time.trim();
        try {
            return Instant.parse(trimmed).atZone(ZoneId.systemDefault());
        } catch (final Exception e) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (final Exception e) {
            // fall through
        }
        return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    static String formatWithLayout(final String layout, final ZonedDateTime zdt) {
        final var formatter = DateTimeFormatter.ofPattern(goToJavaLayout(layout)).withZone(zdt.getZone());
        return zdt.format(formatter);
    }

    static String goToJavaLayout(final String goLayout) {
        final var sb = new StringBuilder();
        final var chars = goLayout.toCharArray();
        int i = 0;
        while (i < chars.length) {
            final var c = chars[i];
            if (Character.isLetter(c) && Character.isUpperCase(c)) {
                // collect consecutive uppercase letters
                final var start = i;
                while (i < chars.length && Character.isLetter(chars[i]) && Character.isUpperCase(chars[i])) {
                    i++;
                }
                final var token = new String(chars, start, i - start);
                sb.append(goTokenToJava(token));
            } else if (c == '\'') {
                // quoted literal
                i++;
                final var start = i;
                while (i < chars.length && chars[i] != '\'') {
                    i++;
                }
                sb.append('\'').append(chars, start, i - start).append('\'');
                if (i < chars.length) {
                    i++; // skip closing quote
                }
            } else if (c == '.') {
                sb.append('.');
                i++;
            } else if (c == '-' || c == '/' || c == ':' || c == ' ' || c == 'T') {
                sb.append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String goTokenToJava(final String token) {
        switch (token) {
            case "2006":
                return "yyyy";
            case "2005":
                return "yy";
            case "01":
                return "MM";
            case "1":
                return "M";
            case "02":
                return "dd";
            case "2":
                return "d";
            case "15":
                return "HH";
            case "3":
                return "H";
            case "04":
                return "mm";
            case "4":
                return "m";
            case "05":
                return "ss";
            case "5":
                return "s";
            case "000":
                return "SSS";
            case "999":
                return "SSS";
            case "PM":
                return "a";
            case "Mon":
                return "EEE";
            case "Monday":
                return "EEEE";
            case "Jan":
                return "MMM";
            case "January":
                return "MMMM";
            case "MST":
                return "Z";
            case "Z0700":
                return "XXX";
            case "Z070000":
                return "XX";
            case "-0700":
                return "Z";
            case "-07:00":
                return "XXX";
            case "-07":
                return "XX";
            default:
                return token;
        }
    }
}
