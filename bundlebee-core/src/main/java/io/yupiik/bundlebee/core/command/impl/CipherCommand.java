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

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.tools.codec.simple.SimpleCodec;
import io.yupiik.tools.codec.simple.SimpleCodecConfiguration;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Log
@Dependent
public class CipherCommand implements Executable {
    @Inject
    @Description("Master password..")
    @ConfigProperty(name = "bundlebee.cipher.masterPassword", defaultValue = UNSET)
    private String masterPassword;

    @Inject
    @Description("Value to cipher.")
    @ConfigProperty(name = "bundlebee.cipher.value", defaultValue = UNSET)
    private String value;

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
        log.info(new SimpleCodec(SimpleCodecConfiguration.builder().masterPassword(masterPassword).build()).encrypt(value.strip()));
        return completedFuture(null);
    }
}
