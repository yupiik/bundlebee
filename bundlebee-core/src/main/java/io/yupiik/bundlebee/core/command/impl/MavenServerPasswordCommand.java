/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.bundlebee.core.service.Maven;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Log
@Dependent
public class MavenServerPasswordCommand implements Executable {
    @Inject
    @Description("Password value to cipher (`auto` generates a random one).")
    @ConfigProperty(name = "bundlebee.cipher-password.password", defaultValue = "auto")
    private String password;

    @Inject
    private Maven maven;

    @Override
    public String name() {
        return "cipher-password";
    }

    @Override
    public String description() {
        return "Cipher a password to put it in `~/.m2/settings.xml` servers (useful for deploy command for example).";
    }

    @Override
    public CompletionStage<?> execute() {
        final var settingsXml = maven.ensureSettingsXml();
        final var securityXml = settingsXml.getParent().resolve("settings-security.xml");
        if (!Files.exists(securityXml)) {
            throw new IllegalArgumentException(securityXml + " does not exist, ensure to call create-master-password command first.");
        }

        final String securityContent;
        try {
            securityContent = Files.readString(securityXml);
        } catch (final IOException e) {
            throw new IllegalStateException("Invalid " + securityXml, e);
        }
        final int start = securityContent.indexOf("<master>");
        final int end = securityContent.indexOf("</master>", start + 1);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Can't find master password in " + securityXml);
        }

        final var value = maven.createPassword(
                password,
                maven.decryptPassword(securityContent.substring(start + "<master>".length(), end).trim(), "settings.security"));
        log.info(() -> "You can add this server in " + settingsXml + ":\n" +
                "\n" +
                "    <server>\n" +
                "      <id>my-server</id>\n" +
                "      <username>" + System.getProperty("user.name", "user") + "</username>\n" +
                "      <password>" + value + "</password>\n" +
                "    </server>\n");

        return completedFuture(value);
    }
}
