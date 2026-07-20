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
// category = Type
//metadata:end
@Description("Converts a value to an unsigned integer")
public class ToUintFunc implements HelmFunction {
    @Override
    public String name() {
        return "toUint";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return 0L;
        }
        final long value;
        if (args[0] instanceof Number) {
            value = ((Number) args[0]).longValue();
        } else {
            value = Long.parseLong(args[0].toString());
        }
        if (value < 0) {
            throw new IllegalArgumentException("toUint: negative value not allowed: " + value);
        }
        return value;
    }
}
