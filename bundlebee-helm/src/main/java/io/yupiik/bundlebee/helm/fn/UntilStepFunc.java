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

import java.util.ArrayList;
import java.util.List;

@Dependent
//metadata:start
// category = List
//metadata:end
@Description("Generates a range with start, stop, and step")
public class UntilStepFunc implements HelmFunction {
    @Override
    public String name() {
        return "untilStep";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 3) {
            throw new IllegalArgumentException("untilStep requires three arguments: start, stop, step");
        }
        final var start = toInt(args[0]);
        final var stop = toInt(args[1]);
        final var step = toInt(args[2]);
        if (step == 0) {
            throw new IllegalArgumentException("untilStep step must not be zero");
        }
        final var result = new ArrayList<Integer>();
        if (step > 0) {
            for (var i = start; i < stop; i += step) {
                result.add(i);
            }
        } else {
            for (var i = start; i > stop; i += step) {
                result.add(i);
            }
        }
        return result;
    }

    private static int toInt(final Object arg) {
        return arg instanceof Integer ? ((Number) arg).intValue() : Integer.parseInt(arg.toString());
    }
}
