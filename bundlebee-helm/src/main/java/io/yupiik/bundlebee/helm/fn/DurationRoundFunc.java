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

import java.util.regex.Pattern;

@Dependent
//metadata:start
// category = Date
//metadata:end
@Description("Rounds a duration to the most significant unit")
public class DurationRoundFunc implements HelmFunction {
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)(d|h|m|s)");

    @Override
    public String name() {
        return "durationRound";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1) {
            return "";
        }
        final var duration = String.valueOf(args[0]).trim();
        if (duration.isEmpty()) {
            return "";
        }
        final var matcher = DURATION_PATTERN.matcher(duration);
        String firstUnit = null;
        long firstValue = 0;
        while (matcher.find()) {
            if (firstUnit == null) {
                firstUnit = matcher.group(2);
                firstValue = Long.parseLong(matcher.group(1));
            }
        }
        if (firstUnit == null) {
            return duration;
        }
        return firstValue + firstUnit;
    }
}
