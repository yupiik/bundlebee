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

import java.util.List;
import java.util.Map;

@Dependent
//metadata:start
// category = List
//metadata:end
@Description("Returns the element at the given index from a list or map")
public class IndexFunc implements HelmFunction {
    @Override
    public String name() {
        return "index";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(final Object... args) {
        if (args.length < 2 || args[0] == null) {
            return null;
        }
        final var target = args[0];
        final var index = args[1];
        if (target instanceof List) {
            final var list = (List<?>) target;
            if (index instanceof Number) {
                final var i = ((Number) index).intValue();
                return (i >= 0 && i < list.size()) ? list.get(i) : null;
            }
            try {
                final var i = Integer.parseInt(index.toString());
                return (i >= 0 && i < list.size()) ? list.get(i) : null;
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        if (target instanceof Map) {
            return ((Map<?, ?>) target).get(index.toString());
        }
        if (target instanceof String) {
            final var s = (String) target;
            try {
                final var i = Integer.parseInt(index.toString());
                return (i >= 0 && i < s.length()) ? s.substring(i, i + 1) : null;
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
