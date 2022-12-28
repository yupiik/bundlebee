/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static java.util.stream.Collectors.toMap;

public final class Args {
    private Args() {
        // no-op
    }

    public static Map<String, String> toProperties(final String cmd, final String... args) {
        if ("completion".equals(cmd)) { // using complete -C will send an invalid command so fwd them manully for this one
            final var arguments = List.of(args);
            final var out = new HashMap<String, String>();
            final int pt = getArgIndex(arguments, "comp.point");
            if (pt >= 0 && args.length > pt + 1) {
                out.put("comp.point", arguments.get(pt + 1));
            }
            final int line = getArgIndex(arguments, "comp.line");
            if (line >= 0 && args.length > line + 1) {
                out.put("comp.line", arguments.get(line + 1));
            }
            final int useLogger = getArgIndex(arguments, "useLogger");
            if (useLogger >= 0 && args.length > useLogger + 1) {
                out.put("bundlebee.completion.useLogger", arguments.get(useLogger + 1));
            }
            return out;
        }
        if (args.length > 0 && (args.length % 2) == 0) {
            throw new IllegalArgumentException("" +
                    "Invalid argument parity, syntax is: <command> --<arg1> <value> --<arg2> <value> ..., got:\n" +
                    String.join(" ", args));
        }
        final var directMapping = IntStream.rangeClosed(0, (args.length / 2) - 1).boxed().collect(toMap(
                idx -> {
                    final var value = args[1 + idx * 2];
                    return value.startsWith("--") ? value.substring("--".length()) : value;
                },
                idx -> args[(1 + idx) * 2],
                (a, b) -> b /* prefer last one (override logic) */));

        // the configuration convention for commands is "bundlebee.<command name>.<property>" so we enable
        // to only use "--property" on the CLI by prefixing the properties not starting with "bundlebee"
        return directMapping.entrySet().stream()
                .flatMap(it -> it.getKey().startsWith("bundlebee.") ?
                        Stream.of(it) : Stream.of(it, entry("bundlebee." + cmd + "." + it.getKey(), it.getValue())))
                .flatMap(it -> !it.getKey().contains("-") ?
                        Stream.of(it) : Stream.of(it, entry(it.getKey().replace('-', '.'), it.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    private static int getArgIndex(final List<String> arguments, final String name) {
        final var pt = arguments.indexOf("--" + name);
        if (pt < 0) {
            return arguments.indexOf("--" + name.replace('.', '-'));
        }
        return pt;
    }
}
