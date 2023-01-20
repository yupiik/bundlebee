/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import javax.inject.Inject;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SetSystemProperty implements BeforeAllCallback, AfterAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        System.setProperty("bundlebee.maven.cache", System.getProperty("m2.location", "auto"));
        System.setProperty("bundlebee.maven.repositories.downloads.enabled", "true");
        System.setProperty("bundlebee.maven.repositories.snapshot", "https://oss.sonatype.org/content/repositories/snapshots/");
    }

    @Override
    public void afterAll(final ExtensionContext context) {
        System.clearProperty("bundlebee.maven.cache");
    }
}

@ExtendWith(SetSystemProperty.class)
@Cdi
class MavenTest {
    @Inject
    private Maven resolver;

    @Test
    @Disabled("only for manual test with oss sonatype snapshot (when they change certificates for ex)")
    void resolveRemote() throws ExecutionException, InterruptedException {
        System.out.println(resolver.findOrDownload("io.yupiik.alveoli:postgres-local:1.0.12-SNAPSHOT").toCompletableFuture().get());
    }

    @Test
    void resolveLocal() throws ExecutionException, InterruptedException {
        assertTrue(Files.exists(resolver.findOrDownload("org.apache.geronimo.specs:geronimo-jcdi_2.0_spec:1.3")
                .toCompletableFuture().get()));
    }
}
