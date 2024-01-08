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
package io.yupiik.bundlebee.core.command.impl.lint.builtin;

import io.yupiik.bundlebee.core.command.impl.lint.SynchronousLintingCheck;
import lombok.RequiredArgsConstructor;

import java.util.Set;
import java.util.logging.Logger;

import static lombok.AccessLevel.PROTECTED;

@RequiredArgsConstructor(access = PROTECTED)
public abstract class CheckByKind implements SynchronousLintingCheck {
    private final Set<String> supportedKinds;

    @Override
    public boolean accept(final LintableDescriptor descriptor) {
        try {
            return supportedKinds.contains(descriptor.kind());
        } catch (final RuntimeException re) {
            return false;
        }
    }

    protected Logger lazyLogger() {
        return Logger.getLogger(getClass().getName());
    }
}
