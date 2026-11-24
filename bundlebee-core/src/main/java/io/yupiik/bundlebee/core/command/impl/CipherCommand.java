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
package io.yupiik.bundlebee.core.command.impl;

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.tools.codec.simple.SimpleCodec;
import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = "cipher",
        description =
                "Enables to cipher a value using `AES/CBC/PKCS5Padding` algorithm with a master password. It can then be used as a placeholder with the syntax `{{bundlebee-decipher:{{my.master.password}},$cipheredValue}}`.")
public class CipherCommand implements Executable {
    private final Logger log = Logger.getLogger(CipherCommand.class.getName());

    public CipherCommand(final Options options) {
        this.masterPassword = options.masterPassword();
        this.value = options.value();
    }

    private final String masterPassword;
    private final String value;

    @Override
    public String name() {
        return "cipher";
    }

    @Override
    public String description() {
        return "Enables to cipher a value using `AES/CBC/PKCS5Padding` algorithm with a master password. It can then be used as a placeholder with the syntax `{{bundlebee-decipher:{{my.master.password}},$cipheredValue}}`.";
    }

    @Override
    public CompletionStage<?> execute() {
        if (UNSET.equals(value) || UNSET.equals(masterPassword)) {
            throw new IllegalArgumentException("Missing master password or value to decipher, ensure both are set.");
        }
        // not a lambda to fail if the ciphering fails even if it is not logged
        log.info(new SimpleCodec(SimpleCodecConfiguration.builder()
                        .masterPassword(masterPassword)
                        .build())
                .encrypt(value.strip()));
        return completedFuture(null);
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.cipher.masterPassword",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Master password..")
            String masterPassword,

            @Property(
                    value = "bundlebee.cipher.value",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Value to cipher.")
            String value) {}
}
