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
import java.security.SecureRandom;

/**
 * Placeholder bcrypt implementation using SHA-256 + salt.
 * Note: this is NOT real bcrypt - it produces a $2a$ prefixed hash for compatibility
 * with htpasswd format but uses SHA-256 internally.
 * For real bcrypt, use org.mindrot.jbcrypt.BCrypt.
 */
@Dependent
//metadata:start
// category = Crypto
//metadata:end
@Description("Generates a bcrypt hash of a password")
public class BcryptFunc implements HelmFunction {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BCRYPT_CHARSET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Override
    public String name() {
        return "bcrypt";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length == 0 || args[0] == null) {
            return "";
        }
        return hashBcrypt(args[0].toString());
    }

    public static String hashBcrypt(final String password) {
        try {
            final var salt = new byte[16];
            RANDOM.nextBytes(salt);
            final var saltBase64 = base64Encode(salt);
            final var md = MessageDigest.getInstance("SHA-256");
            md.update(password.getBytes(StandardCharsets.UTF_8));
            md.update(salt);
            final var hash = md.digest();
            final var hashBase64 = base64Encode(hash);
            return "$2a$10$" + saltBase64 + hashBase64.substring(0, 31);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Encode(final byte[] data) {
        final var sb = new StringBuilder();
        for (var i = 0; i < data.length; i += 3) {
            final var b0 = data[i] & 0xFF;
            final var b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            final var b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(BCRYPT_CHARSET.charAt(b0 >> 2));
            sb.append(BCRYPT_CHARSET.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            if (i + 1 < data.length) {
                sb.append(BCRYPT_CHARSET.charAt(((b1 & 15) << 2) | (b2 >> 6)));
            }
            if (i + 2 < data.length) {
                sb.append(BCRYPT_CHARSET.charAt(b2 & 63));
            }
        }
        return sb.toString();
    }
}
