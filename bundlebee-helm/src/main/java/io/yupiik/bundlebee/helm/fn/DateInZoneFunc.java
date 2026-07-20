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
import java.time.format.DateTimeFormatter;

@Dependent
//metadata:start
// category = Date
//metadata:end
@Description("Formats a date with a specific timezone")
public class DateInZoneFunc implements HelmFunction {
    @Override
    public String name() {
        return "dateInZone";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 3) {
            return "";
        }
        final var layout = String.valueOf(args[0]);
        final var time = String.valueOf(args[1]);
        final var zone = String.valueOf(args[2]);
        if (layout.isEmpty() || time.isEmpty() || zone.isEmpty()) {
            return "";
        }
        final var zdt = DateFunc.parseTime(time).withZoneSameInstant(ZoneId.of(zone));
        return DateFunc.formatWithLayout(layout, zdt);
    }
}
