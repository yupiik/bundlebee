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

import java.util.regex.Pattern;

@Dependent
//metadata:start
// category = Regex
//metadata:end
@Description("Escapes all regex metacharacters")
public class RegexQuoteMetaFunc implements HelmFunction {
    @Override
    public String name() {
        return "regexQuoteMeta";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 1 || args[0] == null) {
            return "";
        }
        return Pattern.quote(args[0].toString());
    }
}
