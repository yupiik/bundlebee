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
import java.util.regex.PatternSyntaxException;

@Dependent
//metadata:start
// category = Regex
//metadata:end
@Description("Tests if a string matches a regex, throws on error")
public class MustRegexMatchFunc implements HelmFunction {
    @Override
    public String name() {
        return "mustRegexMatch";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2 || args[0] == null || args[1] == null) {
            throw new IllegalArgumentException("mustRegexMatch requires two arguments");
        }
        try {
            return Pattern.matches(args[0].toString(), args[1].toString());
        } catch (final PatternSyntaxException e) {
            throw new RuntimeException("invalid regex pattern: " + args[0], e);
        }
    }
}
