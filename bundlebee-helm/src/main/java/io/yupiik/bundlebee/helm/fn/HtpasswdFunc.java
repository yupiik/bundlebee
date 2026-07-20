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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Dependent
//metadata:start
// category = Crypto
//metadata:end
@Description("Generates an htpasswd entry")
public class HtpasswdFunc implements HelmFunction {
    @Override
    public String name() {
        return "htpasswd";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2 || args[0] == null || args[1] == null) {
            return "";
        }
        final var user = args[0].toString();
        final var pass = args[1].toString();
        final var algo = args.length >= 3 && args[2] != null ? args[2].toString().toLowerCase() : "bcrypt";

        switch (algo) {
            case "sha":
            case "sha1":
                return sha1Entry(user, pass);
            case "bcrypt":
                return bcryptEntry(user, pass);
            default:
                return bcryptEntry(user, pass);
        }
    }

    private String sha1Entry(final String user, final String pass) {
        try {
            final var md = MessageDigest.getInstance("SHA-1");
            final var digest = md.digest((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            return user + ":{SHA}" + Base64.getEncoder().encodeToString(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String bcryptEntry(final String user, final String pass) {
        return user + ":" + BcryptFunc.hashBcrypt(pass);
    }
}
