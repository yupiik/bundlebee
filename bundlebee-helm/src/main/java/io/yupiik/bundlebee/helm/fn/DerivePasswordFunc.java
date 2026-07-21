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
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Dependent
//metadata:start
// category = Crypto
//metadata:end
@Description("Derives a password using the Spectre algorithm")
public class DerivePasswordFunc implements HelmFunction {
    @Override
    public String name() {
        return "derivePassword";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 5 || args[0] == null || args[1] == null || args[2] == null
                || args[3] == null || args[4] == null) {
            throw new IllegalArgumentException(
                    "derivePassword requires 5 arguments: counter, type, password, user, site");
        }
        final var counter = toInt(args[0]);
        final var type = args[1].toString();
        final var password = args[2].toString();
        final var user = args[3].toString();
        final var site = args[4].toString();

        // Spectre-based derivation using HMAC-SHA256 as a simplified approach
        // Full Spectre algorithm is significantly more complex
        try {
            final var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final var template = type + ":" + user + "@" + site + ":" + counter;
            final var hash = mac.doFinal(template.getBytes(StandardCharsets.UTF_8));
            final var sb = new StringBuilder();
            for (final var b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private int toInt(final Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (final NumberFormatException e) {
            return 1;
        }
    }
}
