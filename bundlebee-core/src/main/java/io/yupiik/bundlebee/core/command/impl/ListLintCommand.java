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

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.command.impl.lint.LintingCheck;
import lombok.extern.java.Log;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;

@Log
@Dependent
public class ListLintCommand implements Executable {
    @Inject
    @Any
    private Instance<LintingCheck> checks;

    @Override
    public String name() {
        return "list-lint-rules";
    }

    @Override
    public String description() {
        return "List available linting rules (ease exclusions for ex).";
    }

    @Override
    public CompletionStage<?> execute() {
        final var output = checks.stream()
                .map(c -> " *" + c.name())
                .collect(joining("\n"));
        log.info(output);
        return completedFuture(output);
    }
}
