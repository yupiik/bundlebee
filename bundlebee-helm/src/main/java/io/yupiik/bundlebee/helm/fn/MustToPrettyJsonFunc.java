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
import io.yupiik.bundlebee.helm.internal.JsonFunctionSupport;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;

@DefaultScoped
// metadata:start
// category = JSON/YAML
// metadata:end
@Description("Encodes a value to pretty JSON, throws on error")
public class MustToPrettyJsonFunc extends JsonFunctionSupport {
    public MustToPrettyJsonFunc(final JsonMapper mapper) {
        super(mapper);
    }

    @Override
    public String name() {
        return "mustToPrettyJson";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return "null";
        }
        return encode(args[0], true, false);
    }
}
