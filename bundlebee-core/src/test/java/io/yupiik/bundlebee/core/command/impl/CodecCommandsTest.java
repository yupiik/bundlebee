/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import io.yupiik.tools.codec.Codec;
import io.yupiik.tools.codec.simple.SimpleCodec;
import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CodecCommandsTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    private final Codec codec = new SimpleCodec(SimpleCodecConfiguration.builder().masterPassword("test").build());

    @Test
    void cipher(final CommandExecutor executor) {
        final var clearValue = "foo";
        assertEquals(clearValue, codec.decrypt(executor.wrap(null, INFO, () -> new BundleBee()
                .launch("cipher", "--masterPassword", "test", "--value", clearValue)).strip()));
    }

    @Test
    void decipher(final CommandExecutor executor) {
        final var clearValue = "foo";
        final var ciphered = codec.encrypt(clearValue);
        assertEquals(clearValue, executor.wrap(null, INFO, () -> new BundleBee()
                .launch("decipher", "--masterPassword", "test", "--value", ciphered)).strip());
    }
}
