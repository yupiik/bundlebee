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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.regex.Pattern;

@ApplicationScoped
public class RequirementService {
    private static final Pattern VERSION_SEPARATOR = Pattern.compile("\\.");

    public void checkRequirements(final Manifest manifest) {
        if (manifest.requirements() == null) {
            return;
        }
        manifest.requirements().forEach(this::check);
    }

    private void check(final Manifest.Requirement requirement) {
        if (requirement.minBundlebeeVersion() != null
                && !requirement.minBundlebeeVersion().isBlank()
                && !compareVersion(requirement.minBundlebeeVersion(), true)) {
            throw new IllegalArgumentException("Invalid bundlebee version: " + getBundlebeeVersion() + " expected-min="
                    + requirement.minBundlebeeVersion());
        }
        if (requirement.maxBundlebeeVersion() != null
                && !requirement.maxBundlebeeVersion().isBlank()
                && !compareVersion(requirement.maxBundlebeeVersion(), false)) {
            throw new IllegalArgumentException("Invalid bundlebee version: " + getBundlebeeVersion() + " expected-max="
                    + requirement.maxBundlebeeVersion());
        }
        if (requirement.forbiddenVersions() != null) {
            requirement.forbiddenVersions().forEach(version -> {
                if (compareVersion(version, null)) {
                    throw new IllegalArgumentException("Invalid bundlebee version: " + getBundlebeeVersion()
                            + " forbidden=" + requirement.forbiddenVersions());
                }
            });
        }
    }

    protected String getBundlebeeVersion() {
        return Constants.VERSION;
    }

    private String sanitize(final String version) {
        if (version.endsWith("-SNAPSHOT")) {
            return version.substring(0, version.length() - "-SNAPSHOT".length());
        }
        return version;
    }

    private boolean compareVersion(final String version, final Boolean expectNegative) {
        final var expected = sanitize(version);
        final var actual = sanitize(getBundlebeeVersion());
        final var expectedSegments = VERSION_SEPARATOR.split(expected);
        final var actualSegments = VERSION_SEPARATOR.split(actual);
        final int segmentLoopLength = Math.min(expectedSegments.length, actualSegments.length);
        for (int i = 0; i < segmentLoopLength; i++) {
            final var exp = expectedSegments[i];
            if ("*".equals(exp)) {
                // TODO: suffix instead of the whole value? it is harder to configure then
                continue;
            }
            final var act = actualSegments[i];
            if (exp.equals(act)) {
                continue;
            }
            try {
                final int expInt = Integer.parseInt(exp);
                final int actInt = Integer.parseInt(act);
                final int comp = expInt - actInt;
                if (expectNegative == null && comp != 0) {
                    return false;
                }
                if (comp != 0) {
                    return expectNegative ? comp < 0 : comp > 0;
                }
            } catch (final NumberFormatException nfe) {
                return false;
            }
        }
        return // assume wildcard so it matches for the missing segment
        expectedSegments.length < actualSegments.length || expectedSegments.length == actualSegments.length;
    }
}
