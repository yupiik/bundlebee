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
import io.yupiik.bundlebee.core.command.impl.lint.SynchronousLintingCheck;

import javax.enterprise.context.Dependent;
import java.util.stream.Stream;

@Dependent
public class UseNamespace implements SynchronousLintingCheck {
    @Override
    public String name() {
        return "use-namespace";
    }

    @Override
    public String description() {
        return "Indicates when a resource is deployed to the default namespace.  \n" +
                "CIS Benchmark 5.7.1: Create administrative boundaries between resources using namespaces.\n" +
                "CIS Benchmark 5.7.4: The default namespace should not be used.";
    }

    @Override
    public String remediation() {
        return "Create namespaces for objects in your deployment.";
    }

    @Override
    public boolean accept(final LintableDescriptor descriptor) {
        return true;
    }

    @Override
    public Stream<LintError> validateSync(final LintableDescriptor descriptor) {
        final var metadata = descriptor.getDescriptor().getJsonObject("metadata");
        if (metadata == null) {
            return Stream.empty();
        }
        final var value = metadata.getString("namespace", "");
        return "default".equals(value) ?
                Stream.of(new LintError(LintError.LintLevel.ERROR, "'default' namespace is used")) :
                Stream.empty();
    }
}
