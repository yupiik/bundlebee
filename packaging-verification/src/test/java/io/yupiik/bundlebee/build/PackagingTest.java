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
package io.yupiik.bundlebee.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.internal.parser.BufferProvider;
import io.yupiik.fusion.json.internal.parser.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class PackagingTest {
    private static final Pattern MULTI_RELEASE = Pattern.compile("^META-INF/versions/[0-9]+/");
    private static final Pattern COMMENT_SEPARATOR = Pattern.compile("#");
    private static final String MODULE = "META-INF/services/io.yupiik.fusion.framework.api.container.FusionModule";
    private static final List<String> LEGACY = List.of(
            "javax/enterprise/",
            "jakarta/enterprise/",
            "javax/inject/",
            "jakarta/inject/",
            "javax/interceptor/",
            "jakarta/interceptor/",
            "javax/annotation/",
            "jakarta/annotation/",
            "javax/json/",
            "jakarta/json/",
            "org/apache/webbeans/",
            "org/apache/openwebbeans/",
            "org/apache/johnzon/",
            "org/apache/geronimo/config/",
            "org/eclipse/microprofile/config/",
            "org/apache/xbean/");
    private static final List<String> JSON_METADATA = List.of(
            "META-INF/fusion/configuration/documentation.json",
            "META-INF/fusion/json/schemas.json",
            "META-INF/fusion/jsonrpc/openrpc.json");

    @TempDir
    Path temporary;

    @Test
    @EnabledIfSystemProperty(named = "bundlebee.root", matches = ".+")
    void packagedApplication() throws Exception {
        final var root = Path.of(System.getProperty("bundlebee.root")).toAbsolutePath();
        final var version = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(root.resolve("pom.xml").toFile())
                .getDocumentElement();
        String projectVersion = null;
        for (var child = version.getFirstChild(); child != null; child = child.getNextSibling()) {
            if ("version".equals(child.getNodeName())) {
                projectVersion = child.getTextContent().strip();
            }
        }
        assertNotNull(projectVersion, "Root project version");
        final var core = root.resolve("bundlebee-core/target/bundlebee-core-" + projectVersion + ".jar");
        final var helm = root.resolve("bundlebee-helm/target/bundlebee-helm-" + projectVersion + ".jar");
        final var fat = root.resolve("bundlebee-core/target/bundlebee-core-" + projectVersion + "-fat.jar");
        verify(fat, List.of(core, helm));
        smoke(fat, "help", List.of("[] apply:", "[] helm:", "[] version:"));
        smoke(fat, "version", List.of(projectVersion));
    }

    private void verify(final Path fatPath, final List<Path> thinPaths) throws IOException {
        try (final var fat = new JarFile(fatPath.toFile());
                final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
            final var names = new HashSet<String>();
            final var entries = fat.entries();
            while (entries.hasMoreElements()) {
                final var name = entries.nextElement().getName();
                assertTrue(names.add(name), "Duplicate fat jar entry: " + name);
                // Include multi-release classes when checking the old EE implementation/API packages.
                final var className = MULTI_RELEASE.matcher(name).replaceFirst("");
                assertFalse(
                        name.endsWith(".class") && LEGACY.stream().anyMatch(className::startsWith),
                        "Legacy class: " + name);
                assertFalse(
                        name.equals("META-INF/beans.xml")
                                || name.startsWith("META-INF/openwebbeans/")
                                || name.startsWith("META-INF/services/javax.enterprise.")
                                || name.startsWith("META-INF/services/jakarta.enterprise.")
                                || name.startsWith("META-INF/services/org.eclipse.microprofile.config."),
                        "Legacy resource: " + name);
            }
            assertNotNull(fat.getManifest(), "Executable manifest");
            assertEquals(
                    "io.yupiik.bundlebee.core.BundleBee",
                    fat.getManifest().getMainAttributes().getValue("Main-Class"));
            for (final var thinPath : thinPaths) {
                try (final var thin = new JarFile(thinPath.toFile())) {
                    assertFalse(providers(text(thin, MODULE)).isEmpty(), "No Fusion module in " + thinPath);
                    for (final var entry :
                            thin.stream().filter(e -> !e.isDirectory()).toList()) {
                        final var name = entry.getName();
                        if (name.startsWith("META-INF/services/")) {
                            assertTrue(
                                    providers(text(fat, name)).containsAll(providers(text(thin, name))),
                                    "Lost service providers: " + thinPath + "!" + name);
                        } else if (JSON_METADATA.contains(name)
                                || (name.startsWith("META-INF/native-image/") && name.endsWith(".json"))) {
                            contains(
                                    json(mapper, text(thin, name)),
                                    json(mapper, text(fat, name)),
                                    thinPath + "!" + name);
                        } else if (name.endsWith(".class") && !name.equals("module-info.class")) {
                            assertNotNull(fat.getJarEntry(name), "Lost application class: " + name);
                        }
                    }
                }
            }
            // Check all merged providers, including transitive dependencies, are loadable from the fat jar.
            for (final var name : names.stream()
                    .filter(n -> n.startsWith("META-INF/services/") && !n.endsWith("/"))
                    .toList()) {
                for (final var provider : providers(text(fat, name))) {
                    assertNotNull(
                            fat.getJarEntry(provider.replace('.', '/') + ".class"),
                            "Missing provider class: " + provider);
                }
            }
            for (final var name : JSON_METADATA) {
                if (names.contains(name)) {
                    json(mapper, text(fat, name));
                } else {
                    System.out.println("Not emitted by this application (merge not exercised): " + name);
                }
            }
        }
    }

    private static String text(final JarFile jar, final String name) throws IOException {
        final var entry = jar.getJarEntry(name);
        assertNotNull(entry, "Missing " + jar.getName() + "!" + name);
        try (final var input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Object json(final JsonMapperImpl mapper, final String text) {
        // Consume the whole document: mapping alone can overlook trailing content.
        try (final var parser = new JsonParser(new StringReader(text), 1024, new BufferProvider(1024, 4), true)) {
            var depth = 0;
            var complete = false;
            while (parser.hasNext()) {
                assertFalse(complete, "Trailing JSON content");
                switch (parser.next()) {
                    case START_OBJECT, START_ARRAY -> depth++;
                    case END_OBJECT, END_ARRAY -> {
                        depth--;
                        complete = depth == 0;
                    }
                    default -> {
                        assertTrue(depth > 0, "Expected a JSON object or array");
                    }
                }
            }
            assertTrue(complete, "Incomplete JSON document");
        }
        return mapper.fromString(Object.class, text);
    }

    private static Set<String> providers(final String text) {
        final var result = new HashSet<String>();
        for (final var line : text.lines().toList()) {
            final var provider = COMMENT_SEPARATOR.split(line, 2)[0].strip();
            if (!provider.isEmpty()) {
                result.add(provider);
            }
        }
        return result;
    }

    private static void contains(final Object expected, final Object actual, final String location) {
        if (expected instanceof Map<?, ?> map) {
            assertTrue(actual instanceof Map<?, ?>, location);
            final var merged = (Map<?, ?>) actual;
            map.forEach((key, value) -> {
                assertTrue(merged.containsKey(key), "Lost metadata: " + location + "/" + key);
                contains(value, merged.get(key), location + "/" + key);
            });
        } else if (expected instanceof List<?> list) {
            assertTrue(actual instanceof List<?>, location);
            assertTrue(((List<?>) actual).containsAll(list), "Lost metadata list entries: " + location);
        } else {
            assertEquals(expected, actual, "Changed metadata: " + location);
        }
    }

    private void smoke(final Path fat, final String command, final List<String> expected) throws Exception {
        final var home = Files.createDirectories(temporary.resolve(command));
        final var output = home.resolve("output.log");
        final var args = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-D.handlers=io.yupiik.logging.jul.handler.StandardHandler",
                "-Duser.home=" + home,
                "-Djava.net.useSystemProxies=false",
                "-Dhttp.proxyHost=127.0.0.1",
                "-Dhttp.proxyPort=1",
                "-Dhttps.proxyHost=127.0.0.1",
                "-Dhttps.proxyPort=1",
                "-jar",
                fat.toAbsolutePath().toString(),
                command,
                "--kubeconfig",
                "explicit",
                "--bundlebee.kube.api",
                "http://127.0.0.1:1"));
        if (Boolean.getBoolean("bundlebee.offline")) {
            // CI's passwordless sudo creates a fresh network namespace with no external interfaces.
            args.addAll(0, List.of("/usr/bin/sudo", "-n", "/usr/bin/unshare", "--net", "--"));
        }
        final var builder = new ProcessBuilder(args)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        // Prevent host kube/Maven credentials, JVM injection, and in-cluster configuration from leaking in.
        final var environment = builder.environment();
        environment.clear();
        environment.put("HOME", home.toString());
        environment.put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
        environment.put("LANG", "C.UTF-8");
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), command + " timed out; see " + output);
            final var log = Files.readString(output);
            assertEquals(0, process.exitValue(), command + ":\n" + log);
            for (final var fragment : expected) {
                assertTrue(log.contains(fragment), command + " missing '" + fragment + "':\n" + log);
            }
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void acceptsMergedModulesAndMetadata() throws Exception {
        final var thin = fixture("thin.jar", Map.of());
        final var helm = fixture(
                "helm.jar",
                Map.of(
                        MODULE,
                        "helm.Module\n",
                        "helm/Module.class",
                        "",
                        JSON_METADATA.get(1),
                        "{\"schemas\":{\"helm.Model\":{\"type\":\"string\"}}}"));
        final var fat = fixture(
                "fat.jar",
                Map.of(
                        MODULE,
                        "example.Module\nhelm.Module\n",
                        "helm/Module.class",
                        "",
                        JSON_METADATA.get(1),
                        "{\"schemas\":{\"example.Model\":{\"type\":\"object\"},\"helm.Model\":{\"type\":\"string\"}}}"));
        verify(fat, List.of(thin, helm));
    }

    @Test
    void rejectsLostModuleProvider() throws Exception {
        final var thin = fixture("thin.jar", Map.of());
        final var fat = fixture("fat.jar", Map.of(MODULE, "other.Module\n", "other/Module.class", ""));
        assertThrows(AssertionError.class, () -> verify(fat, List.of(thin)));
    }

    @Test
    void rejectsLostMetadata() throws Exception {
        final var thin = fixture("thin.jar", Map.of());
        final var fat = fixture("fat.jar", Map.of(JSON_METADATA.get(1), "{\"schemas\":{}}"));
        assertThrows(AssertionError.class, () -> verify(fat, List.of(thin)));
    }

    @Test
    void rejectsLegacyClassesAndResources() throws Exception {
        final var thin = fixture("thin.jar", Map.of());
        for (final var legacy : List.of(
                "javax/json/Json.class",
                "META-INF/versions/17/jakarta/enterprise/Bean.class",
                "org/apache/webbeans/Container.class",
                "META-INF/beans.xml")) {
            final var fat = fixture("fat.jar", Map.of(legacy, ""));
            assertThrows(AssertionError.class, () -> verify(fat, List.of(thin)), legacy);
        }
    }

    @Test
    void rejectsMissingProviderClass() throws Exception {
        final var thin = fixture("thin.jar", Map.of());
        final var fat = fixture("fat.jar", Map.of("META-INF/services/example.Service", "missing.Provider"));
        assertThrows(AssertionError.class, () -> verify(fat, List.of(thin)));
    }

    @Test
    void rejectsMalformedMetadata() throws Exception {
        try (final var mapper = new JsonMapperImpl(List.of(), key -> Optional.empty())) {
            assertThrows(RuntimeException.class, () -> json(mapper, "{\"schemas\":{]"));
            assertThrows(RuntimeException.class, () -> json(mapper, "{\"schemas\":{}} {}"));
        }
    }

    @Test
    void preservesConfigurationRootsAndNativeMetadata() throws Exception {
        final var metadata = Map.of(
                JSON_METADATA.get(0),
                "{\"version\":1,\"classes\":{\"Config\":{}},\"roots\":[\"Config\"]}",
                "META-INF/native-image/example/module/reachability-metadata.json",
                "{\"reflection\":[{\"type\":\"Config\"}]}");
        final var thin = fixture("thin.jar", metadata);
        verify(fixture("fat.jar", metadata), List.of(thin));
        final var lost = new HashMap<>(metadata);
        lost.put(JSON_METADATA.get(0), "{\"version\":1,\"classes\":{\"Config\":{}},\"roots\":[]}");
        assertThrows(AssertionError.class, () -> verify(fixture("fat.jar", lost), List.of(thin)));
    }

    private Path fixture(final String file, final Map<String, String> overrides) throws IOException {
        final var entries = new HashMap<>(Map.of(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nMain-Class: io.yupiik.bundlebee.core.BundleBee\n\n",
                MODULE,
                "example.Module # comment\n",
                "example/Module.class",
                "",
                JSON_METADATA.get(1),
                "{\"schemas\":{\"example.Model\":{\"type\":\"object\"}}}"));
        entries.putAll(overrides);
        final var path = temporary.resolve(file);
        try (final var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (final var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }
}
