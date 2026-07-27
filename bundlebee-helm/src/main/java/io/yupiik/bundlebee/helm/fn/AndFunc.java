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

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.helm.HelmFunction;

import javax.enterprise.context.Dependent;

@Dependent
//metadata:start
// category = Defaults
//metadata:end
@Description("Returns the logical AND of two values, returning the last truthy value or the first falsy one")
public class AndFunc implements HelmFunction {
    @Override
    public String name() {
        return "and";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2) {
            return args.length == 1 ? args[0] : null;
        }
        var result = args[0];
        for (int i = 1; i < args.length; i++) {
            if (!isTruthy(result)) {
                return result;
            }
            result = args[i];
        }
        return result;
    }

    private boolean isTruthy(final Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        return true;
    }

    private boolean isTruthyAllowNull(final Object value) {
        return value != null && isTruthy(value);
    }
}
