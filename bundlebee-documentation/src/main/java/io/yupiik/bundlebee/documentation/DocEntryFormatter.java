/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.documentation;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.util.function.Function;

import static java.util.Locale.ROOT;

public class DocEntryFormatter {
    public String format(final Field it, final Function<String, String> nameMapper) {
        return format(it, nameMapper, false);
    }

    public String format(final Field it, final Function<String, String> nameMapper, final boolean envOnly) {
        final var annotation = it.getAnnotation(ConfigProperty.class);
        final var description = it.getAnnotation(Description.class);
        if (description == null) {
            throw new IllegalArgumentException("No @Description on " + it);
        }
        final var desc = description.value();
        var key = annotation.name();
        if (key.isEmpty()) {
            key = it.getDeclaringClass().getName() + "." + it.getName();
        }
        final var defaultValue = annotation.defaultValue();
        final var envKey = key.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(ROOT);
        return "" +
                (envOnly ? envKey : (nameMapper.apply(key) + " (`" + envKey + "`)")) + "::\n" +
                (desc.endsWith(".") ?
                        desc :
                        (desc + '.')) +
                (!ConfigProperty.UNCONFIGURED_VALUE.equals(defaultValue) && !Executable.UNSET.equals(defaultValue) ?
                        (" Default value: " +
                                (!defaultValue.contains("\n") ?
                                        "`" + defaultValue + "`" :
                                        "\n[source]\n----\n" + defaultValue + "\n----")) :
                        "");
    }
}
