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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InspectCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void inspect(final CommandExecutor executor) {
        final var logs = executor.wrap(INFO, () -> new BundleBee().launch(
                "inspect", "--alveolus", "ApplyCommandTest.withdep"));
        assertEquals("" +
                "Inspection Report for alveolus=ApplyCommandTest.withdep\n" +
                "\n" +
                "* Alveolus 'ApplyCommandTest.apply'\n" +
                "  > Descriptor 'ApplyCommandTest.d1'\n" +
                "\n" +
                "* Alveolus 'ApplyCommandTest.withdep'\n" +
                "  > Descriptor 'ApplyCommandTest.d2'\n" +
                "  - Dependency 'ApplyCommandTest.apply'\n" +
                "", logs);
    }

    @Test
    void inspectVerbose(final CommandExecutor executor) {
        final var logs = executor.wrap(INFO, () -> new BundleBee().launch(
                "inspect", "--alveolus", "ApplyCommandTest.withdep", "--verbose", "true"));
        assertEquals("" +
                "Inspection Report for alveolus=ApplyCommandTest.withdep\n" +
                "\n" +
                "* Alveolus 'ApplyCommandTest.apply'\n" +
                "  > Descriptor 'ApplyCommandTest.d1'\n" +
                "    apiVersion: v1\n" +
                "    kind: Service\n" +
                "    metadata:\n" +
                "      name: s\n" +
                "      labels:\n" +
                "        app: s-test\n" +
                "    spec:\n" +
                "      type: NodePort\n" +
                "      ports:\n" +
                "        - port: 1234\n" +
                "          targetPort: 1234\n" +
                "      selector:\n" +
                "        app: s-test\n" +
                "\n" +
                "* Alveolus 'ApplyCommandTest.withdep'\n" +
                "  > Descriptor 'ApplyCommandTest.d2'\n" +
                "    apiVersion: v1\n" +
                "    kind: Service\n" +
                "    metadata:\n" +
                "      name: s2\n" +
                "      labels:\n" +
                "        app: s-test\n" +
                "    spec:\n" +
                "      type: NodePort\n" +
                "      ports:\n" +
                "        - port: 1235\n" +
                "          targetPort: 1235\n" +
                "      selector:\n" +
                "        app: s-test\n" +
                "  - Dependency 'ApplyCommandTest.apply'\n" +
                "", logs);
    }
}
