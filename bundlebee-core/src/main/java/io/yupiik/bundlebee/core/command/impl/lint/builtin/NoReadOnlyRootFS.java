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

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Map;
import java.util.stream.Stream;

@DefaultScoped
public class NoReadOnlyRootFS extends ContainerValueValidator {

    @Override
    public String name() {
        return "no-read-only-root-fs";
    }

    @Override
    public String description() {
        return "Indicates when containers are running without a read-only root filesystem.";
    }

    @Override
    public String remediation() {
        return "Set readOnlyRootFilesystem to true in the container securityContext.";
    }

    @Override
    protected boolean supportsInitContainers() {
        return true;
    }

    @Override
    protected Stream<LintError> validate(final Map<?, ?> container, final LintableDescriptor descriptor) {
        if (!(container.get("securityContext") instanceof Map<?, ?> securityContext)) {
            return Stream.empty();
        }
        if ((securityContext.get("readOnlyRootFilesystem") instanceof Boolean readOnlyRootFilesystemValue
                ? readOnlyRootFilesystemValue
                : true)) {
            return Stream.empty();
        }
        return Stream.of(new LintError(LintError.LintLevel.ERROR, "'readOnlyRootFilesystem' set to true"));
    }
}
