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
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(name = "new", description = "Creates a new project.")
public class NewBundleCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(NewBundleCommand.class.getName());

    public NewBundleCommand(final Options options) {
        this.group = options.group();
        this.artifact = options.artifact();
        this.version = options.version();
        this.directory = options.directory();
        this.skipSamples = options.skipSamples();
        this.skipPom = options.skipPom();
        this.force = options.force();
    }

    private final String group;
    private final String artifact;
    private final String version;
    private final String directory;
    private final boolean skipSamples;
    private final boolean skipPom;
    private final boolean force;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "skipSamples":
            case "skipPom":
            case "force":
                return Stream.of("false", "true");
            default:
                return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Creates a new project.";
    }

    @Override
    public CompletionStage<?> execute() {
        if (UNSET.equals(group) || UNSET.equals(artifact)) {
            throw new IllegalArgumentException("Ensure to set group and artifact for command new");
        }
        final var output = Paths.get(UNSET.equals(directory) ? artifact : directory)
                .normalize()
                .toAbsolutePath();
        try {
            if (!force
                    && Files.exists(output)
                    && Files.list(output).anyMatch(it -> {
                        final var name = it.getFileName().toString();
                        return !".".equals(name) && !"..".equals(name);
                    })) {
                throw new IllegalArgumentException("Folder already exists: " + output);
            }
            log.info("Created " + Files.createDirectories(output.resolve("bundlebee/kubernetes")));
            log.info("Created "
                    + Files.writeString(
                            output.resolve("bundlebee/manifest.json"),
                            "{\n" + "  \"alveoli\": [\n"
                                    + (skipSamples
                                            ? ""
                                            : "    {\n" + "      \"name\": \"" + group + ":" + artifact
                                                    + "_my-alveolus:" + version + "\",\n" + "      \"descriptors\": [\n"
                                                    + "        {\n" + "          \"name\": \"" + group + "_" + artifact
                                                    + "_my-alveolus.configmap\"\n" + "        }\n" + "      ]\n"
                                                    + "    }\n")
                                    + "  ]\n" + "}" + "",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING));
            if (!skipSamples) {
                log.info("Created "
                        + Files.writeString(
                                output.resolve("bundlebee/kubernetes/" + group + "_" + artifact
                                        + "_my-alveolus.configmap.yaml"),
                                "apiVersion: v1\n" + "kind: ConfigMap\n" + "metadata:\n" + "  name: "
                                        + group.replace('/', '.').replace('.', '_') + '_' + artifact
                                        + "_my-alveolus_configmap\n" + "  labels:\n" + "    app: my-app\n" + "data:\n"
                                        + "  # set your data there, you can safely drop BUNDLEBEE_SKAFFOLDING variable\n"
                                        + "  BUNDLEBEE_SKAFFOLDING: true\n" + "",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING));
            } else {
                log.info("Created "
                        + Files.writeString(
                                output.resolve("bundlebee/kubernetes/.keepit"),
                                "can safely be deleted as soon as you add a file in this folder",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING));
            }
            if (!skipPom) {
                log.info("Created "
                        + Files.writeString(
                                output.resolve("pom.xml"),
                                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                        + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                                        + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                                        + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                                        + "  <modelVersion>4.0.0</modelVersion>\n" + "\n" + "  <groupId>"
                                        + group.replace('/', '.') + "</groupId>\n" + "  <artifactId>" + artifact
                                        + "</artifactId>\n" + "  <version>" + version + "</version>\n" + "  <name>"
                                        + artifact + "</name>\n" + "  <description>Informative pom.xml for " + artifact
                                        + " if you want to use maven to build and deploy this bundle.</description>\n"
                                        + "\n" + "  <build>\n" + "    <resources>\n" + "      <resource>\n"
                                        + "        <directory>${project.basedir}</directory>\n" + "        <includes>\n"
                                        + "          <include>bundlebee/**</include>\n" + "        </includes>\n"
                                        + "      </resource>\n" + "    </resources>\n" + "    <plugins>\n"
                                        + "      <plugin>\n" + "        <groupId>org.apache.maven.plugins</groupId>\n"
                                        + "        <artifactId>maven-release-plugin</artifactId>\n"
                                        + "        <version>3.0.0-M1</version>\n" + "      </plugin>\n"
                                        + "    </plugins>\n" + "  </build>\n" + "\n"
                                        + "  <!-- override with your custom nexus if you have one -->\n"
                                        + "  <distributionManagement>\n" + "    <snapshotRepository>\n"
                                        + "      <id>my-nexus</id>\n"
                                        + "      <url>https://nexus.mycompany.org/content/repositories/snapshots</url>\n"
                                        + "    </snapshotRepository>\n" + "    <repository>\n"
                                        + "      <id>my-nexus</id>\n"
                                        + "      <url>https://nexus.mycompany.org/content/repositories/releases</url>\n"
                                        + "    </repository>\n" + "  </distributionManagement>\n" + "</project>\n" + "",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING));
            }
            log.info("Creation completed, you can go in " + output + " and build it with \'bundlebee build\'");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return completedFuture(true);
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.new.group",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle groupId.")
            String group,

            @Property(
                    value = "bundlebee.new.artifact",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle artifactId.")
            String artifact,

            @Property(value = "bundlebee.new.version", defaultValue = "\"1.0.0\"", documentation = "Bundle version.")
            String version,

            @Property(
                    value = "bundlebee.new.dir",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "Where to create the project. If not set it will use the artifact value in current folder.")
            String directory,

            @Property(
                    value = "bundlebee.new.skipSamples",
                    defaultValue = "false",
                    documentation =
                            "By default a sample alveolus with a config map is generated, if `false` it will be skipped.")
            boolean skipSamples,

            @Property(
                    value = "bundlebee.new.skipPom",
                    defaultValue = "false",
                    documentation = "If `true` no pom.xml is generated.")
            boolean skipPom,

            @Property(
                    value = "bundlebee.new.force",
                    defaultValue = "false",
                    documentation = "If `true` the execution runs even if dir already exists.")
            boolean force) {}
}
