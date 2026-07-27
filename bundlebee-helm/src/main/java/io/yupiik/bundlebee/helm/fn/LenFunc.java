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
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

@Dependent
//metadata:start
// category = Generic
//metadata:end
@Description("Returns the integer length of a given value")
public class LenFunc implements HelmFunction {
    @Override
    public String name() {
        return "len";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1 || args[0] == null) {
            return 0;
        }
        final var value = args[0];
        if (value instanceof String) {
            return ((String) value).length();
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).size();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).size();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return value.toString().length();
    }
}
