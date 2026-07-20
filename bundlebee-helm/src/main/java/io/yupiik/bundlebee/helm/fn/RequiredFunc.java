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
// category = Defaults
//metadata:end
@Description("Fails if the value is empty")
public class RequiredFunc implements HelmFunction {
    @Override
    public String name() {
        return "required";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("required requires at least 2 arguments");
        }
        final var msg = args[0] == null ? "" : args[0].toString();
        final var val = args[1];
        if (val == null) {
            throw new IllegalArgumentException(msg);
        }
        if (val instanceof String && ((String) val).isEmpty()) {
            throw new IllegalArgumentException(msg);
        }
        return val;
    }
}
