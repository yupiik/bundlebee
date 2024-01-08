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
package io.yupiik.bundlebee.core.command.impl.lint;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.completedStage;

public interface SynchronousLintingCheck extends LintingCheck {
    boolean accept(LintableDescriptor descriptor);

    @Override
    default CompletionStage<Stream<LintError>> validate(LintableDescriptor descriptor) {
        try {
            return completedStage(validateSync(descriptor));
        } catch (final RuntimeException re) {
            final var future = new CompletableFuture<Stream<LintError>>();
            future.completeExceptionally(re);
            return future;
        }
    }

    Stream<LintError> validateSync(LintableDescriptor descriptor);

    @Override
    default CompletionStage<Stream<ContextualLintError>> afterAll() {
        try {
            return completedStage(afterAllSync());
        } catch (final RuntimeException re) {
            final var future = new CompletableFuture<Stream<ContextualLintError>>();
            future.completeExceptionally(re);
            return future;
        }
    }

    default Stream<ContextualLintError> afterAllSync() {
        return Stream.empty();
    }
}
