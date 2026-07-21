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
import java.util.Map;

@Dependent
//metadata:start
// category = Defaults
//metadata:end
@Description("Returns true if the value is not empty")
public class NotEmptyFunc implements HelmFunction {
    @Override
    public String name() {
        return "notEmpty";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return false;
        }
        if (args[0] instanceof String) {
            final var s = (String) args[0];
            return !s.isEmpty();
        }
        if (args[0] instanceof ArrayList) {
            final var l = (ArrayList<?>) args[0];
            return !l.isEmpty();
        }
        if (args[0] instanceof Map) {
            final var m = (Map<?, ?>) args[0];
            return !m.isEmpty();
        }
        return true;
    }
}
