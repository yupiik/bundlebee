/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.bundlebee.core.service.VersioningService;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.util.Comparator;
import java.util.concurrent.CompletionStage;

import static java.util.stream.Collectors.joining;

@Log
@Dependent
public class VersionsCommand implements Executable {
    @Inject
    private Maven resolver;

    @Inject
    private VersioningService versioningService;

    @Inject
    @Description("Bundle groupId.")
    @ConfigProperty(name = "bundlebee.versions.group", defaultValue = UNSET)
    private String group;

    @Inject
    @Description("Bundle artifactId.")
    @ConfigProperty(name = "bundlebee.versions.artifact", defaultValue = UNSET)
    private String artifact;

    @Override
    public String name() {
        return "versions";
    }

    @Override
    public String description() {
        return "List versions for an artifact to know which ones are available.";
    }

    @Override
    public CompletionStage<?> execute() {
        if (UNSET.equals(group) || UNSET.equals(artifact)) {
            throw new IllegalArgumentException("Ensure to set group and artifact for command versions");
        }
        return resolver.findAvailableVersions(group, artifact)
                .thenApply(versions -> {
                    versions.sort(compareVersions().reversed());
                    log.info("Available version:\n" + versions.stream()
                            .map(it -> "- " + it)
                            .collect(joining("\n")));
                    return versions;
                });
    }

    private Comparator<String> compareVersions() {
        return (a, b) -> {
            try {
                return versioningService.toSemanticVersion(a)
                        .compareTo(versioningService.toSemanticVersion(b));
            } catch (final IllegalArgumentException iae) {
                return a.compareTo(b);
            }
        };
    }
}
