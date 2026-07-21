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
@Description("Generates a sequence of integers like bash seq")
public class SeqFunc implements HelmFunction {
    @Override
    public String name() {
        return "seq";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1) {
            throw new IllegalArgumentException("seq requires at least one argument");
        }
        final int start;
        final int stop;
        final int step;
        if (args.length == 1) {
            start = 1;
            stop = toInt(args[0]);
            step = start <= stop ? 1 : -1;
        } else if (args.length == 2) {
            start = toInt(args[0]);
            stop = toInt(args[1]);
            step = start <= stop ? 1 : -1;
        } else {
            start = toInt(args[0]);
            stop = toInt(args[2]);
            step = toInt(args[1]);
        }
        if (step == 0) {
            throw new IllegalArgumentException("seq step must not be zero");
        }
        final var result = new ArrayList<Integer>();
        if (step > 0) {
            for (var i = start; i <= stop; i += step) {
                result.add(i);
            }
        } else {
            for (var i = start; i >= stop; i += step) {
                result.add(i);
            }
        }
        return result;
    }

    private static int toInt(final Object arg) {
        return arg instanceof Integer ? ((Number) arg).intValue() : Integer.parseInt(arg.toString());
    }
}
