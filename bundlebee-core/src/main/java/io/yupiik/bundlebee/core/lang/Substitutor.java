/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.lang;

import javax.enterprise.inject.Vetoed;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

// forked from commons-text but no need to bring a dep only for < 200 LOC
@Vetoed
public class Substitutor {
    private static final char ESCAPE = '\\';
    private static final String PREFIX = "{{";
    private static final String SUFFIX = "}}";
    private static final String VALUE_DELIMITER = ":-";

    private final BiFunction<String, String, String> lookup;

    public Substitutor(final BiFunction<String, String, String> lookup) {
        this.lookup = lookup;
    }

    public Substitutor(final Function<String, String> lookup) {
        this.lookup = (key, defVal) -> lookup.apply(key);
    }

    public String replace(final String source) {
        if (source == null) {
            return null;
        }
        String current = source;
        do {
            final var previous = current;
            current = substitute(current, 0);
            if (previous.equals(current)) {
                return previous;
            }
        } while (true);
    }

    private String substitute(final String input, int iteration) {
        if (iteration > 25) {
            return input;
        }
        int from = 0;
        int start = -1;
        while (start < 0 && from < input.length()) {
            start = input.indexOf(PREFIX, from);
            if (start < 0) {
                return input;
            }
            if (start != 0 && input.charAt(start - 1) != ESCAPE) {
                break;
            }
            from = start + 1;
        }
        final var keyStart = start + PREFIX.length();
        final var end = input.indexOf(SUFFIX, keyStart);
        if (end < 0) {
            return input;
        }
        final var key = input.substring(start + PREFIX.length(), end);
        final var nested = key.indexOf(PREFIX);
        if (nested > 0) {
            final var nestedPlaceholder = key + SUFFIX;
            final var newKey = substitute(nestedPlaceholder, iteration + 1);
            return input.replace(nestedPlaceholder, newKey);
        }

        final var startOfString = input.substring(0, start);
        final var endOfString = input.substring(end + SUFFIX.length());

        final int sep = key.indexOf(VALUE_DELIMITER);
        if (sep > 0) {
            final var actualKey = key.substring(0, sep);
            final var fallback = key.substring(sep + VALUE_DELIMITER.length());
            return startOfString + getOrDefault(actualKey, fallback) + endOfString;
        }
        return startOfString + getOrDefault(key, null) + endOfString;
    }

    protected String getOrDefault(final String varName, final String varDefaultValue) {
        try {
            return ofNullable(lookup.apply(varName, varDefaultValue)).orElse(varDefaultValue);
        } catch (final RuntimeException re) {
            if (varDefaultValue != null) {
                return varDefaultValue;
            }
            throw re;
        }
    }
}
