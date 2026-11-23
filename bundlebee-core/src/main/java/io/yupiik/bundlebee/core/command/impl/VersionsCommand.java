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
package io.yupiik.bundlebee.core.command.impl;

import static java.util.stream.Collectors.joining;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.bundlebee.core.service.VersioningService;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.util.Comparator;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

@DefaultScoped
@Command(name = "versions", description = "List versions for an artifact to know which ones are available.")
public class VersionsCommand implements Executable {
    private final Logger log = Logger.getLogger(VersionsCommand.class.getName());

    public VersionsCommand(final Options options, Maven resolver, VersioningService versioningService) {
        this.resolver = resolver;
        this.versioningService = versioningService;
        this.group = options.group();
        this.artifact = options.artifact();
    }

    private final Maven resolver;
    private final VersioningService versioningService;
    private final String group;
    private final String artifact;

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
        return resolver.findAvailableVersions(group, artifact).thenApply(versions -> {
            versions.sort(compareVersions().reversed());
            log.info("Available version:\n"
                    + versions.stream().map(it -> "- " + it).collect(joining("\n")));
            return versions;
        });
    }

    private Comparator<String> compareVersions() {
        return (a, b) -> {
            try {
                return versioningService.toSemanticVersion(a).compareTo(versioningService.toSemanticVersion(b));
            } catch (final IllegalArgumentException iae) {
                return a.compareTo(b);
            }
        };
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.versions.group",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle groupId.")
            String group,

            @Property(
                    value = "bundlebee.versions.artifact",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle artifactId.")
            String artifact) {}
}
