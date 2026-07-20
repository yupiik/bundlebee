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

import javax.enterprise.context.Dependent;

import io.yupiik.bundlebee.core.configuration.Description;
import java.util.List;
import java.util.Map;

@Dependent
//metadata:start
// category = Defaults
//metadata:end
@Description("Returns true if any value is non-empty")
public class AnyFunc implements io.yupiik.bundlebee.helm.HelmFunction {
    @Override
    public String name() {
        return "any";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length == 0) {
            return false;
        }
        for (final var arg : args) {
            if (!isEmpty(arg)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEmpty(final Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            final String s = (String) value;
            return s.isEmpty();
        }
        if (value instanceof Boolean) {
            final Boolean b = (Boolean) value;
            return !b;
        }
        if (value instanceof Number) {
            final Number n = (Number) value;
            return n.doubleValue() == 0;
        }
        if (value instanceof List) {
            final List<?> l = (List<?>) value;
            return l.isEmpty();
        }
        if (value instanceof Map) {
            final Map<?, ?> m = (Map<?, ?>) value;
            return m.isEmpty();
        }
        return false;
    }
}
