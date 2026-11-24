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
package io.yupiik.bundlebee.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

class SpotlessConfigurationTest {
    @Test
    void configuresJava21FormattingWithoutRaisingJava17ApplicationBaseline() throws Exception {
        final var root = root();
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var builder = factory.newDocumentBuilder();
        final var xpath = XPathFactory.newInstance().newXPath();
        final var plugin = "/project/build/plugins/plugin[artifactId='spotless-maven-plugin']";
        for (final var pom : List.of(root.resolve("pom.xml"), root.resolve("packaging-verification/pom.xml"))) {
            final var document = builder.parse(pom.toFile());
            assertEquals("1", xpath.evaluate("count(" + plugin + ")", document), pom.toString());
            assertEquals("com.diffplug.spotless", xpath.evaluate(plugin + "/groupId", document));
            assertEquals("3.12.0", xpath.evaluate(plugin + "/version", document));
            assertNotEquals("false", xpath.evaluate(plugin + "/inherited", document));
            assertEquals(
                    "compile", xpath.evaluate(plugin + "/executions/execution[id='spotless-check']/phase", document));
            assertEquals(
                    "check",
                    xpath.evaluate(plugin + "/executions/execution[id='spotless-check']/goals/goal", document));
            final var config = plugin + "/configuration";
            assertEquals("0", xpath.evaluate("count(" + config + "/skip | " + config + "/ratchetFrom)", document));
            assertEquals("true", xpath.evaluate(config + "/upToDateChecking/enabled", document));
            assertEquals(
                    "${maven.multiModuleProjectDirectory}/.mvn/.spotless/${project.artifactId}-spotless-index",
                    xpath.evaluate(config + "/upToDateChecking/indexFile", document));
            assertEquals("2.103.0", xpath.evaluate(config + "/java/palantirJavaFormat/version", document));
            assertEquals("0", xpath.evaluate("count(/project/profiles/profile//palantirJavaFormat)", document));
            for (final var include : List.of(
                    "src/main/java/**/*.java", "src/test/java/**/*.java", "src/main/java-templates/**/*.java")) {
                assertEquals(
                        "1",
                        xpath.evaluate(
                                "count(" + config + "/java/includes/include[text()='" + include + "'])", document));
            }
            for (final var step : List.of("shortenFullyQualifiedTypes", "removeUnusedImports")) {
                assertEquals("1", xpath.evaluate("count(" + config + "/java/" + step + ")", document));
            }
            for (final var exclude : List.of("**/generated/**", "**/generated_*/**")) {
                assertEquals(
                        "1",
                        xpath.evaluate(
                                "count(" + config + "/java/excludes/exclude[text()='" + exclude + "'])", document));
            }
            assertEquals("false", xpath.evaluate(config + "/pom/sortPom/expandEmptyElements", document));
            assertEquals("true", xpath.evaluate(config + "/pom/sortPom/spaceBeforeCloseEmptyElement", document));
            for (final var include : List.of(".gitignore", ".gitattributes")) {
                assertEquals(
                        "1",
                        xpath.evaluate(
                                "count(" + config + "/formats/format/includes/include[text()='" + include + "'])",
                                document));
            }
            for (final var step : List.of("trimTrailingWhitespace", "endWithNewline")) {
                assertEquals("1", xpath.evaluate("count(" + config + "/formats/format/" + step + ")", document));
            }
            assertEquals("true", xpath.evaluate(config + "/formats/format/indent/tabs", document));
            assertEquals("4", xpath.evaluate(config + "/formats/format/indent/spacesPerTab", document));
            if (pom.equals(root.resolve("pom.xml"))) {
                for (final var setting : List.of("source", "target", "release")) {
                    assertEquals(
                            "17",
                            xpath.evaluate(
                                    "/project/build/plugins/plugin[artifactId='maven-compiler-plugin']/configuration/"
                                            + setting,
                                    document));
                }
                assertEquals(
                        "17",
                        xpath.evaluate(
                                "/project/build/plugins/plugin[artifactId='maven-javadoc-plugin']/configuration/source",
                                document));
            } else {
                assertEquals("0", xpath.evaluate("count(/project/parent)", document));
                assertEquals("17", xpath.evaluate("/project/properties/maven.compiler.release", document));
            }
        }
        assertTrue(Files.readAllLines(root.resolve(".gitignore")).contains("**/.mvn/.spotless/*-spotless-index"));
    }

    @Test
    void documentsBuildJdkSeparatelyFromRuntimeAndForksCiTests() throws Exception {
        final var root = root();
        for (final var path :
                List.of("README.adoc", "bundlebee-documentation/src/main/minisite/content/getting-started.adoc")) {
            final var documentation = Files.readString(root.resolve(path));
            assertTrue(documentation.contains(
                    "We kept the Java 17 baseline, but the build requires Java 21 for formatting tooling."));
            assertTrue(documentation.contains("2.103.0"));
            assertTrue(documentation.contains("-Djvm=/path/to/jdk17/bin/java"));
        }
        final var workflow = Files.readString(root.resolve(".github/workflows/maven.yml"));
        assertTrue(workflow.contains("build-java: '21'\n            runtime-java: '17'"));
        assertTrue(workflow.contains("build-java: '21'\n            runtime-java: '21'"));
        assertTrue(workflow.contains("build-java: '25'\n            runtime-java: '25'"));
        assertTrue(workflow.contains("echo \"TEST_JAVA=$JAVA_HOME/bin/java\" >> \"$GITHUB_ENV\""));
        assertTrue(workflow.contains("mvn install -Djvm=\"$TEST_JAVA\""));
        assertTrue(workflow.contains("packaging-verification/pom.xml test -Djvm=\"$TEST_JAVA\""));
        assertTrue(workflow.contains("if: matrix.build-java == '21' && matrix.runtime-java == '21'"));
    }

    private Path root() {
        final var directory = Path.of(System.getProperty("bundlebee.root", "."))
                .toAbsolutePath()
                .normalize();
        return Files.isDirectory(directory.resolve("bundlebee-core")) ? directory : directory.getParent();
    }
}
