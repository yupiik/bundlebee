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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
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
        final var manifest = reader.readManifest(null, () -> new ByteArrayInputStream(("{" +
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
        assertManifest(manifest);
    }

    @Test
    void wrappedRead() {
        final var manifest = reader.readManifest(null, () -> new ByteArrayInputStream(("{\"bundlebee\":{" +
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
                "}}").getBytes(StandardCharsets.UTF_8)));
        assertManifest(manifest);
    }

    private void assertManifest(final Manifest manifest) {
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
