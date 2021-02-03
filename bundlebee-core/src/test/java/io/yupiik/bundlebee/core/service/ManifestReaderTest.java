package io.yupiik.bundlebee.core.service;

import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Cdi
class ManifestReaderTest {
    @Inject
    private ManifestReader reader;

    @Test
    void read() {
        final var manifest = reader.readManifest(() -> new ByteArrayInputStream(("{" +
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
                "}").getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, manifest.getAlveoli().size());

        final var alveolus = manifest.getAlveoli().iterator().next();
        assertEquals("test", alveolus.getName());
        assertEquals(1, alveolus.getDescriptors().size());

        final var descriptor = alveolus.getDescriptors().get(0);
        assertEquals("kubernetes", descriptor.getType());
        assertEquals("foo", descriptor.getName());
        assertEquals("com.company:alv:1.0.0", descriptor.getLocation());
    }
}
