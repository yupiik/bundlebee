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

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(name = "build", description = "Build a project.")
public class BuildCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(BuildCommand.class.getName());

    public BuildCommand(final Options options, Maven mvn) {
        this.directory = options.directory();
        this.buildDirectory = options.buildDirectory();
        this.deployInLocalRepository = options.deployInLocalRepository();
        this.group = options.group();
        this.artifact = options.artifact();
        this.version = options.version();
        this.mvn = mvn;
    }

    private final String directory;
    private final String buildDirectory;
    private final boolean deployInLocalRepository;
    private final String group;
    private final String artifact;
    private final String version;
    private final Maven mvn;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        if ("deployInLocalRepository".equals(optionName)) {
            return Stream.of("false", "true");
        }
        return Stream.empty();
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Build a project.";
    }

    @Override
    public CompletionStage<?> execute() {
        return doBuild(directory, buildDirectory, group, artifact, version, deployInLocalRepository);
    }

    public CompletionStage<BuildResult> doBuild(
            final String directory,
            final String buildDirectory,
            final String rawGroup,
            final String rawArtifact,
            final String rawVersion,
            final boolean deployInLocalRepository) {
        final var source = Paths.get(directory).normalize().toAbsolutePath();
        if (!Files.exists(source)) {
            throw new IllegalArgumentException(source + " does not exist");
        }
        if (!Files.exists(source.resolve("bundlebee/manifest.json"))) {
            throw new IllegalArgumentException("No manifest.json in " + source);
        }
        final var buildDirPath = Paths.get(buildDirectory);
        final var target = buildDirPath.isAbsolute()
                ? buildDirPath
                : source.resolve(buildDirPath).normalize().toAbsolutePath();
        final String group;
        final String artifact;
        final String version;
        if (UNSET.equals(rawGroup) || UNSET.equals(rawArtifact) || UNSET.equals(rawVersion)) {
            final var gav = loadGavFromPom(source.resolve("pom.xml"));
            if (UNSET.equals(rawGroup)) {
                group = gav.getGroup();
            } else {
                group = rawGroup;
            }
            if (UNSET.equals(rawArtifact)) {
                artifact = gav.getArtifact();
            } else {
                artifact = rawArtifact;
            }
            if (UNSET.equals(rawVersion)) {
                version = gav.getVersion();
            } else {
                version = rawVersion;
            }
        } else {
            group = rawGroup;
            artifact = rawArtifact;
            version = rawVersion;
        }
        try {
            Files.createDirectories(target);
            final var jar = target.resolve(artifact + "-" + version + ".jar");
            final var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1");
            manifest.getMainAttributes().putValue("Generator", getClass().getSimpleName());
            try (JarOutputStream jarStream = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
                jarStream.putNextEntry(new JarEntry("bundlebee/"));
                jarStream.closeEntry();
                log.info(() -> "Including bundlebee/manifest.json");
                jarStream.putNextEntry(new JarEntry("bundlebee/manifest.json"));
                jarStream.write(Files.readAllBytes(source.resolve("bundlebee/manifest.json")));
                jarStream.closeEntry();
                final var descriptors = source.resolve("bundlebee/kubernetes");
                if (Files.exists(descriptors)) {
                    jarStream.putNextEntry(new JarEntry("bundlebee/kubernetes/"));
                    jarStream.closeEntry();
                    Files.walkFileTree(descriptors, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                                throws IOException {
                            final var name = file.getFileName().toString();
                            if (name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")) {
                                final var path =
                                        source.relativize(file).toString().replace('\\', '/');
                                log.info(() -> "Including " + path);
                                try {
                                    jarStream.putNextEntry(new JarEntry(path));
                                    Files.copy(file, jarStream);
                                    jarStream.closeEntry();
                                } catch (final IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            }
                            return super.visitFile(file, attrs);
                        }
                    });
                }
            }
            log.info("Built " + jar);
            if (deployInLocalRepository) {
                final var m2Location = mvn.getM2()
                        .resolve(group.replace('.', '/'))
                        .resolve(artifact)
                        .resolve(version)
                        .resolve(artifact + "-" + version + ".jar");
                Files.createDirectories(m2Location.getParent());
                Files.copy(jar, m2Location, StandardCopyOption.REPLACE_EXISTING);
                log.info(() -> "Installed " + m2Location);
            }
            log.info("Project successfully built.");
            return completedFuture(new BuildResult(group, artifact, version, jar));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Maven.GavParser loadGavFromPom(final Path pom) {
        if (!Files.exists(pom)) {
            throw new IllegalArgumentException(
                    "No pom at " + pom + ", ensure to set group, artifact and version on the CLI");
        }
        try (InputStream stream = Files.newInputStream(pom)) {
            return mvn.extractGav(stream);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class BuildResult {
        private final String group;
        private final String artifact;
        private final String version;
        private final Path jar;

        public BuildResult(final String group, final String artifact, final String version, final Path jar) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.jar = jar;
        }

        public String getGroup() {
            return this.group;
        }

        public String getArtifact() {
            return this.artifact;
        }

        public String getVersion() {
            return this.version;
        }

        public Path getJar() {
            return this.jar;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof BuildResult other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getGroup(), other.getGroup())
                    && Objects.equals(this.getArtifact(), other.getArtifact())
                    && Objects.equals(this.getVersion(), other.getVersion())
                    && Objects.equals(this.getJar(), other.getJar());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof BuildCommand.BuildResult;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getGroup(), this.getArtifact(), this.getVersion(), this.getJar());
        }

        @Override
        public String toString() {
            return "BuildCommand.BuildResult(group=" + this.getGroup() + ", artifact=" + this.getArtifact()
                    + ", version=" + this.getVersion() + ", jar=" + this.getJar() + ")";
        }
    }

    @RootConfiguration("-")
    public record Options(
            @Property(value = "bundlebee.build.dir", defaultValue = "\".\"", documentation = "Project to build.")
            String directory,

            @Property(
                    value = "bundlebee.build.builddir",
                    defaultValue = "\"target\"",
                    documentation = "Project to build.")
            String buildDirectory,

            @Property(
                    value = "bundlebee.build.deployInLocalRepository",
                    defaultValue = "true",
                    documentation = "If `true` it will be added to your local maven repository.")
            boolean deployInLocalRepository,

            @Property(
                    value = "bundlebee.build.group",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle groupId.")
            String group,

            @Property(
                    value = "bundlebee.build.artifact",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle artifactId.")
            String artifact,

            @Property(
                    value = "bundlebee.build.version",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle version.")
            String version) {}
}
