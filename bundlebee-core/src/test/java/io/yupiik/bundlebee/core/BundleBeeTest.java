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
package io.yupiik.bundlebee.core;

import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.json.Json;
import java.util.stream.Stream;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleBeeTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void help(final CommandExecutor executor) {
        final var noArg = executor.wrap(INFO, () -> new BundleBee().launch());
        final var explicitHelp = executor.wrap(INFO, () -> new BundleBee().launch("help"));
        final var missingCommand = executor.wrap(INFO, () -> new BundleBee().launch("missing"));
        Stream.of(noArg, explicitHelp, missingCommand)
                .forEach(content -> {
                    assertTrue(content.contains("  - help: print help.\n"), content);
                });

        // missing command has an additional error message
        assertTrue(missingCommand.contains("No command found for args: [missing]\n"), missingCommand);
    }
}
