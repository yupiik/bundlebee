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

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.bundlebee.core.service.MavenConfiguration;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class MavenPasswordCommandsTest {
    private static final Pattern CIPHER = Pattern.compile("\\{[^}]+}");

    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void masterAndServer(@TempDir final Path m2, final CommandExecutor executor) throws Exception {
        final var m2String = m2.toString().replace('\\', '/');
        // 1. generate a master password
        assertEquals(
                "Created " + m2String + "/settings.xml\n" + "Created master password: {xxx}\n" + "Created " + m2String
                        + "/settings-security.xml\n" + "",
                CIPHER.matcher(executor.wrap(
                                null,
                                INFO,
                                () -> new BundleBee()
                                        .launch(
                                                "create-master-password",
                                                "--bundlebee-maven-cache",
                                                m2String,
                                                "--bundlebee-maven-preferCustomSettingsXml",
                                                "true",
                                                "--bundlebee-maven-forceCustomSettingsXml",
                                                "true",
                                                "--bundlebee-create-master-password-password",
                                                "secret")))
                        .replaceFirst("{xxx}"));
        // 2. generate a server password
        final var logs = executor.wrap(
                null,
                INFO,
                () -> new BundleBee()
                        .launch(
                                "cipher-password",
                                "--bundlebee-maven-cache",
                                m2String,
                                "--bundlebee-maven-preferCustomSettingsXml",
                                "true",
                                "--bundlebee-maven-forceCustomSettingsXml",
                                "true",
                                "--bundlebee-cipher-password-password",
                                "server-secret"));
        assertEquals(
                "You can add this server in " + m2String + "/settings.xml:\n" + "\n" + "    <server>\n"
                        + "      <id>my-server</id>\n" + "      <username>" + System.getProperty("user.name", "foo")
                        + "</username>\n" + "      <password>{xxx}</password>\n" + "    </server>\n" + "\n" + "",
                CIPHER.matcher(logs).replaceFirst("{xxx}"));
        // append the server
        final var settingsXml = m2.resolve("settings.xml");
        final var server = logs.substring(logs.indexOf("<server>"), logs.indexOf("</server>"))
                .trim();
        Files.writeString(
                settingsXml,
                Files.readString(settingsXml, StandardCharsets.UTF_8)
                        .replace("<servers>", "<servers>\n    " + server + "\n    </server>\n"),
                StandardOpenOption.TRUNCATE_EXISTING);
        // 3. ensure it can be deciphered
        final var maven = new Maven(
                HttpClient.newHttpClient(),
                new MavenConfiguration(
                        30000, m2String, true, true, "https://repo.maven.apache.org/maven2/", UNSET, UNSET, false));
        final var init = Maven.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(maven);
        assertEquals(
                "server-secret",
                maven.findServerPassword(settingsXml, "my-server").orElseThrow().getPassword());
    }
}
