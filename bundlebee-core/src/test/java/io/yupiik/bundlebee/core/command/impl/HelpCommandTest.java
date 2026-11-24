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
package io.yupiik.bundlebee.core.command.impl;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class // BundleBeeTest already test help command (since it is always there)
HelpCommandTest {

    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void authenticationFilesAreShownInSharedHelp(final CommandExecutor executor) {
        final var logs = executor.wrap(
                null,
                INFO,
                () -> new BundleBee()
                        .launch("help", "--command", "apply", "--kubeconfig", "/must-not-be-read-for-help"));
        assertTrue(logs.contains("--bundlebee.kube.tokenFile (default: none):"), logs);
        assertTrue(logs.contains("disables file authentication."), logs);
        assertTrue(logs.contains("--bundlebee.kube.certificates: optional CA certificate file or PEM"), logs);
        assertTrue(logs.contains("--bundlebee.kube.dryRun (default: false):"), logs);
        assertTrue(logs.contains("--bundlebee.kube.kubeconfig (default: auto):"), logs);
        assertTrue(logs.contains("--bundlebee.kube.fieldValidation (default: Strict):"), logs);
        assertFalse(logs.contains("    --kubeconfig"), logs);
        assertFalse(logs.contains("--bundlebee-kube-"), logs);
    }

    @Test
    void completionRemainsVisibleAndFilterable(final CommandExecutor executor) {
        final var all =
                executor.wrap(null, INFO, () -> new BundleBee().launch("help", "--shared", "false", "--args", "false"));
        assertTrue(all.contains("  [] completion:"), all);
        assertTrue(all.contains("  BundleBee is a light Kubernetes package manager."), all);
        assertTrue(all.contains(" Available commands:"), all);
        assertFalse(all.contains("// end of short description"), all);
        assertFalse(all.contains("complete -o default"), all);
        final var filtered = executor.wrap(
                null,
                INFO,
                () -> new BundleBee()
                        .launch("help", "--command", "completion", "--shared", "false", "--args", "false"));
        assertTrue(filtered.contains("  [] completion:"), filtered);
        assertFalse(filtered.contains("  [] completion/bash:"), filtered);
        assertFalse(filtered.contains("    --"), filtered);
    }

    @Test
    void generatedCommandsWithEmptyDescriptionsDoNotBreakHelp(final CommandExecutor executor) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch("help"));
        assertTrue(logs.contains("  [] completion/bash:"), logs);
        assertTrue(logs.contains("  [] version:"), logs);
    }

    @Test
    void helpOneCommand(final CommandExecutor executor) {
        final var logs = executor.wrap(
                null,
                INFO,
                () -> new BundleBee()
                        .launch("help", "--bundlebee-help-command", "version", "--bundlebee-help-shared", "false"));
        assertEquals("BundleBee\n\n  [] version: shows the application version.", logs.strip());
    }
}
