/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Log
@Dependent
public class MavenMasterPasswordCommand implements Executable {
    @Inject
    @Description("Master password value, if `auto` it will be generated.")
    @ConfigProperty(name = "bundlebee.create-master-password.password", defaultValue = "auto")
    private String password;

    @Inject
    private Maven maven;

    @Override
    public String name() {
        return "create-master-password";
    }

    @Override
    public String description() {
        return "Generate a master password if none exist (in `~/.m2/settings-security.xml`)";
    }

    @Override
    public CompletionStage<?> execute() {
        final var settingsXml = maven.ensureSettingsXml();
        final var securityXml = settingsXml.getParent().resolve("settings-security.xml");
        if (Files.exists(securityXml)) {
            log.warning(() -> securityXml + " already exists, skipping");
            return completedFuture(false);
        }

        final var value = maven.createPassword(password, "settings.security");
        log.info(() -> "Created master password: " + value);
        try {
            Files.writeString(securityXml, "" +
                    "<settingsSecurity>\n" +
                    "  <master>" + value + "</master>\n" +
                    "</settingsSecurity>\n" +
                    "", StandardOpenOption.CREATE);
            log.info(() -> "Created " + securityXml);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        return completedFuture(value);
    }
}
