/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidateAlveolusTest {
    @ValidateAlveolus("ValidateAlveolusTest.simple")
    void simple() {
    }

    @ValidateAlveolus("ValidateAlveolusTest.simple")
    void simpleWithLogCheck(final CommandExecutor executor, final LogCapturer logCapturer) {
        executor.run();

        // no manifest matching was found but apply installed all the available ones from the classpath (which is what was requested with location=auto)
        final var all = logCapturer.all();
        assertEquals(1, all.size());
        assertEquals("" +
                        "Auto scanning the classpath, " +
                        "this can be dangerous if you don't fully control your classpath, " +
                        "ensure to set a particular alveolus if you doubt about this behavior",
                all.iterator().next().getMessage());
    }

    @ValidateAlveolus("ValidateAlveolusTest.simple")
    void simpleWithApiCheck(final CommandExecutor executor, final KubernetesApi kubernetesApi) {
        kubernetesApi.capture();
        executor.run();

        // no manifest matching was found but apply installed all the available ones from the classpath (which is what was requested with location=auto)
        final var all = kubernetesApi.captured();
        assertEquals(0, all.size());
    }
}
