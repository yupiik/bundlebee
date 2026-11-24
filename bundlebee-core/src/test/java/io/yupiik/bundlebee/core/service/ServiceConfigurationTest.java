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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.test.BaseTestModule;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = ServiceConfigurationTest.TestConfigurationModule.class)
class ServiceConfigurationTest {
    @Test
    void generatedTypedConfiguration(
            @Fusion final MavenConfiguration maven, @Fusion final AwaiterConfiguration awaiter) {
        assertEquals(1234, maven.timeout());
        assertEquals("auto", maven.cache());
        assertTrue(maven.preferCustomSettingsXml());
        assertFalse(maven.forceCustomSettingsXml());
        assertTrue(maven.downloadsEnabled());
        assertEquals("https://example.invalid/releases", maven.releaseRepository());
        assertEquals(UNSET, maven.snapshotRepository());
        assertEquals(UNSET, maven.httpHeaders());
        assertEquals(25, awaiter.retryInterval());
    }

    public static class TestConfigurationModule extends BaseTestModule {
        public TestConfigurationModule() {
            super(Map.of(
                    "bundlebee.kube.skipLoadConfig", "true",
                    "bundlebee.maven.http.timeout", "1234",
                    "bundlebee.maven.repositories.downloads.enabled", "true",
                    "bundlebee.maven.repositories.release", "https://example.invalid/releases",
                    "bundlebee.awaiter.retryInterval", "25"));
        }
    }
}
