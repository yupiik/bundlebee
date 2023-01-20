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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import lombok.Data;

import javax.enterprise.context.ApplicationScoped;

import static java.util.Optional.ofNullable;

@ApplicationScoped
public class VersioningService {
    public String findVersion(final Manifest.Alveolus alveolus) {
        return ofNullable(alveolus.getVersion())
                .filter(it -> !it.isBlank())
                .orElseGet(() -> {
                    final var segments = alveolus.getName().split(":");
                    if (segments.length >= 3) {
                        return segments[2];
                    }
                    throw new IllegalArgumentException("Can't extract the version from " + alveolus + ". " +
                            "Ensure to set the version \"version\":\"1.2.3\" or use the naming convention <group>:<artifact>:<version>");
                });
    }

    public SemanticVersion toSemanticVersion(final String version) {
        final var segments = version.split("\\.");
        if (segments.length < 2) {
            return invalid(version);
        }
        try {
            final int major = Integer.parseInt(segments[0]);
            final int minor = Integer.parseInt(segments[1]);
            final int patch = segments.length >= 3 ? Integer.parseInt(segments[2]) : 0;
            return new SemanticVersion(version, major, minor, patch, segments.length >= 3);
        } catch (final NumberFormatException nfe) {
            return invalid(version);
        }
    }

    private <T> T invalid(final String raw) {
        throw new IllegalArgumentException(raw + " is not a semver friendly version, " +
                "ensure to use <major:integer>.<minor:integer>[.<path:integer[..*]] pattern.");
    }

    @Data
    public static class SemanticVersion implements Comparable<SemanticVersion> {
        private final String raw;
        private final int major;
        private final int minor;
        private final int patch;
        private final boolean hasPatch;

        @Override
        public int compareTo(final SemanticVersion o) {
            final var major = this.major - o.getMajor();
            if (major != 0) {
                return major;
            }
            final var minor = this.minor - o.getMinor();
            if (minor != 0) {
                return minor;
            }
            if (o.isHasPatch() && hasPatch) {
                final var patch = this.patch - o.getPatch();
                if (patch != 0) {
                    return patch;
                }
            }
            if (o.isHasPatch()) {
                return -1;
            }
            if (hasPatch) {
                return 1;
            }
            return raw.compareTo(o.getRaw());
        }
    }
}
