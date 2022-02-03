/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.bundlebee.core.test.http.SpyingResponseLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.talend.sdk.component.junit.http.api.HttpApiHandler;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.internal.impl.ResponseImpl;
import org.talend.sdk.component.junit.http.junit5.HttpApi;
import org.talend.sdk.component.junit.http.junit5.HttpApiInject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@HttpApi(useSsl = true)
class DeployCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @HttpApiInject
    private HttpApiHandler<?> handler;

    @Test
    void nexus3(final CommandExecutor executor, final TestInfo info,
                @TempDir final Path temp) {
        final var spy = newSpyingHandler(info);
        handler.setResponseLocator(spy);

        new BundleBee().launch("new",
                "--group", "com.company",
                "--artifact", "foo",
                "--dir", temp.toString());

        final var logs = executor.wrap(handler, INFO, () -> new BundleBee().launch("deploy",
                "--nexusBaseApi", "http://admin:admin@localhost:" + handler.getPort(),
                "--deployInLocalRepository", "false",
                "--dir", temp.toString()));
        final var dirString = temp.toString().replace('\\', '/');
        assertEquals("" +
                "Including bundlebee/manifest.json\n" +
                "Including bundlebee/kubernetes/com.company_foo_my-alveolus.configmap.yaml\n" +
                "Built " + dirString + "/target/foo-1.0.0.jar\n" +
                "Project successfully built.\n" +
                "Uploaded " + dirString + "/target/foo-1.0.0.jar on Nexus repository maven-releases\n" +
                "", logs);
        assertEquals(4 /*test v2, test v3, test jar exists, upload jar */, spy.getFound().size());
    }

    private SpyingResponseLocator newSpyingHandler(final TestInfo info) {
        return new SpyingResponseLocator(
                info.getTestClass().orElseThrow().getName() + "_" + info.getTestMethod().orElseThrow().getName()) {
            @Override
            protected Optional<Response> doFind(final Request request, final String pref, final ClassLoader loader,
                                                final Predicate<String> headerFilter, final boolean exactMatching) {
                switch (request.method()) {
                    case "CONNECT":
                        return Optional.empty();
                    case "GET":
                        if ("/repository/maven-releases/com/company/foo/1.0.0/foo-1.0.0.jar".equals(request.uri())) {
                            return Optional.of(new ResponseImpl(Map.of(), 404, "{}".getBytes(StandardCharsets.UTF_8)));
                        }
                        return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                    case "DELETE":
                    case "PUT":
                        /* the proxy we use converts request payloads to string which prevents to do that so we just do a sanity check
                        try (final JarInputStream jar = new JarInputStream(new ByteArrayInputStream(request.payload().getBytes(StandardCharsets.ISO_8859_1)))) {
                            final AtomicReference<JarEntry> last = new AtomicReference<>();
                            assertEquals(
                                    List.of(),
                                    Stream.<JarEntry>iterate(
                                            null, jarEntry -> {
                                                try {
                                                    final var entry = jar.getNextJarEntry();
                                                    last.set(entry);
                                                    return entry != null;
                                                } catch (final IOException e) {
                                                    return false;
                                                }
                                            }, ignored -> last.get())
                                            .filter(Objects::nonNull)
                                            .map(JarEntry::getName)
                                            .sorted()
                                            .collect(toList()));
                        } catch (final IOException e) {
                            return Optional.of(new ResponseImpl(Map.of(), 500, e.getMessage().getBytes(StandardCharsets.UTF_8)));
                        }
                        */
                        assertTrue(request.payload().startsWith("PK"));
                        assertTrue(request.payload().contains("META-INF/MANIFEST.MF"));
                        return Optional.of(new ResponseImpl(Map.of(), 200, "{}".getBytes(StandardCharsets.UTF_8)));
                    default:
                        return Optional.of(new ResponseImpl(Map.of(), 500, "{}".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };
    }
}
