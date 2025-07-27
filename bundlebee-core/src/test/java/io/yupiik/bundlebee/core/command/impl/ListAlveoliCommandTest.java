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

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ListAlveoliCommandTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void list(final CommandExecutor executor) {
        final var logs = executor.wrap(null, INFO, () -> new BundleBee().launch("list-alveoli"));
        assertEquals("Found alveoli:\n" +
                "- ApplyCommandTest.apply\n" +
                "- ApplyCommandTest.applyAwait\n" +
                "- ApplyCommandTest.applyAwaitCondition\n" +
                "- ApplyCommandTest.applyAwaitConditionFailure\n" +
                "- ApplyCommandTest.fromTemplate\n" +
                "- ApplyCommandTest.includeIfPatch\n" +
                "- ApplyCommandTest.simpleNestedDependencyWithReusingTheTemplate\n" +
                "- ApplyCommandTest.template\n" +
                "- ApplyCommandTest.withdep\n" +
                "- ApplyCommandTest.withexclude\n" +
                "- ApplyCommandTest.withsamedep\n" +
                "- DeleteCommandTest.deleteMaven\n" +
                "- RollbackCommandTest.first\n" +
                "- RollbackCommandTest.second\n" +
                "- customContentType\n" +
                "- handlebars\n" +
                "\n", logs);
    }
}
