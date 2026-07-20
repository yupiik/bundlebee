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

import java.security.SecureRandom;

@Dependent
//metadata:start
// category = String
//metadata:end
@Description("Generates a random printable ASCII string of given length")
public class RandAsciiFunc implements HelmFunction {
    private final SecureRandom random = new SecureRandom();

    @Override
    public String name() {
        return "randAscii";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return "";
        }
        final var n = toInt(args[0]);
        if (n <= 0) {
            return "";
        }
        final var sb = new StringBuilder(n);
        for (var i = 0; i < n; i++) {
            sb.append((char) (32 + random.nextInt(95)));
        }
        return sb.toString();
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
