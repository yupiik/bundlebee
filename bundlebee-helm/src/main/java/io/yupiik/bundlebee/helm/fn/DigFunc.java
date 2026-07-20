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
// category = Dictionary
//metadata:end
@Description("Traverses nested dicts with a default value")
public class DigFunc implements io.yupiik.bundlebee.helm.HelmFunction {
    @Override
    public String name() {
        return "dig";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 2) {
            return null;
        }
        final var keys = new Object[args.length - 2];
        System.arraycopy(args, 0, keys, 0, keys.length);
        final var defaultValue = args[args.length - 2];
        var dict = args[args.length - 1];
        if (dict == null) {
            return defaultValue;
        }
        for (final var key : keys) {
            if (dict instanceof Map) {
                final Map<?, ?> map = (Map<?, ?>) dict;
                final var val = map.get(key);
                if (val == null) {
                    return defaultValue;
                }
                dict = val;
            } else {
                return defaultValue;
            }
        }
        return dict;
    }
}
