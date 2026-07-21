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

@Dependent
//metadata:start
// category = Date
//metadata:end
@Description("Formats seconds as a duration string")
public class DurationFunc implements HelmFunction {
    @Override
    public String name() {
        return "duration";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1) {
            return "";
        }
        final var secondsStr = String.valueOf(args[0]).trim();
        if (secondsStr.isEmpty()) {
            return "";
        }
        long seconds;
        try {
            seconds = (long) Double.parseDouble(secondsStr);
        } catch (final NumberFormatException e) {
            return "";
        }
        if (seconds < 0) {
            seconds = 0;
        }
        final var days = seconds / 86400;
        seconds %= 86400;
        final var hours = seconds / 3600;
        seconds %= 3600;
        final var minutes = seconds / 60;
        seconds %= 60;
        final var sb = new StringBuilder();
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
