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

import javax.enterprise.context.Dependent;
import javax.json.JsonObject;
import java.util.stream.Stream;

@Dependent
public class NoLatestImage extends ContainerValueValidator {
    @Override
    public String name() {
        return "no-latest";
    }

    @Override
    public String description() {
        return "Ensures no latest image is used.";
    }

    @Override
    public String remediation() {
        return "Ensure to tag any image you use and use an immutable tag if possible or at least versionned flavor.";
    }

    @Override
    protected boolean supportsInitContainers() {
        return true;
    }

    @Override
    protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
        final var image = container.getString("image", "");
        final var tagStart = image.lastIndexOf(':');
        // note: we assume that latest+sha is ok-ish
        if (tagStart < 0 || "latest".equals(image.substring(tagStart + 1))) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "You shouldn't use latest tag since it is highly unstable: '" + image + "'"));
        }
        return Stream.empty();
    }
}
