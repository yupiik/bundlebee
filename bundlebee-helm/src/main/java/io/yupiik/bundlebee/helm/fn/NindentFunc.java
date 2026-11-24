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

import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.rangeClosed;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.helm.HelmFunction;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.regex.Pattern;

@DefaultScoped
// metadata:start
// category = String
// metadata:end
@Description("Indents every line and prepends a newline")
public class NindentFunc implements HelmFunction {
    private static final Pattern NEWLINE = Pattern.compile("\n");

    @Override
    public String name() {
        return "nindent";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2 || args[0] == null || args[1] == null) {
            return "";
        }
        final var spaces = toInt(args[0]);
        final var prefix = rangeClosed(1, spaces).mapToObj(i -> " ").collect(joining());
        final var lines = NEWLINE.split(args[1].toString(), -1);
        final var sb = new StringBuilder("\n");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            if (!lines[i].isEmpty()) {
                sb.append(prefix);
            }
            sb.append(lines[i]);
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
