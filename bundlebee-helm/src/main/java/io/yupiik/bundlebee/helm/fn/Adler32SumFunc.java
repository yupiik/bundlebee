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
@Description("Computes the Adler-32 checksum")
public class Adler32SumFunc implements HelmFunction {
    @Override
    public String name() {
        return "adler32sum";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return "";
        }
        final var data = args[0].toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var a = 1;
        var b = 0;
        for (final var c : data) {
            a = (a + (c & 0xFF)) % 65521;
            b = (b + a) % 65521;
        }
        return String.format("%08x", (b << 16) | a);
    }
}
