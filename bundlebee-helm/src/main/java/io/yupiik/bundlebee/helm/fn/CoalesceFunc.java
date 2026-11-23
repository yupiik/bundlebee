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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

@DefaultScoped
// metadata:start
// category = Defaults
// metadata:end
@Description("Returns the first non-empty value")
public class CoalesceFunc implements HelmFunction {
    @Override
    public String name() {
        return "coalesce";
    }

    @Override
    public Object execute(final Object... args) {
        for (final var arg : args) {
            if (!isEmpty(arg)) {
                return arg;
            }
        }
        return null;
    }

    private boolean isEmpty(final Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() == 0;
        }
        if (value instanceof Boolean) {
            return !(Boolean) value;
        }
        if (value instanceof String) {
            return ((String) value).isEmpty();
        }
        if (value instanceof java.util.Map) {
            return ((java.util.Map<?, ?>) value).isEmpty();
        }
        if (value instanceof java.util.Collection) {
            return ((java.util.Collection<?>) value).isEmpty();
        }
        return false;
    }
}
