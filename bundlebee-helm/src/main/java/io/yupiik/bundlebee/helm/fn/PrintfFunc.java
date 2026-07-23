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

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.helm.HelmFunction;

import javax.enterprise.context.Dependent;

@Dependent
//metadata:start
// category = Other
//metadata:end
@Description("Returns a formatted string using fmt.Sprintf")
public class PrintfFunc implements HelmFunction {
    @Override
    public String name() {
        return "printf";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1 || args[0] == null) {
            return "";
        }
        final var format = args[0].toString();
        if (args.length == 1) {
            return format;
        }
        final var fmtArgs = new Object[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            fmtArgs[i - 1] = args[i] == null ? "<nil>" : args[i];
        }
        try {
            return String.format(format, fmtArgs);
        } catch (final Exception e) {
            return format;
        }
    }
}
