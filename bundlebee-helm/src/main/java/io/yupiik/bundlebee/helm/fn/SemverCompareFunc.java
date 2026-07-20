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

import io.yupiik.bundlebee.helm.HelmFunction;

import javax.enterprise.context.Dependent;

import io.yupiik.bundlebee.core.configuration.Description;

import java.util.Map;

@Dependent
//metadata:start
// category = Semver
//metadata:end
@Description("Compares a semantic version against a constraint")
public class SemverCompareFunc implements HelmFunction {
    @Override
    public String name() {
        return "semverCompare";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 2 || args[0] == null || args[1] == null) {
            return 0;
        }
        final var a = parseSemver(args[0].toString());
        final var b = parseSemver(args[1].toString());
        final var majorCmp = Integer.compare(a.get("major"), b.get("major"));
        if (majorCmp != 0) {
            return majorCmp;
        }
        final var minorCmp = Integer.compare(a.get("minor"), b.get("minor"));
        if (minorCmp != 0) {
            return minorCmp;
        }
        return Integer.compare(a.get("patch"), b.get("patch"));
    }

    private Map<String, Integer> parseSemver(final String version) {
        var v = version;
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        final var parts = v.split("\\.");
        if (parts.length < 3) {
            throw new IllegalArgumentException("invalid semver string: " + version);
        }
        return Map.of(
                "major", Integer.parseInt(parts[0]),
                "minor", Integer.parseInt(parts[1]),
                "patch", Integer.parseInt(parts[2]));
    }
}
