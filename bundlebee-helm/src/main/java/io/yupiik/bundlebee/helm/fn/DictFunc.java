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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Dependent
//metadata:start
// category = Dictionary
//metadata:end
@Description("Creates a dictionary from key-value pairs")
public class DictFunc implements HelmFunction {
    @Override
    public String name() {
        return "dict";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(final Object... args) {
        if (args.length == 1 && args[0] instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) args[0]);
        }
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("dict requires an even number of arguments");
        }
        final var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < args.length; i += 2) {
            map.put(args[i] == null ? "" : args[i].toString(), args[i + 1]);
        }
        return map;
    }
}
