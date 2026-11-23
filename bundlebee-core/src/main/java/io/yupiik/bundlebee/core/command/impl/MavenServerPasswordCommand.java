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
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = "cipher-password",
        description =
                "Cipher a password to put it in `~/.m2/settings.xml` servers (useful for deploy command for example).")
public class MavenServerPasswordCommand implements Executable {
    private final Logger log = Logger.getLogger(MavenServerPasswordCommand.class.getName());

    public MavenServerPasswordCommand(final Options options, Maven maven) {
        this.password = options.password();
        this.maven = maven;
    }

    private final String password;
    private final Maven maven;

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
            throw new IllegalArgumentException(
                    securityXml + " does not exist, ensure to call create-master-password command first.");
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
            throw new IllegalArgumentException("Can\'t find master password in " + securityXml);
        }
        final var value = maven.createPassword(
                password,
                maven.decryptPassword(
                        securityContent
                                .substring(start + "<master>".length(), end)
                                .trim(),
                        "settings.security"));
        log.info(() -> "You can add this server in " + settingsXml + ":\n" + "\n" + "    <server>\n"
                + "      <id>my-server</id>\n" + "      <username>" + System.getProperty("user.name", "user")
                + "</username>\n" + "      <password>" + value + "</password>\n" + "    </server>\n");
        return completedFuture(value);
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.cipher-password.password",
                    defaultValue = "\"auto\"",
                    documentation = "Password value to cipher (`auto` generates a random one).")
            String password) {}
}
