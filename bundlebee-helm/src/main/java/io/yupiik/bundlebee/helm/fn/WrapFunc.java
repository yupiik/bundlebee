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
// category = String
//metadata:end
@Description("Wraps text at a given column count")
public class WrapFunc implements HelmFunction {
    @Override
    public String name() {
        return "wrap";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2 || args[0] == null || args[1] == null) {
            return "";
        }
        final var limit = toInt(args[0]);
        final var s = args[1].toString();
        if (limit <= 0 || s.isEmpty()) {
            return s;
        }
        final var sb = new StringBuilder();
        int start = 0;
        while (start < s.length()) {
            int end = Math.min(start + limit, s.length());
            if (end < s.length()) {
                int lastSpace = s.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s, start, end);
            start = end;
            if (start < s.length() && s.charAt(start) == ' ') {
                start++;
            }
        }
        return sb.toString();
    }

    private int toInt(final Object o) {
        if (o instanceof Number) {
            final var n = (Number) o;
            return n.intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
