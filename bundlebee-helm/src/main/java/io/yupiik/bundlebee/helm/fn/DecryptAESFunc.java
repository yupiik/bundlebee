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
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Dependent
//metadata:start
// category = Crypto
//metadata:end
@Description("Decrypts AES-256 CBC encrypted text")
public class DecryptAESFunc implements HelmFunction {
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int KEY_SIZE = 32; // 256 bits
    private static final int IV_SIZE = 16;  // 128 bits

    @Override
    public String name() {
        return "decryptAES";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2 || args[0] == null || args[1] == null) {
            throw new IllegalArgumentException("decryptAES requires key and ciphertext arguments");
        }
        try {
            final var keyBytes = deriveKey(args[0].toString());
            final var decoded = Base64.getDecoder().decode(args[1].toString());

            if (decoded.length < IV_SIZE) {
                throw new IllegalArgumentException("Ciphertext too short - missing IV");
            }

            final var iv = new byte[IV_SIZE];
            System.arraycopy(decoded, 0, iv, 0, IV_SIZE);
            final var ciphertext = new byte[decoded.length - IV_SIZE];
            System.arraycopy(decoded, IV_SIZE, ciphertext, 0, ciphertext.length);

            final var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new IvParameterSpec(iv));
            final var decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to decrypt with AES", e);
        }
    }

    private byte[] deriveKey(final String key) {
        final var keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length >= KEY_SIZE) {
            final var result = new byte[KEY_SIZE];
            System.arraycopy(keyBytes, 0, result, 0, KEY_SIZE);
            return result;
        }
        final var result = new byte[KEY_SIZE];
        System.arraycopy(keyBytes, 0, result, 0, keyBytes.length);
        return result;
    }
}
