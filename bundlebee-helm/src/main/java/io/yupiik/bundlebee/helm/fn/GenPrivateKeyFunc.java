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

import java.security.KeyPairGenerator;

@Dependent
//metadata:start
// category = Crypto
//metadata:end
@Description("Generates a PEM-encoded private key")
public class GenPrivateKeyFunc implements HelmFunction {
    @Override
    public String name() {
        return "genPrivateKey";
    }

    @Override
    public Object execute(final Object... args) {
        try {
            final var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            final var keyPair = generator.generateKeyPair();
            final var encoded = java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
            return "-----BEGIN RSA PRIVATE KEY-----\n" + encoded + "\n-----END RSA PRIVATE KEY-----";
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate RSA private key", e);
        }
    }
}
