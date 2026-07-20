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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Dependent
//metadata:start
// category = Dictionary
//metadata:end
@Description("Deep copies a value")
public class MustDeepCopyFunc implements io.yupiik.bundlebee.helm.HelmFunction {
    @Override
    public String name() {
        return "mustDeepCopy";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return null;
        }
        return deepCopy(args[0]);
    }

    private Object deepCopy(final Object value) {
        if (value instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) value;
            final var copy = new LinkedHashMap<>();
            for (final var entry : map.entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            final List<?> list = (List<?>) value;
            final var copy = new ArrayList<>();
            for (final var item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
