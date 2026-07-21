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
import java.util.Map;

@Dependent
//metadata:start
// category = Type
//metadata:end
@Description("Returns the type of a value")
public class TypeOfFunc implements HelmFunction {
    @Override
    public String name() {
        return "typeOf";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return "<nil>";
        }
        if (args[0] instanceof Map) {
            return "map";
        }
        if (args[0] instanceof ArrayList) {
            return "slice";
        }
        if (args[0] instanceof Boolean) {
            return "bool";
        }
        if (args[0] instanceof Integer || args[0] instanceof Long) {
            return "int";
        }
        if (args[0] instanceof Float || args[0] instanceof Double) {
            return "float64";
        }
        return "string";
    }
}
