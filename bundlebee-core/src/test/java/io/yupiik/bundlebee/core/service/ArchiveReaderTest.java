package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Cdi
class ArchiveReaderTest {
    @Inject
    private ArchiveReader reader;

    @Test
    void read(@TempDir final Path dir) throws IOException {
        final var yaml = "apiVersion: v1\n" +
                "kind: Service\n" +
                "metadata:\n" +
                "  name: foo\n" +
                "  labels:\n" +
                "    app: foo\n" +
                "spec:\n" +
                "  type: NodePort\n" +
                "  ports:\n" +
                "   - port: 1234\n" +
                "     targetPort: 1234\n" +
                "  selector:\n" +
                "   app: foo\n";

        final var zip = dir.resolve("test.zip");
        try (final ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.CREATE))) {
            zipOutputStream.putNextEntry(new ZipEntry("bundlebee/"));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("bundlebee/kubernetes/"));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("bundlebee/manifest.json"));
            zipOutputStream.write(("{" +
                    "  \"alveoli\":[" +
                    "    {" +
                    "      \"name\": \"test\"," +
                    "      \"descriptors\":[" +
                    "        {" +
                    "          \"name\": \"foo\"," +
                    "          \"location\": \"com.company:alv:1.0.0\"" +
                    "        }" +
                    "      ]" +
                    "    }" +
                    "  ]" +
                    "}").getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("bundlebee/kubernetes/foo.yaml"));
            zipOutputStream.write(yaml.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
        final var archive = reader.read(zip);
        assertEquals(1, archive.getManifest().getAlveoli().size());

        final var alveolus = archive.getManifest().getAlveoli().get(0);
        assertEquals("test", alveolus.getName());
        assertEquals(1, alveolus.getDescriptors().size());

        final var descriptor = alveolus.getDescriptors().get(0);
        assertEquals("kubernetes", descriptor.getType());
        assertEquals("foo", descriptor.getName());
        assertEquals("com.company:alv:1.0.0", descriptor.getLocation());
        assertEquals(yaml.trim(), archive.getDescriptors().get("bundlebee/kubernetes/foo.yaml"));
    }
}
