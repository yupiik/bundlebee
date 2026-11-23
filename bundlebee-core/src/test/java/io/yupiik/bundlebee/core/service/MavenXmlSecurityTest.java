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
package io.yupiik.bundlebee.core.service;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXParseException;

class MavenXmlSecurityTest {
    @TempDir
    Path repository;

    private HttpServer server;
    private Maven maven;
    private String base;
    private final AtomicReference<String> metadata = new AtomicReference<>();
    private final AtomicInteger externalRequests = new AtomicInteger();
    private final List<String> artifactRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var path = exchange.getRequestURI().getPath();
            final String body;
            if (path.startsWith("/external")) {
                externalRequests.incrementAndGet();
                body = "<!ENTITY injected 'external-secret'>";
            } else if (path.endsWith("maven-metadata.xml")) {
                body = metadata.get();
            } else {
                artifactRequests.add(path);
                body = "artifact";
            }
            final var bytes = body.getBytes(UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, bytes.length);
                try (final var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        maven = new Maven(
                HttpClient.newHttpClient(),
                new MavenConfiguration(
                        5000, repository.toString(), true, true, base + "/repo/", base + "/repo/", UNSET, true));
        maven.init();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesOrdinaryPomAndSettings() throws Exception {
        try (final var input = xml("<project xmlns='http://maven.apache.org/POM/4.0.0'>"
                + "<groupId>org.example</groupId><artifactId>demo</artifactId><version>1.0</version></project>")) {
            final var gav = maven.extractGav(input);
            assertEquals("org.example", gav.getGroup());
            assertEquals("demo", gav.getArtifact());
            assertEquals("1.0", gav.getVersion());
        }
        final var settings = repository.resolve("settings.xml");
        final var master = maven.createPassword("master-secret", "settings.security");
        Files.writeString(
                repository.resolve("settings-security.xml"),
                "<settingsSecurity><master>" + master + "</master></settingsSecurity>");
        assertEquals(master, maven.findMasterPassword(settings).orElseThrow());
        Files.writeString(
                settings,
                "<settings><servers><server><id>test</id><username>user</username><password>"
                        + maven.createPassword("server-secret", "master-secret")
                        + "</password></server></servers></settings>");
        final var credentials = maven.findServerPassword(settings, "test").orElseThrow();
        assertEquals("user", credentials.getUsername());
        assertEquals("server-secret", credentials.getPassword());
    }

    @Test
    void parsesOrdinaryRemoteMetadata() throws Exception {
        metadata.set("<metadata><versioning><latest>1.2.3</latest><release>1.2.3</release>"
                + "<versions><version>1.0</version><version>1.2.3</version></versions>"
                + "<snapshot><timestamp>20261123.120000</timestamp><buildNumber>2</buildNumber></snapshot>"
                + "</versioning></metadata>");
        final var versions = maven.findAvailableVersions(base + "/repo", "org.example", "demo")
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals("1.2.3", versions.getLatest());
        assertEquals("1.2.3", versions.getRelease());
        assertEquals(List.of("1.0", "1.2.3"), versions.getVersions());
        download("LATEST");
        download("1.0-SNAPSHOT");
        assertEquals(
                List.of(
                        "/repo/org/example/demo/LATEST/demo-1.2.3.jar",
                        "/repo/org/example/demo/1.0-SNAPSHOT/demo-1.0-20261123.120000-2.jar"),
                artifactRequests);
        assertEquals(0, externalRequests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "http", "external-dtd", "parameter", "internal", "doctype"})
    void rejectsDoctypesAcrossAllParsingPaths(final String attack) throws Exception {
        final var secret = repository.resolve("secret.txt");
        Files.writeString(secret, "local-secret");
        final var doctype = switch (attack) {
            case "file" -> "<!DOCTYPE root [<!ENTITY injected SYSTEM '" + secret.toUri() + "'>]>";
            case "http" -> "<!DOCTYPE root [<!ENTITY injected SYSTEM '" + base + "/external/entity'>]>";
            case "external-dtd" -> "<!DOCTYPE root SYSTEM '" + base + "/external/dtd'>";
            case "parameter" -> "<!DOCTYPE root [<!ENTITY % remote SYSTEM '" + base + "/external/parameter'>%remote;]>";
            case "internal" -> "<!DOCTYPE root [<!ENTITY a 'secret'><!ENTITY injected '&a;&a;&a;&a;'>]>";
            case "doctype" -> "<!DOCTYPE root>";
            default -> throw new IllegalArgumentException(attack);
        };
        final var value = "doctype".equals(attack) ? "ordinary" : "&injected;";
        try (final var input = xml(doctype + "<project><groupId>" + value + "</groupId></project>")) {
            assertDoctypeRejected(IllegalStateException.class, () -> maven.extractGav(input));
        }
        final var settings = repository.resolve("settings.xml");
        final var security = repository.resolve("settings-security.xml");
        Files.writeString(security, doctype + "<settingsSecurity><master>" + value + "</master></settingsSecurity>");
        assertDoctypeRejected(IllegalArgumentException.class, () -> maven.findMasterPassword(settings));
        Files.delete(security);
        Files.writeString(
                settings,
                doctype + "<settings><servers><server><id>test</id><username>" + value
                        + "</username><password>password</password></server></servers></settings>");
        assertDoctypeRejected(IllegalArgumentException.class, () -> maven.findServerPassword(settings, "test"));
        Files.delete(settings);

        metadata.set(doctype + "<metadata><versioning><latest>" + value + "</latest><release>" + value
                + "</release><versions><version>" + value + "</version></versions>"
                + "<snapshot><timestamp>" + value + "</timestamp><buildNumber>2</buildNumber></snapshot>"
                + "</versioning></metadata>");
        final var versions = maven.findAvailableVersions(base + "/repo", "org.example", "demo")
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(new Maven.Versions(null, null, List.of()), versions);
        download("LATEST");
        download("1.0-SNAPSHOT");
        // Metadata parse failures retain the existing fallback behavior, without using attacker-controlled values.
        assertEquals(
                List.of(
                        "/repo/org/example/demo/LATEST/demo-LATEST.jar",
                        "/repo/org/example/demo/1.0-SNAPSHOT/demo-1.0-SNAPSHOT.jar"),
                artifactRequests);
        assertEquals(0, externalRequests.get(), "No XML parsing path may fetch external resources");
    }

    private void download(final String version) throws Exception {
        final var artifact = maven.findOrDownload("org.example:demo:" + version)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals("artifact", Files.readString(artifact));
    }

    private ByteArrayInputStream xml(final String content) {
        return new ByteArrayInputStream(content.getBytes(UTF_8));
    }

    private <T extends Throwable> void assertDoctypeRejected(final Class<T> type, final Executable parse) {
        final var error = assertThrows(type, parse);
        final var cause = assertInstanceOf(SAXParseException.class, error.getCause());
        assertTrue(cause.getMessage().contains("DOCTYPE"), cause::getMessage);
    }
}
