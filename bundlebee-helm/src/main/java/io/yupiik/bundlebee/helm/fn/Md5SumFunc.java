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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Dependent
//metadata:start
// category = Other
//metadata:end
@Description("Computes the MD5 hex digest of a string")
public class Md5SumFunc implements HelmFunction {
    @Override
    public String name() {
        return "md5sum";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return "";
        }
        try {
            final var digest = MessageDigest.getInstance("MD5")
                    .digest(args[0].toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final var sb = new StringBuilder(digest.length * 2);
            for (final var b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
