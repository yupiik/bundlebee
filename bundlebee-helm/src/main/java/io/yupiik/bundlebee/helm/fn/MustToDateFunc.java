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

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Dependent
//metadata:start
// category = Date
//metadata:end
@Description("Converts a string to a date, throws on error")
public class MustToDateFunc implements HelmFunction {
    @Override
    public String name() {
        return "mustToDate";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 2) {
            throw new IllegalArgumentException("mustToDate requires at least 2 arguments (layout, value)");
        }
        final var layout = String.valueOf(args[0]);
        final var value = String.valueOf(args[1]);
        if (layout.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException("mustToDate: layout and value must not be empty");
        }
        final var javaPattern = DateFunc.goToJavaLayout(layout);
        final var formatter = DateTimeFormatter.ofPattern(javaPattern);
        final var parsed = ZonedDateTime.parse(value.trim(), formatter);
        return parsed.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
