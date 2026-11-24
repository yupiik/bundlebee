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
package io.yupiik.bundlebee.helm.fn;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.helm.HelmFunction;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Map;
import java.util.regex.Pattern;

@DefaultScoped
// metadata:start
// category = Semver
// metadata:end
@Description("Parses a semantic version string")
public class SemverFunc implements HelmFunction {
    private static final Pattern VERSION_SEPARATOR = Pattern.compile("\\.");

    @Override
    public String name() {
        return "semver";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1 || args[0] == null) {
            return Map.of();
        }
        var version = args[0].toString();
        if (version.startsWith("v")) {
            version = version.substring(1);
        }
        final var parts = VERSION_SEPARATOR.split(version);
        if (parts.length < 3) {
            throw new IllegalArgumentException("invalid semver string: " + args[0]);
        }
        return Map.of(
                "major", Integer.parseInt(parts[0]),
                "minor", Integer.parseInt(parts[1]),
                "patch", Integer.parseInt(parts[2]));
    }
}
