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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

@Dependent
//metadata:start
// category = URL
//metadata:end
@Description("Returns the URL-encoded form of the input string")
public class UrlqueryFunc implements HelmFunction {
    @Override
    public String name() {
        return "urlquery";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1 || args[0] == null) {
            return "";
        }
        try {
            return URLEncoder.encode(args[0].toString(), "UTF-8")
                    .replace("+", "%20");
        } catch (final UnsupportedEncodingException e) {
            return args[0].toString();
        }
    }
}
