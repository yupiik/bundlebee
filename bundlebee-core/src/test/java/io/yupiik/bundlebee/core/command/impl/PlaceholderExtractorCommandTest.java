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

import io.yupiik.bundlebee.core.BundleBee;
import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderExtractorCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void extract(final CommandExecutor executor) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(
                "placeholder-extract", "--alveolus", "ApplyCommandTest.fromTemplate"));
        assertEquals("" +
                "JSON\n" +
                "{\"items\":[{\"name\":\"ApplyCommandTest.fromTemplate.port\",\"description\":\"ApplyCommandTest.fromTemplate.port\",\"defaultValue\":\"9090\",\"required\":false,\"defaultValues\":[\"9090\"]},{\"name\":\"some.placeholder1\",\"description\":\"some.placeholder1\",\"defaultValue\":\"with defaultvalue\",\"required\":false,\"defaultValues\":[\"with defaultvalue\"]},{\"name\":\"some.placeholder2\",\"description\":\"some.placeholder2\",\"defaultValue\":\"with defaultvalue 2\",\"required\":false,\"defaultValues\":[\"with defaultvalue 2\"]}]}\n" +
                "\n" +
                "Sample\n" +
                "# HELP: ApplyCommandTest.fromTemplate.port\n" +
                "# ApplyCommandTest.fromTemplate.port = 9090\n" +
                "\n" +
                "# HELP: some.placeholder1\n" +
                "# some.placeholder1 = with defaultvalue\n" +
                "\n" +
                "# HELP: some.placeholder2\n" +
                "# some.placeholder2 = with defaultvalue 2\n" +
                "\n" +
                "Completion\n" +
                "ApplyCommandTest.fromTemplate.port = ApplyCommandTest.fromTemplate.port\n" +
                "some.placeholder1 = some.placeholder1\n" +
                "some.placeholder2 = some.placeholder2\n" +
                "\n" +
                "Doc\n" +
                "`ApplyCommandTest.fromTemplate.port`::\n" +
                "Default: `9090`.\n" +
                "\n" +
                "\n" +
                "`some.placeholder1`::\n" +
                "Default: `with defaultvalue`.\n" +
                "\n" +
                "\n" +
                "`some.placeholder2`::\n" +
                "Default: `with defaultvalue 2`.\n" +
                "\n" +
                "", logs);
    }

    @Test
    void extractHelmValues(final CommandExecutor executor) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(
                "placeholder-extract", "--alveolus", "ApplyCommandTest.helm"));
        assertTrue(logs.contains("\"name\":\"replicaCount\""), logs);
        assertTrue(logs.contains("\"defaultValue\":\"1\""), logs);
        assertTrue(logs.contains("\"name\":\"image.repository\""), logs);
        assertTrue(logs.contains("\"defaultValue\":\"nginx\""), logs);
        assertTrue(logs.contains("\"name\":\"image.tag\""), logs);
        assertTrue(logs.contains("\"name\":\"image.pullPolicy\""), logs);
        assertTrue(logs.contains("\"defaultValue\":\"IfNotPresent\""), logs);
    }

    @Test
    void helmValuesSeenByObserver(final CommandExecutor executor) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch(
                "placeholder-extract", "--alveolus", "ApplyCommandTest.helm"));

        // The OnPlaceholder event is fired via CDI Event<OnPlaceholder>.fire() for each helm value.yaml leaf.
        // The PlaceholderSpy (CDI @Observes) receives all events and forwards to registered consumers.
        // The command's JSON output is produced from the collector that consumes spy-forwarded events,
        // so its presence proves the CDI observer saw the helm leaf values.
        final var expectedLeafValues = List.of(
                "replicaCount", "image.repository", "image.tag", "image.pullPolicy");
        for (final var name : expectedLeafValues) {
            assertTrue(logs.contains("\"name\":\"" + name + "\""),
                    "CDI observer should see '" + name + "' in: " + logs);
        }
        assertTrue(logs.contains("\"defaultValue\":\"1\""), "replicaCount default=1");
        assertTrue(logs.contains("\"defaultValue\":\"nginx\""), "image.repository default=nginx");
        assertTrue(logs.contains("\"defaultValue\":\"1.19\""), "image.tag default=1.19");
        assertTrue(logs.contains("\"defaultValue\":\"IfNotPresent\""), "image.pullPolicy default=IfNotPresent");
    }
}
