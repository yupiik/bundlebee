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

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@FusionSupport
class ManifestReaderTest {

    @Fusion
    private ManifestReader reader;

    @Fusion
    private ArchiveReader archiveReader;

    @Test
    void interpolateInitFromManifest(@TempDir final Path work) throws IOException {
        assertEquals(
                List.of(true),
                writeAndLoadInterpolateFlags(
                        work,
                        "{" + "\"interpolateAlveoli\":true,"
                                + "\"alveoli\":[{\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]" + "}"));
        assertEquals(
                List.of(false),
                writeAndLoadInterpolateFlags(
                        work,
                        "{" + "\"interpolateAlveoli\":false,"
                                + "\"alveoli\":[{\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]" + "}"));
    }

    @Test
    void interpolateInitFromAlveolus(@TempDir final Path work) throws IOException {
        assertEquals(
                List.of(true),
                writeAndLoadInterpolateFlags(
                        work,
                        "{"
                                + "\"alveoli\":[{\"interpolateDescriptors\":true,\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]"
                                + "}"));
        assertEquals(
                List.of(false),
                writeAndLoadInterpolateFlags(
                        work,
                        "{"
                                + "\"alveoli\":[{\"interpolateDescriptors\":false,\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]"
                                + "}"));
    }

    @Test
    void interpolateInitFromAlveolusOverridingManifest(@TempDir final Path work) throws IOException {
        assertEquals(
                List.of(true),
                writeAndLoadInterpolateFlags(
                        work,
                        "{" + "\"interpolateAlveoli\":true,"
                                + "\"alveoli\":[{\"interpolateDescriptors\":true,\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]"
                                + "}"));
        assertEquals(
                List.of(true),
                writeAndLoadInterpolateFlags(
                        work,
                        "{" + "\"interpolateAlveoli\":false,"
                                + "\"alveoli\":[{\"interpolateDescriptors\":true,\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]"
                                + "}"));
        assertEquals(
                List.of(false),
                writeAndLoadInterpolateFlags(
                        work,
                        "{" + "\"interpolateAlveoli\":false,"
                                + "\"alveoli\":[{\"interpolateDescriptors\":false,\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]"
                                + "}"));
        assertEquals(
                List.of(false),
                writeAndLoadInterpolateFlags(
                        work,
                        "{" + "\"interpolateAlveoli\":true,"
                                + "\"alveoli\":[{\"interpolateDescriptors\":false,\"name\":\"main\",\"descriptors\":[{\"name\":\"test\"}]}]"
                                + "}"));
    }

    @Test
    void referencesFolder(@TempDir final Path work) throws IOException {
        final var bundlebee = Files.createDirectories(work.resolve("bundlebee"));
        final var main = Files.writeString(
                bundlebee.resolve("manifest.json"),
                "{" + "\"references\":[{\"path\":\"ref1.json\"}]," + "\"alveoli\":[{\"name\":\"main\"}]" + "}");
        Files.writeString(bundlebee.resolve("ref1.json"), "{" + "\"alveoli\":[{\"name\":\"ref1-alveolus\"}]" + "}");
        final var manifest = reader.readManifest(
                null,
                () -> {
                    try {
                        return Files.newInputStream(main);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                },
                n -> {
                    try {
                        return Files.newInputStream(bundlebee.resolve(n));
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                },
                null);
        assertEquals(
                List.of("main", "ref1-alveolus"),
                manifest.alveoli().stream().map(Manifest.Alveolus::name).collect(toList()));
    }

    @Test
    void referencesJar(@TempDir final Path work) throws IOException {
        final var jar = work.resolve("module.jar");
        try (final var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("bundlebee/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("bundlebee/manifest.json"));
            out.write(("{" + "\"references\":[{\"path\":\"ref1.json\"}]," + "\"alveoli\":[{\"name\":\"main\"}]" + "}")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("bundlebee/ref1.json"));
            out.write(("{" + "\"alveoli\":[{\"name\":\"ref1-alveolus\"}]" + "}").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        // we cheat a bit to reuse the logic behind the loading
        final var archive = archiveReader.read("whatever", jar, null);
        final var manifest = archive.getManifest();
        assertEquals(
                List.of("main", "ref1-alveolus"),
                manifest.alveoli().stream().map(Manifest.Alveolus::name).collect(toList()));
    }

    @Test
    void read() {
        final var manifest = reader.readManifest(
                null,
                () -> new ByteArrayInputStream(("{" + "  \"alveoli\":[" + "    {" + "      \"name\": \"test\","
                                + "      \"descriptors\":[" + "        {" + "          \"name\": \"foo\","
                                + "          \"location\": \"com.company:alv:1.0.0\"" + "        }" + "      ]"
                                + "    }" + "  ]" + "}")
                        .getBytes(StandardCharsets.UTF_8)),
                null,
                null);
        assertManifest(manifest);
    }

    @Test
    void wrappedRead() {
        final var manifest = reader.readManifest(
                null,
                () -> new ByteArrayInputStream(("{\"bundlebee\":{" + "  \"alveoli\":[" + "    {"
                                + "      \"name\": \"test\"," + "      \"descriptors\":[" + "        {"
                                + "          \"name\": \"foo\"," + "          \"location\": \"com.company:alv:1.0.0\""
                                + "        }" + "      ]" + "    }" + "  ]" + "}}")
                        .getBytes(StandardCharsets.UTF_8)),
                null,
                null);
        assertManifest(manifest);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"null", "[]", "true", "42", "{\"bundlebee\":null}", "{\"bundlebee\":[]}", "{\"bundlebee\":true}"
            })
    void rejectsMalformedManifestShapes(final String json) {
        assertThrows(
                IllegalStateException.class,
                () -> reader.readManifest(
                        null, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), null, null));
    }

    private void assertManifest(final Manifest manifest) {
        assertEquals(1, manifest.alveoli().size());
        final var alveolus = manifest.alveoli().iterator().next();
        assertEquals("test", alveolus.name());
        assertEquals(1, alveolus.descriptors().size());
        final var descriptor = alveolus.descriptors().get(0);
        assertEquals("kubernetes", descriptor.type());
        assertEquals("foo", descriptor.name());
        assertEquals("com.company:alv:1.0.0", descriptor.location());
    }

    private List<Boolean> writeAndLoadInterpolateFlags(final Path work, final String content) throws IOException {
        return doRead(Files.writeString(
                        Files.createDirectories(work.resolve("bundlebee")).resolve("manifest.json"), content))
                .alveoli()
                .stream()
                .map(Manifest.Alveolus::descriptors)
                .flatMap(Collection::stream)
                .map(Manifest.Descriptor::interpolate)
                .collect(toList());
    }

    private Manifest doRead(final Path main) {
        return reader.readManifest(
                null,
                () -> {
                    try {
                        return Files.newInputStream(main);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                },
                null,
                null);
    }
}
