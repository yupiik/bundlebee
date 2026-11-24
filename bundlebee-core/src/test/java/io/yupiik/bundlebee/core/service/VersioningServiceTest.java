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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

@FusionSupport
class VersioningServiceTest {

    @Fusion
    private VersioningService service;

    @Test
    void invalidToSemVer() {
        assertThrows(IllegalArgumentException.class, () -> service.toSemanticVersion("a.2.3"));
        assertThrows(IllegalArgumentException.class, () -> service.toSemanticVersion("a.2"));
        assertThrows(IllegalArgumentException.class, () -> service.toSemanticVersion("1."));
        assertThrows(IllegalArgumentException.class, () -> service.toSemanticVersion("1"));
        assertThrows(IllegalArgumentException.class, () -> service.toSemanticVersion("wrong"));
    }

    @Test
    void toSemVerThreeDigits() {
        final var semanticVersion = service.toSemanticVersion("1.2.3");
        assertEquals(1, semanticVersion.getMajor());
        assertEquals(2, semanticVersion.getMinor());
        assertEquals(3, semanticVersion.getPatch());
        assertTrue(semanticVersion.isHasPatch());
    }

    @Test
    void toSemVerTwoDigits() {
        final var semanticVersion = service.toSemanticVersion("1.2");
        assertEquals(1, semanticVersion.getMajor());
        assertEquals(2, semanticVersion.getMinor());
        assertEquals(0, semanticVersion.getPatch());
        assertFalse(semanticVersion.isHasPatch());
    }

    @Test
    void findVersionFromVersion() {
        final var alveolus = new Manifest.Alveolus(null, null, "1.2.3", null, null, false, null, null, null);
        assertEquals("1.2.3", service.findVersion(alveolus));
    }

    @Test
    void findVersionFromName() {
        final var alveolus = new Manifest.Alveolus(null, "a:b:1.2.3", null, null, null, false, null, null, null);
        assertEquals("1.2.3", service.findVersion(alveolus));
    }

    @Test
    void findVersionFromNameWithClassifier() {
        final var alveolus =
                new Manifest.Alveolus(null, "a:b:1.2.3:jar:bundlebee", null, null, null, false, null, null, null);
        assertEquals("1.2.3", service.findVersion(alveolus));
    }

    @Test
    void missingVersion() {
        final var alveolus = new Manifest.Alveolus(null, "a:b", null, null, null, false, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.findVersion(alveolus));
    }

    @Test
    void trailingNameSeparators() {
        final var versioned = new Manifest.Alveolus(null, "a:b:1.2.3::", null, null, null, false, null, null, null);
        assertEquals("1.2.3", service.findVersion(versioned));
        final var unversioned = new Manifest.Alveolus(null, "a:b::", null, null, null, false, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.findVersion(unversioned));
    }
}
