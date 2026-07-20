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
// category = Other
//metadata:end
@Description("Abbreviates both sides of a string with ellipses")
public class AbbrevBothFunc implements HelmFunction {
    @Override
    public String name() {
        return "abbrevboth";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 3 || args[0] == null || args[1] == null || args[2] == null) {
            return "";
        }
        final var left = toInt(args[0]);
        final var maxlen = toInt(args[1]);
        final var s = args[2].toString();
        if (maxlen < 0 || s.isEmpty()) {
            return s;
        }
        if (s.length() <= maxlen) {
            return s;
        }
        if (maxlen <= 3) {
            return s.substring(0, maxlen);
        }
        final var right = maxlen - left - 3;
        if (right <= 0) {
            return s.substring(0, maxlen);
        }
        return s.substring(0, left) + "..." + s.substring(s.length() - right);
    }

    private int toInt(final Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
