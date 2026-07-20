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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Dependent
//metadata:start
// category = Dictionary
//metadata:end
@Description("Excludes given keys from a dict")
public class OmitFunc implements HelmFunction {
    @Override
    public String name() {
        return "omit";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(final Object... args) {
        if (args == null || args.length < 2 || args[0] == null || args[1] == null) {
            return new LinkedHashMap<>();
        }
        final var keys = (List<String>) args[0];
        final var dict = (Map<String, Object>) args[1];
        final var result = new LinkedHashMap<String, Object>();
        for (final var entry : dict.entrySet()) {
            if (!keys.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
