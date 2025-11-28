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
import javax.json.JsonString;
import java.util.stream.Stream;

import static javax.json.JsonValue.ValueType.STRING;

@Dependent
public class DropNetRawCapability extends ContainerValueValidator {
    @Override
    public String name() {
        return "drop-net-raw-capability";
    }

    @Override
    public String description() {
        return "Indicates when containers do not drop NET_RAW capability";
    }

    @Override
    public String remediation() {
        return "`NET_RAW` makes it so that an application within the container is able to craft raw packets, " +
                "use raw sockets, and bind to any address. Remove this capability in the containers under " +
                "containers security contexts.";
    }

    @Override
    protected boolean supportsInitContainers() {
        return true;
    }

    @Override
    protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
        final var securityContext = container.getJsonObject("securityContext");
        if (securityContext == null) {
            return Stream.empty();
        }
        final var capabilities = securityContext.getJsonObject("capabilities");
        if (capabilities == null) {
            return Stream.empty();
        }
        final var add = capabilities.getJsonArray("add");
        if (add == null) {
            return Stream.empty();
        }
        return add.stream()
                .filter(it -> it.getValueType() == STRING)
                .map(it -> (JsonString) it)
                .map(JsonString::getString)
                .filter("NET_RAW"::equals)
                .map(it -> new LintError(LintError.LintLevel.ERROR, "'NET_RAW' capabilities usage"));
    }
}
