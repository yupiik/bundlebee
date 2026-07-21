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
import java.util.LinkedHashMap;
import java.util.Map;

@Dependent
//metadata:start
// category = Dictionary
//metadata:end
@Description("Merges dicts, first wins")
public class MustMergeFunc implements io.yupiik.bundlebee.helm.HelmFunction {
    @Override
    public String name() {
        return "mustMerge";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length == 0) {
            return null;
        }
        final var result = new LinkedHashMap<>();
        for (final var arg : args) {
            if (arg instanceof Map) {
                final Map<?, ?> map = (Map<?, ?>) arg;
                for (final var entry : map.entrySet()) {
                    if (entry.getValue() instanceof Map && result.get(entry.getKey()) instanceof Map) {
                        final Map<?, ?> nested = (Map<?, ?>) entry.getValue();
                        final Map<?, ?> existing = (Map<?, ?>) result.get(entry.getKey());
                        result.put(entry.getKey(), merge(existing, nested));
                    } else {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return result;
    }

    private Map<Object, Object> merge(final Map<?, ?> dest, final Map<?, ?> src) {
        final var result = new LinkedHashMap<>();
        result.putAll(dest);
        for (final var entry : src.entrySet()) {
            if (entry.getValue() instanceof Map && result.get(entry.getKey()) instanceof Map) {
                final Map<?, ?> nested = (Map<?, ?>) entry.getValue();
                final Map<?, ?> existing = (Map<?, ?>) result.get(entry.getKey());
                result.put(entry.getKey(), merge(existing, nested));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
