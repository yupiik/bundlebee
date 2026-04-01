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
package io.yupiik.bundlebee.documentation;

import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.logging.Logger;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@Log
public class K8sPublishMavenProjectGenerator implements Runnable {
    private final Path sourceBase;
    private final String projectVersion;

    public K8sPublishMavenProjectGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.projectVersion = configuration.get("version");
    }

    @Override
    public void run() {
        final var docBase = sourceBase.resolve("../../..").normalize();
        final var schemas = docBase.resolve("target/bundlebee-documentation-" + projectVersion + "/generated/kubernetes/jsonschema");

        final var logger = Logger.getLogger(getClass().getName());
        if (Files.notExists(schemas)) {
            logger.info(() -> "Skipping " + getClass().getSimpleName() + ", no '" + schemas + "' directory");
            return;
        }

        try (final var ls = Files.list(schemas)) {
            final var fakeProject = Files.createDirectories(docBase.resolve("target/generated__publish-schemas"));
            final var versions = new HashSet<String>();
            for (final var it : ls
                    .filter(Files::isDirectory)
                    // is a version
                    .filter(it -> it.getFileName().toString().contains("."))
                    .collect(toList())) {
                final var version = it.getFileName().toString();
                versions.add(version);

                final var module = Files.createDirectories(fakeProject.resolve(it.getFileName()));
                Files.writeString(module.resolve("pom.xml"), "" +
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"" +
                        "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
                        "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                        "  <parent>\n" +
                        "    <artifactId>parent</artifactId>\n" +
                        "    <groupId>io.yupiik.kubernetes.schema</groupId>\n" +
                        "    <version>" + projectVersion + "</version>\n" +
                        "  </parent>\n" +
                        "  <modelVersion>4.0.0</modelVersion>\n" +
                        "\n" +
                        "  <artifactId>" + version + "</artifactId>\n" +
                        "  <name>Kubernetes Schema :: " + version + "</name>\n" +
                        "  <description>Kubernetes " + version + " schemas.</description>\n" +
                        "</project>");
                final var packageVersion = version.replace('.', '_');
                final var javaBase = Files.createDirectories(module.resolve("src/main/java/io/yupiik/kubernetes/schema/v" + packageVersion));
                Files.writeString(javaBase.resolve("Version.java"), "package io.yupiik.kubernetes.schema.v" + packageVersion + ";\n" +
                        "\n" +
                        "public final class Version {\n" +
                        "  public static final String VALUE = \"" + version + "\";\n" +
                        "\n" +
                        "  private Version() {\n" +
                        "    // no-op\n" +
                        "  }\n" +
                        "\n" +
                        "}\n");

                final var resources = module.resolve("src/main/resources");
                Files.walkFileTree(it, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        if (!file.getFileName().toString().endsWith(".json")) {
                            return super.visitFile(file, attrs);
                        }
                        final var target = resources.resolve(it.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        return super.visitFile(file, attrs);
                    }
                });
            }

            Files.writeString(fakeProject.resolve("pom.xml"), "" +
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"" +
                    "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
                    "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                    "  <modelVersion>4.0.0</modelVersion>\n" +
                    "\n" +
                    "  <groupId>io.yupiik.kubernetes.schema</groupId>\n" +
                    "  <artifactId>parent</artifactId>\n" +
                    "  <version>" + projectVersion + "</version>\n" +
                    "  <packaging>pom</packaging>\n" +
                    "  <name>Kubernetes Schema</name>\n" +
                    "  <description>Bundle Kubernetes schemas in jar so they can be consumed from central.</description>\n" +
                    "  <!-- this project is generated, this is the project owning the generator for now, in bundlebee-documentation module -->\n" +
                    "  <url>https://github.com/yupiik/bundlebee</url>\n" +
                    "\n" +
                    "  <organization>\n" +
                    "    <name>Yupiik SAS</name>\n" +
                    "    <url>https://www.yupiik.com</url>\n" +
                    "  </organization>\n" +
                    "  <inceptionYear>2026 - present</inceptionYear>\n" +
                    "\n" +
                    "  <properties>\n" +
                    "    <project.build.outputTimestamp>2026-04-01T10:00:00Z</project.build.outputTimestamp>\n" +
                    "  </properties>\n" +
                    "\n" +
                    "  <modules>\n" +
                    versions.stream().map(it ->  "    <module>"+it+"</module>\n").collect(joining()) +
                    "  </modules>\n" +
                    "\n" +
                    "  <build>\n" +
                    "    <plugins>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-jar-plugin</artifactId>\n" +
                    "        <version>3.3.0</version>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-clean-plugin</artifactId>\n" +
                    "        <version>3.3.1</version>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-install-plugin</artifactId>\n" +
                    "        <version>3.1.1</version>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-resources-plugin</artifactId>\n" +
                    "        <version>3.3.1</version>\n" +
                    "        <configuration>\n" +
                    "          <encoding>UTF-8</encoding>\n" +
                    "        </configuration>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-compiler-plugin</artifactId>\n" +
                    "        <version>3.11.0</version>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-surefire-plugin</artifactId>\n" +
                    "        <version>3.0.0</version>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.sonatype.central</groupId>\n" +
                    "        <artifactId>central-publishing-maven-plugin</artifactId>\n" +
                    "        <version>0.6.0</version>\n" +
                    "        <extensions>true</extensions>\n" +
                    "        <configuration>\n" +
                    "          <publishingServerId>central</publishingServerId>\n" +
                    "          <autoPublish>true</autoPublish>\n" +
                    "        </configuration>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-javadoc-plugin</artifactId>\n" +
                    "        <version>3.6.3</version>\n" +
                    "        <executions>\n" +
                    "          <execution>\n" +
                    "            <id>attach-javadocs</id>\n" +
                    "            <goals>\n" +
                    "              <goal>jar</goal>\n" +
                    "            </goals>\n" +
                    "          </execution>\n" +
                    "        </executions>\n" +
                    "        <configuration>\n" +
                    "          <source>11</source>\n" +
                    "          <doclint>none</doclint>\n" +
                    "          <encoding>UTF-8</encoding>\n" +
                    "        </configuration>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-release-plugin</artifactId>\n" +
                    "        <version>3.0.0-M1</version>\n" +
                    "        <configuration>\n" +
                    "          <autoVersionSubmodules>true</autoVersionSubmodules>\n" +
                    "        </configuration>\n" +
                    "      </plugin>\n" +
                    "      <plugin>\n" +
                    "        <groupId>org.apache.maven.plugins</groupId>\n" +
                    "        <artifactId>maven-source-plugin</artifactId>\n" +
                    "        <version>3.3.0</version>\n" +
                    "        <executions>\n" +
                    "          <execution>\n" +
                    "            <id>attach-sources</id>\n" +
                    "            <goals>\n" +
                    "              <goal>jar-no-fork</goal>\n" +
                    "            </goals>\n" +
                    "          </execution>\n" +
                    "        </executions>\n" +
                    "      </plugin>\n" +
                    "    </plugins>\n" +
                    "  </build>\n" +
                    "\n" +
                    "  <profiles>\n" +
                    "    <profile>\n" +
                    "      <id>release</id>\n" +
                    "      <activation>\n" +
                    "        <activeByDefault>false</activeByDefault>\n" +
                    "      </activation>\n" +
                    "      <build>\n" +
                    "        <plugins>\n" +
                    "          <plugin>\n" +
                    "            <groupId>org.apache.maven.plugins</groupId>\n" +
                    "            <artifactId>maven-gpg-plugin</artifactId>\n" +
                    "            <version>3.2.4</version>\n" +
                    "            <configuration>\n" +
                    "              <gpgArguments>\n" +
                    "                <arg>--digest-algo=SHA512</arg>\n" +
                    "              </gpgArguments>\n" +
                    "            </configuration>\n" +
                    "            <executions>\n" +
                    "              <execution>\n" +
                    "                <id>sign-artifacts</id>\n" +
                    "                <phase>verify</phase>\n" +
                    "                <goals>\n" +
                    "                  <goal>sign</goal>\n" +
                    "                </goals>\n" +
                    "              </execution>\n" +
                    "            </executions>\n" +
                    "          </plugin>\n" +
                    "          <plugin>\n" +
                    "            <groupId>net.nicoulaj.maven.plugins</groupId>\n" +
                    "            <artifactId>checksum-maven-plugin</artifactId>\n" +
                    "            <executions>\n" +
                    "              <execution>\n" +
                    "                <id>source-release-checksum</id>\n" +
                    "                <goals>\n" +
                    "                  <goal>artifacts</goal>\n" +
                    "                </goals>\n" +
                    "              </execution>\n" +
                    "            </executions>\n" +
                    "            <configuration>\n" +
                    "              <algorithms>\n" +
                    "                <algorithm>SHA-512</algorithm>\n" +
                    "              </algorithms>\n" +
                    "              <csvSummary>false</csvSummary>\n" +
                    "            </configuration>\n" +
                    "          </plugin>\n" +
                    "        </plugins>\n" +
                    "      </build>\n" +
                    "    </profile>\n" +
                    "  </profiles>\n" +
                    "\n" +
                    "  <licenses>\n" +
                    "    <license>\n" +
                    "      <name>Apache License, Version 2.0</name>\n" +
                    "      <url>https://github.com/yupiik/yupiik-logging/blob/master/LICENSE</url>\n" +
                    "      <distribution>may be downloaded from the Maven repository</distribution>\n" +
                    "    </license>\n" +
                    "  </licenses>\n" +
                    "\n" +
                    "  <developers>\n" +
                    "    <developer>\n" +
                    "      <name>Romain Manni-Bucau</name>\n" +
                    "      <id>rmannibucau</id>\n" +
                    "      <roles>\n" +
                    "        <role>Contributor</role>\n" +
                    "      </roles>\n" +
                    "      <timezone>+1</timezone>\n" +
                    "    </developer>\n" +
                    "    <developer>\n" +
                    "      <name>Francois Papon</name>\n" +
                    "      <id>fpapon</id>\n" +
                    "      <roles>\n" +
                    "        <role>Contributor</role>\n" +
                    "      </roles>\n" +
                    "      <timezone>+1</timezone>\n" +
                    "    </developer>\n" +
                    "  </developers>\n" +
                    "\n" +
                    "  <scm>\n" +
                    "    <!-- this project is actually generated so these are the generator metadata -->" +
                    "    <connection>scm:git:https://github.com/yupiik/bundlebee.git</connection>\n" +
                    "    <developerConnection>scm:git:https://github.com/yupiik/bundlebee.git</developerConnection>\n" +
                    "    <url>https://github.com/yupiik/bundlebee.git</url>\n" +
                    "  </scm>\n" +
                    "\n" +
                    "  <distributionManagement>\n" +
                    "    <snapshotRepository>\n" +
                    "      <id>ossrh</id>\n" +
                    "      <url>https://oss.sonatype.org/content/repositories/snapshots</url>\n" +
                    "    </snapshotRepository>\n" +
                    "    <repository>\n" +
                    "      <id>ossrh</id>\n" +
                    "      <url>https://oss.sonatype.org/service/local/staging/deploy/maven2/</url>\n" +
                    "    </repository>\n" +
                    "  </distributionManagement>\n" +
                    "</project>\n");
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        logger.info(() -> "You can publish the schema on central going in 'target/generated__publish-schemas/' and running 'mvn deploy -Prelease'");
    }
}
