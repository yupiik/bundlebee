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
// category = Math
//metadata:end
@Description("Computes the modulus of two numbers")
public class ModFunc implements HelmFunction {
    @Override
    public String name() {
        return "mod";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 2) {
            throw new IllegalArgumentException("mod requires two arguments");
        }
        return ((Number) args[0]).intValue() % ((Number) args[1]).intValue();
    }

    private static double toDouble(final Object arg) {
        return arg instanceof Number ? ((Number) arg).doubleValue() : Double.parseDouble(arg.toString());
    }
}
