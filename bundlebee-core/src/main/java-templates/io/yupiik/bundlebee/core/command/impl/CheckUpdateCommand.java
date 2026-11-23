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

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toSet;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "check-update",
        description = "Check if a new version is available.\n" + "// end of short description\n\n"
                + "IMPORTANT: this command only works for amd64 linux machines.")
public class CheckUpdateCommand implements Executable {
    private final Logger log = Logger.getLogger(CheckUpdateCommand.class.getName());

    public CheckUpdateCommand(final Options options, VersionCommand versionCommand, Maven maven) {
        this.versionCommand = versionCommand;
        this.maven = maven;
        this.repository = options.repository();
        this.groupId = options.groupId();
        this.artifactId = options.artifactId();
        this.classifier = options.classifier();
        this.type = options.type();
        this.installLocation = options.installLocation();
        this.update = options.update();
    }

    private final VersionCommand versionCommand;
    private final Maven maven;
    private final String repository;
    private final String groupId;
    private final String artifactId;
    private final String classifier;
    private final String type;
    private final String installLocation;
    private final boolean update;

    @Override
    public String name() {
        return "check-update";
    }

    @Override
    public String description() {
        return "Check if a new version is available.\n" + "// end of short description\n\n"
                + "IMPORTANT: this command only works for amd64 linux machines.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var version = versionCommand.execute();
        final var latest = maven.findAvailableVersions(repository, groupId, artifactId)
                .thenApply(v -> ofNullable(v.getLatest())
                        .or(() -> ofNullable(v.getRelease()))
                        .orElseGet(() -> v.getVersions().isEmpty()
                                ? null
                                : v.getVersions().get(v.getVersions().size() - 1)));
        return version.thenCompose(current -> latest.thenCompose(last -> {
            if (last != null && last.equals(current)) {
                log.info(() -> "You are already using last release: " + last);
                return completedFuture(current);
            }
            log.info(() -> "A new version is available: " + last);
            if (!update) {
                log.info(() -> "You can download it with this link: "
                        + maven.toRelativePath(repository, groupId, artifactId, last, "-Linux-amd64", "bin", last));
                return completedFuture(last);
            }
            final var home = System.getProperty("user.home", ".");
            final var target = Paths.get(
                    /*writtten this way to abuse the filtering + backward compat*/
                    installLocation.replace("$" + "{user.home}", home).replace("{{user.home}}", home));
            if (Files.exists(target.getParent())) {
                try {
                    Files.createDirectories(target.getParent());
                } catch (final IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            if (Files.exists(target)) {
                final var backup =
                        target.getParent().resolve(target.getFileName().toString() + "." + current);
                log.info(() -> "Saving current version at \'" + backup + "\'");
                try {
                    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    Files.list(target.getParent())
                            .filter(p -> p.getFileName()
                                    .toString()
                                    .startsWith(target.getFileName().toString() + '.'))
                            .filter(p -> !Objects.equals(p, backup))
                            .forEach(previous -> {
                                try {
                                    log.info(() -> "Deleting outdated version: \'" + previous + "\'");
                                    Files.delete(previous);
                                } catch (final IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            log.info(() -> "Downloading new version...");
            return maven.doDownload(
                            URI.create(maven.toRelativePath(
                                    repository,
                                    groupId,
                                    artifactId,
                                    last,
                                    !classifier.isEmpty() ? '-' + classifier : "",
                                    type,
                                    last)),
                            target)
                    .thenApply(exec -> {
                        try {
                            final var perms = Files.getPosixFilePermissions(exec);
                            if (!perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
                                Files.setPosixFilePermissions(
                                        exec,
                                        Stream.concat(perms.stream(), Stream.of(PosixFilePermission.OWNER_EXECUTE))
                                                .collect(toSet()));
                            }
                        } catch (final IOException ioe) {
                            throw new IllegalStateException(ioe);
                        }
                        return last;
                    });
        }));
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.check-update.repository",
                    defaultValue = "\"https://repo.maven.apache.org/maven2\"",
                    documentation = "Repository bundlebee is supposed to be, generally Apache Maven central.")
            String repository,

            @Property(
                    value = "bundlebee.check-update.groupId",
                    defaultValue = "\"${project.groupId}\"",
                    documentation = "Bundlebee binary groupId.")
            String groupId,

            @Property(
                    value = "bundlebee.check-update.artifactId",
                    defaultValue = "\"${project.artifactId}\"",
                    documentation = "Bundlebee binary artifactId.")
            String artifactId,

            @Property(
                    value = "bundlebee.check-update.classifier",
                    defaultValue = "\"Linux-amd64\"",
                    documentation = "Bundlebee binary classifier.")
            String classifier,

            @Property(
                    value = "bundlebee.check-update.type",
                    defaultValue = "\"bin\"",
                    documentation = "Bundlebee binary type (extension) in maven coordinates.")
            String type,

            @Property(
                    value = "bundlebee.check-update.installLocation",
                    defaultValue = "\"{{user.home}}/.yupiik/bundlebee/bin/bundlebee\"",
                    documentation = "Where to install bundlebee if `update` is `true`.")
            String installLocation,

            @Property(
                    value = "bundlebee.check-update.update",
                    defaultValue = "false",
                    documentation = "If a new version is available, should it update `installLocation`.")
            boolean update) {}
}
