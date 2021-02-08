/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
import io.yupiik.bundlebee.core.service.MavenResolver;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletionStage;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Log
@Dependent
public class BuildCommand implements Executable {
    @Inject
    @Description("Project to build.")
    @ConfigProperty(name = "bundlebee.build.dir", defaultValue = ".")
    private String directory;

    @Inject
    @Description("Project to build.")
    @ConfigProperty(name = "bundlebee.build.builddir", defaultValue = "target")
    private String buildDirectory;

    @Inject
    @Description("If `true` it will be added to your local maven repository.")
    @ConfigProperty(name = "bundlebee.build.deployInLocalRepository", defaultValue = "true")
    private boolean deployInLocalRepository;

    @Inject
    @Description("Bundle groupId.")
    @ConfigProperty(name = "bundlebee.build.group", defaultValue = UNSET)
    private String group;

    @Inject
    @Description("Bundle artifactId.")
    @ConfigProperty(name = "bundlebee.build.artifact", defaultValue = UNSET)
    private String artifact;

    @Inject
    @Description("Bundle version.")
    @ConfigProperty(name = "bundlebee.build.version", defaultValue = UNSET)
    private String version;

    @Inject
    private MavenResolver mvn;

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
        final var source = Paths.get(directory).normalize().toAbsolutePath();
        if (!Files.exists(source)) {
            throw new IllegalArgumentException(source + " does not exist");
        }
        if (!Files.exists(source.resolve("bundlebee/manifest.json"))) {
            throw new IllegalArgumentException("No manifest.json in " + source);
        }
        final var buildDirPath = Paths.get(buildDirectory);
        final var target = buildDirPath.isAbsolute() ?
                buildDirPath :
                source.resolve(buildDirPath).normalize().toAbsolutePath();

        if (UNSET.equals(group) || UNSET.equals(artifact) || UNSET.equals(version)) {
            loadGavFromPom(source.resolve("pom.xml"));
        }

        try {
            Files.createDirectories(target);
            final var jar = target.resolve(artifact + "-" + version + ".jar");
            final var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1");
            manifest.getMainAttributes().putValue("Generator", getClass().getSimpleName());
            try (final JarOutputStream jarStream = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
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
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                            final var name = file.getFileName().toString();
                            if (name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")) {
                                final var path = source.relativize(file).toString().replace('\\', '/');
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
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }

        log.info("Project successfully built.");
        return completedFuture(true);
    }

    private void loadGavFromPom(final Path pom) {
        if (!Files.exists(pom)) {
            throw new IllegalArgumentException("No pom at " + pom + ", ensure to set group, artifact and version on the CLI");
        }
        final MavenResolver.GavParser gav;
        try (final InputStream stream = Files.newInputStream(pom)) {
            gav = mvn.extractGav(stream);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        if (UNSET.equals(group)) {
            group = gav.getGroup();
        }
        if (UNSET.equals(artifact)) {
            artifact = gav.getArtifact();
        }
        if (UNSET.equals(version)) {
            version = gav.getVersion();
        }
    }
}
