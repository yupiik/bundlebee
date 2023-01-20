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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.service.Maven;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toSet;

@Log
@Dependent
public class CheckUpdateCommand implements Executable {
    @Inject
    private VersionCommand versionCommand;

    @Inject
    private Maven maven;

    @Inject
    @Description("Repository bundlebee is supposed to be, generally Apache Maven central.")
    @ConfigProperty(name = "bundlebee.check-update.repository", defaultValue = "https://repo.maven.apache.org/maven2")
    private String repository;

    @Inject
    @Description("Bundlebee binary groupId.")
    @ConfigProperty(name = "bundlebee.check-update.groupId", defaultValue = "${project.groupId}")
    private String groupId;

    @Inject
    @Description("Bundlebee binary artifactId.")
    @ConfigProperty(name = "bundlebee.check-update.artifactId", defaultValue = "${project.artifactId}")
    private String artifactId;

    @Inject
    @Description("Bundlebee binary classifier.")
    @ConfigProperty(name = "bundlebee.check-update.classifier", defaultValue = "Linux-amd64")
    private String classifier;

    @Inject
    @Description("Bundlebee binary type (extension) in maven coordinates.")
    @ConfigProperty(name = "bundlebee.check-update.type", defaultValue = "bin")
    private String type;

    @Inject
    @Description("Where to install bundlebee if `update` is `true`.")
    @ConfigProperty(name = "bundlebee.check-update.installLocation", defaultValue = "{{user.home}}/.yupiik/bundlebee/bin/bundlebee")
    private String installLocation;

    @Inject
    @Description("If a new version is available, should it update `installLocation`.")
    @ConfigProperty(name = "bundlebee.check-update.update", defaultValue = "false")
    private boolean update;

    @Override
    public String name() {
        return "check-update";
    }

    @Override
    public String description() {
        return "Check if a new version is available.\n" +
                "// end of short description\n\n" +
                "IMPORTANT: this command only works for amd64 linux machines.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var version = versionCommand.execute();
        final var latest = maven.findAvailableVersions(repository, groupId, artifactId)
                .thenApply(v -> ofNullable(v.getLatest())
                        .or(() -> ofNullable(v.getRelease()))
                        .orElseGet(() -> v.getVersions().isEmpty() ? null : v.getVersions().get(v.getVersions().size() - 1)));
        return version.thenCompose(current -> latest.thenCompose(last -> {
            if (last != null && last.equals(current)) {
                log.info(() -> "You are already using last release: " + last);
                return completedFuture(current);
            }
            log.info(() -> "A new version is available: " + last);
            if (!update) {
                log.info(() -> "You can download it with this link: " + maven.toRelativePath(repository, groupId, artifactId, last, "-Linux-amd64", "bin", last));
                return completedFuture(last);
            }
            final var home = System.getProperty("user.home", ".");
            final var target = Paths.get(installLocation
                    .replace("$" + "{user.home}" /*writtten this way to abuse the filtering + backward compat*/, home)
                    .replace("{{user.home}}", home));
            if (Files.exists(target.getParent())) {
                try {
                    Files.createDirectories(target.getParent());
                } catch (final IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            if (Files.exists(target)) {
                final var backup = target.getParent().resolve(target.getFileName().toString() + "." + current);
                log.info(() -> "Saving current version at '" + backup + "'");
                try {
                    Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    Files.list(target.getParent())
                            .filter(p -> p.getFileName().toString().startsWith(target.getFileName().toString() + '.'))
                            .filter(p -> !Objects.equals(p, backup))
                            .forEach(previous -> {
                                try {
                                    log.info(() -> "Deleting outdated version: '" + previous + "'");
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
                    URI.create(maven.toRelativePath(repository, groupId, artifactId, last, !classifier.isEmpty() ? '-' + classifier : "", type, last)),
                    target)
                    .thenApply(exec -> {
                        try {
                            final var perms = Files.getPosixFilePermissions(exec);
                            if (!perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
                                Files.setPosixFilePermissions(
                                        exec, Stream.concat(perms.stream(), Stream.of(PosixFilePermission.OWNER_EXECUTE)).collect(toSet()));
                            }
                        } catch (final IOException ioe) {
                            throw new IllegalStateException(ioe);
                        }
                        return last;
                    });
        }));

    }
}