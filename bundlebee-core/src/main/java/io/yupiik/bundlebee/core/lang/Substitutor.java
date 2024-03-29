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
package io.yupiik.bundlebee.core.lang;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.handlebars.HandlebarsInterpolator;
import io.yupiik.bundlebee.core.service.AlveolusHandler;

import javax.enterprise.inject.Vetoed;
import java.util.Map;
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

    private final int maxIterations = Integer.getInteger("bundlebee.substitutor.max-iterations", 100);

    private final BiFunction<String, String, String> lookup;

    public Substitutor(final BiFunction<String, String, String> lookup) {
        this.lookup = lookup;
    }

    public Substitutor(final Function<String, String> lookup) {
        this.lookup = (key, defVal) -> lookup.apply(key);
    }

    /**
     * @deprecated ensure to pass an execution id or explicitly {@code null}.
     */
    @Deprecated
    public String replace(final String source) {
        return replace(source, null);
    }

    public String replace(final Manifest.Alveolus alveolus,
                          final AlveolusHandler.LoadedDescriptor desc,
                          final String source, final String id) {
        return doReplace(alveolus, desc, source, id);
    }

    public String replace(final String source, final String id) {
        return replace(null, null, source, id);
    }

    private String substitute(final Manifest.Alveolus alveolus, AlveolusHandler.LoadedDescriptor descriptor,
                              final String input, int iteration, final String id) {
        if (iteration > maxIterations) {
            return input;
        }

        int from = 0;
        int start = -1;
        while (from < input.length()) {
            start = input.indexOf(PREFIX, from);
            if (start < 0) {
                return input;
            }
            if (start == 0 || input.charAt(start - 1) != ESCAPE) {
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
        if (nested >= 0 && !(nested > 0 && key.charAt(nested - 1) == ESCAPE)) {
            final var nestedPlaceholder = key + SUFFIX;
            final var newKey = substitute(alveolus, descriptor, nestedPlaceholder, iteration + 1, id);
            return input.replace(nestedPlaceholder, newKey);
        }

        final var startOfString = input.substring(0, start);
        final var endOfString = input.substring(end + SUFFIX.length());

        final int sep = key.indexOf(VALUE_DELIMITER);
        if (sep > 0) {
            final var actualKey = key.substring(0, sep);
            final var fallback = key.substring(sep + VALUE_DELIMITER.length());
            return startOfString + doGetOrDefault(alveolus, descriptor, actualKey, fallback, id) + endOfString;
        }
        return startOfString + doGetOrDefault(alveolus, descriptor, key, null, id) + endOfString;
    }

    protected Map<String, Function<Object, String>> handlebarsHelpers() {
        return Map.of();
    }

    private String handlebars(final Manifest.Alveolus alveolus, final AlveolusHandler.LoadedDescriptor desc,
                              final String source, final String id) {
        return new HandlebarsInterpolator(alveolus, desc, id, handlebarsHelpers(), this::replace).apply(source);
    }

    private String doReplace(final Manifest.Alveolus alveolus, final AlveolusHandler.LoadedDescriptor desc, final String source, final String id) {
        if (source == null) {
            return null;
        }

        if (desc != null && desc.getExtension() != null && ("hb".equals(desc.getExtension()) || "handlebars".equals(desc.getExtension()))) {
            return handlebars(alveolus, desc, source, id);
        }

        String current = source;
        do {
            final var previous = current;
            current = substitute(alveolus, desc, current, 0, id);
            if (previous.equals(current)) {
                return previous.replace(ESCAPE + PREFIX, PREFIX);
            }
        } while (true);
    }

    @Deprecated
    protected String getOrDefault(final String varName, final String varDefaultValue) {
        return getOrDefault(varName, varDefaultValue, null);
    }

    protected String doGetOrDefault(final Manifest.Alveolus alveolus,
                                    final AlveolusHandler.LoadedDescriptor descriptor,
                                    final String varName, final String varDefaultValue, final String id) {
        if ("executionId".equals(varName)) {
            return id == null ? "" : id;
        }
        if ("descriptor.name".equals(varName)) {
            return descriptor == null ? "" : descriptor.getConfiguration().getName();
        }
        if ("alveolus.name".equals(varName)) {
            return alveolus == null ? "" : alveolus.getName();
        }
        if ("alveolus.version".equals(varName)) {
            return alveolus == null || alveolus.getVersion() == null ? "" : alveolus.getVersion();
        }
        return getOrDefault(varName, varDefaultValue, id);
    }

    protected String getOrDefault(final String varName, final String varDefaultValue, final String id) {
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
