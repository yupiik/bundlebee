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
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Log
@Dependent
public class NewBundleCommand implements Executable {
    @Inject
    @Description("Bundle groupId.")
    @ConfigProperty(name = "bundlebee.new.group", defaultValue = UNSET)
    private String group;

    @Inject
    @Description("Bundle artifactId.")
    @ConfigProperty(name = "bundlebee.new.artifact", defaultValue = UNSET)
    private String artifact;

    @Inject
    @Description("Bundle version.")
    @ConfigProperty(name = "bundlebee.new.version", defaultValue = "1.0.0")
    private String version;

    @Inject
    @Description("Where to create the project. If not set it will use the artifact value in current folder.")
    @ConfigProperty(name = "bundlebee.new.dir", defaultValue = UNSET)
    private String directory;

    @Inject
    @Description("By default a sample alveolus with a config map is generated, if `false` it will be skipped.")
    @ConfigProperty(name = "bundlebee.new.skipSamples", defaultValue = "false")
    private boolean skipSamples;

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
        final var output = Paths.get(UNSET.equals(directory) ? artifact : directory).normalize().toAbsolutePath();
        try {
            if (Files.exists(output) && Files.list(output).anyMatch(it -> {
                final var name = it.getFileName().toString();
                return !".".equals(name) && !"..".equals(name);
            })) {
                throw new IllegalArgumentException("Folder already exists: " + output);
            }

            log.info("Created " + Files.createDirectories(output.resolve("bundlebee/kubernetes")));
            log.info("Created " + Files.writeString(output.resolve("bundlebee/manifest.json"), "" +
                    "{\n" +
                    "  \"alveoli\": [\n" +
                    (skipSamples ? "" : "" +
                            "    {\n" +
                            "      \"name\": \"" + group + ":" + artifact + "_my-alveolus:" + version + "\",\n" +
                            "      \"descriptors\": [\n" +
                            "        {\n" +
                            "          \"name\": \"" + group + "_" + artifact + "_my-alveolus.configmap\"\n" +
                            "        }\n" +
                            "      ]\n" +
                            "    }\n") +
                    "  ]\n" +
                    "}" +
                    "", StandardOpenOption.CREATE));
            if (!skipSamples) {
                log.info("Created " + Files.writeString(output.resolve("bundlebee/kubernetes/" + group + "_" + artifact + "_my-alveolus.configmap.yaml"), "" +
                        "apiVersion: v1\n" +
                        "kind: ConfigMap\n" +
                        "metadata:\n" +
                        "  name: " +
                        group.replace('/', '.').replace('.', '_') + '_' +
                        artifact +
                        "_my-alveolus_configmap\n" +
                        "  labels:\n" +
                        "    app: my-app\n" +
                        "data:\n" +
                        "  # set your data there, you can safely drop BUNDLEBEE_SKAFFOLDING variable\n" +
                        "  BUNDLEBEE_SKAFFOLDING: true\n" +
                        "", StandardOpenOption.CREATE));
            } else {
                log.info("Created " + Files.writeString(output.resolve("bundlebee/kubernetes/.keepit"),
                        "can safely be deleted as soon as you add a file in this folder", StandardOpenOption.CREATE));
            }
            log.info("Created " + Files.writeString(output.resolve("pom.xml"), "" +
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                    "  <modelVersion>4.0.0</modelVersion>\n" +
                    "\n" +
                    "  <groupId>" + group.replace('/', '.') + "</groupId>\n" +
                    "  <artifactId>" + artifact + "</artifactId>\n" +
                    "  <version>" + version + "</version>\n" +
                    "  <name>" + artifact + "</name>\n" +
                    "  <description>Informative pom.xml for " + artifact + " if you want to use maven to build and deploy this bundle.</description>\n" +
                    "\n" +
                    "  <build>\n" +
                    "    <resources>\n" +
                    "      <resource>\n" +
                    "        <directory>${project.basedir}</directory>\n" +
                    "        <includes>\n" +
                    "          <include>bundlebee/**</include>\n" +
                    "        </includes>\n" +
                    "      </resource>\n" +
                    "    </resources>\n" +
                    "    <plugins>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-release-plugin</artifactId>\n" +
                    "        <version>3.0.0-M1</version>\n" +
                    "      </plugin>\n" +
                    "    </plugins>\n" +
                    "  </build>\n" +
                    "\n" +
                    "  <!-- override with your custom nexus if you have one -->\n" +
                    "  <distributionManagement>\n" +
                    "    <snapshotRepository>\n" +
                    "      <id>my-nexus</id>\n" +
                    "      <url>https://nexus.mycompany.org/content/repositories/snapshots</url>\n" +
                    "    </snapshotRepository>\n" +
                    "    <repository>\n" +
                    "      <id>my-nexus</id>\n" +
                    "      <url>https://nexus.mycompany.org/content/repositories/releases</url>\n" +
                    "    </repository>\n" +
                    "  </distributionManagement>\n" +
                    "</project>\n" +
                    "", StandardOpenOption.CREATE));

            log.info("Creation completed, you can go in " + output + " and build it with 'bundlebee build'");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return completedFuture(true);
    }
}
