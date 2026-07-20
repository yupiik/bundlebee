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
// category = List
//metadata:end
@Description("Returns a sub-range of a list")
public class SliceFunc implements HelmFunction {
    @Override
    public String name() {
        return "slice";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 3 || args[0] == null || args[1] == null || args[2] == null) {
            return "";
        }
        final var start = Integer.parseInt(args[0].toString());
        final var end = Integer.parseInt(args[1].toString());
        final var s = args[2].toString();
        if (start < 0 || end > s.length() || start > end) {
            return "";
        }
        return s.substring(start, end);
    }
}
