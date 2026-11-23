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
package io.yupiik.bundlebee.documentation;

import static io.yupiik.bundlebee.documentation.JsonSupport.string;
import static java.util.Locale.ROOT;

import io.yupiik.bundlebee.core.command.Executable;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

public class DocEntryFormatter {
    private static final Pattern ENVIRONMENT_KEY = Pattern.compile("[^A-Za-z0-9]");

    public String format(final Map<String, Object> option, final Function<String, String> nameMapper) {
        return format(option, nameMapper, false);
    }

    public String format(
            final Map<String, Object> option, final Function<String, String> nameMapper, final boolean envOnly) {
        final var key = string(option, "name");
        final var description = string(option, "description");
        if (key == null || key.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("Incomplete option metadata: " + option);
        }
        final var defaultValue = string(option, "defaultValue");
        final var envKey = ENVIRONMENT_KEY.matcher(key).replaceAll("_").toUpperCase(ROOT);
        return (envOnly ? envKey : (nameMapper.apply(key) + " (`" + envKey + "`)")) + "::\n"
                + (description.endsWith(".") ? description : description + '.')
                + (defaultValue != null && !"\u0000".equals(defaultValue) && !Executable.UNSET.equals(defaultValue)
                        ? " Default value: "
                                + (!defaultValue.contains("\n")
                                        ? "`" + defaultValue + "`"
                                        : "\n[source]\n----\n" + defaultValue + "\n----")
                        : "");
    }
}
