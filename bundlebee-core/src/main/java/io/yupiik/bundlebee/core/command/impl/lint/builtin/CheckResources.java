/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import javax.json.JsonNumber;
import javax.json.JsonObject;
import java.util.stream.Stream;

public abstract class CheckResources extends ContainerValueValidator {
    private final String resource;
    private final String type;

    public CheckResources(final String type, final String resource) {
        this.type = type;
        this.resource = resource;
    }

    @Override
    public String name() {
        return resource + "-" + type;
    }

    @Override
    public String description() {
        return "Ensures resources." + type + "." + resource + " is set.";
    }

    @Override
    public String remediation() {
        return "Set resources." + type + "." + resource + ".";
    }

    @Override
    protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
        final var resources = container.getJsonObject("resources");
        if (resources == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No resources element in container"));
        }
        final var type = resources.getJsonObject(this.type);
        if (type == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No " + this.type + " element in container resources"));
        }
        final var resource = type.get(this.resource);
        if (resource == null) {
            return Stream.of(new LintError(LintError.LintLevel.ERROR, "No " + this.resource + " resource in container " + this.type + " resources"));
        }
        switch (resource.getValueType()) {
            case NUMBER:
                if (((JsonNumber) resource).intValue() <= 0) {
                    return Stream.of(new LintError(LintError.LintLevel.ERROR, "Zero set as " + this.resource + " resource in container " + type + "s resources"));
                }
                return Stream.empty();
            // todo: string parsing to avoid <=0
            default:
                return Stream.empty();
        }
    }
}
