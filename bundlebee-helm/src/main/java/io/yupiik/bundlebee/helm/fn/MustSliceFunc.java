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
@Description("Returns a sub-range of a list")
public class MustSliceFunc implements HelmFunction {
    @Override
    public String name() {
        return "mustSlice";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 1 || args[0] == null) {
            throw new IllegalArgumentException("mustSlice requires a non-null list");
        }
        final var list = (List<?>) args[0];
        if (args.length == 1) {
            return new ArrayList<>(list);
        }
        final var start = Integer.parseInt(args[1].toString());
        if (args.length == 2) {
            if (start < 0 || start > list.size()) {
                throw new IllegalArgumentException("mustSlice index out of range: " + start);
            }
            return new ArrayList<>(list.subList(start, list.size()));
        }
        final var end = Integer.parseInt(args[2].toString());
        if (start < 0 || end > list.size() || start > end) {
            throw new IllegalArgumentException("mustSlice index out of range: start=" + start + " end=" + end);
        }
        return new ArrayList<>(list.subList(start, end));
    }
}
