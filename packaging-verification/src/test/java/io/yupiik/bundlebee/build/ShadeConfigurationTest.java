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

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

class ShadeConfigurationTest {
    @Test
    void avoidsDependencyReducedPomsInTheSourceTree() throws Exception {
        final var directory = Path.of(System.getProperty("bundlebee.root", "."))
                .toAbsolutePath()
                .normalize();
        final var root = Files.isDirectory(directory.resolve("bundlebee-core")) ? directory : directory.getParent();
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var document = factory.newDocumentBuilder()
                .parse(root.resolve("bundlebee-core/pom.xml").toFile());
        final var xpath = XPathFactory.newInstance().newXPath();
        final var shade = "/project/build/plugins/plugin[artifactId='maven-shade-plugin']";
        assertEquals("1", xpath.evaluate("count(" + shade + ")", document));
        final var execution = shade + "/executions/execution[goals/goal='shade']";
        assertEquals("1", xpath.evaluate("count(" + execution + ")", document));
        assertEquals("false", xpath.evaluate(execution + "/configuration/createDependencyReducedPom", document));
        assertEquals(
                "${project.build.directory}/reduced-pom.xml",
                xpath.evaluate(execution + "/configuration/dependencyReducedPomLocation", document));
        assertEquals("true", xpath.evaluate(execution + "/configuration/shadedArtifactAttached", document));
        assertEquals("fat", xpath.evaluate(execution + "/configuration/shadedClassifierName", document));
        // Deliberately inspect configuration only: existing output files can predate this configuration.
    }
}
