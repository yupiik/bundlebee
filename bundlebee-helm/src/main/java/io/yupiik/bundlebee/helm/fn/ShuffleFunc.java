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
import java.util.Collections;

@Dependent
//metadata:start
// category = String
//metadata:end
@Description("Randomizes the characters in a string")
public class ShuffleFunc implements HelmFunction {
    @Override
    public String name() {
        return "shuffle";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return "";
        }
        final var s = args[0].toString();
        if (s.isEmpty()) {
            return s;
        }
        final var list = new ArrayList<Character>(s.length());
        for (final char c : s.toCharArray()) {
            list.add(c);
        }
        Collections.shuffle(list);
        final var sb = new StringBuilder(s.length());
        for (final char c : list) {
            sb.append(c);
        }
        return sb.toString();
    }
}
