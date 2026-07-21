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
import java.util.List;

@Dependent
//metadata:start
// category = Type
//metadata:end
@Description("Converts a list to a list of strings")
public class ToStringsFunc implements io.yupiik.bundlebee.helm.HelmFunction {
    @Override
    public String name() {
        return "toStrings";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return List.of();
        }
        final var input = args[0];
        if (input instanceof List) {
            final List<?> list = (List<?>) input;
            final var result = new ArrayList<String>();
            for (final var item : list) {
                result.add(item == null ? "" : String.valueOf(item));
            }
            return result;
        }
        return List.of(String.valueOf(input));
    }
}
