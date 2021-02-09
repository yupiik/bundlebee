/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Cdi
class MavenTest {
    @Inject
    private Maven resolver;

    @Test
    void resolveLocal() throws ExecutionException, InterruptedException {
        assertTrue(Files.exists(resolver.findOrDownload("org.apache.geronimo.specs:geronimo-jcdi_2.0_spec:1.3")
                .toCompletableFuture().get()));
    }
}
