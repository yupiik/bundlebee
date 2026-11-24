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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

class ReleaseConfigurationTest {
    @Test
    void preparesKrewChecksumBeforeFilteringAndAttachesBeforeSigning() throws Exception {
        final var root = System.getProperty("bundlebee.root");
        assumeTrue(root != null, "Set bundlebee.root to verify release lifecycle configuration");
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var builder = factory.newDocumentBuilder();
        final var core = builder.parse(Path.of(root, "bundlebee-core/pom.xml").toFile());
        final var parent = builder.parse(Path.of(root, "pom.xml").toFile());
        final var xpath = XPathFactory.newInstance().newXPath();
        for (final var execution :
                new String[] {"native", "kubectl-bundlebee-zip", "krew-sha256", "read-kubectl-bundlebee-sha256"}) {
            assertEquals(
                    "prepare-package",
                    xpath.evaluate(
                            "/project/profiles/profile/build/plugins/plugin/executions/execution[id='" + execution
                                    + "']/phase",
                            core),
                    execution);
        }
        for (final var execution : new String[] {"copy-krew-manifest", "add-krew"}) {
            assertEquals(
                    "package",
                    xpath.evaluate(
                            "/project/profiles/profile/build/plugins/plugin/executions/execution[id='" + execution
                                    + "']/phase",
                            core),
                    execution);
        }
        assertEquals(
                "verify",
                xpath.evaluate(
                        "/project/profiles/profile[id='release']/build/plugins/plugin/executions/execution[id='sign-artifacts']/phase",
                        parent));
    }

    @Test
    void releaseManifestContainsTheActualArchiveChecksumAndHasASignature() throws Exception {
        final var root = System.getProperty("bundlebee.release.root");
        assumeTrue(root != null, "Set bundlebee.release.root after a clean native,Krew,release verify");
        final var target = Path.of(root, "bundlebee-core/target");
        final var archive = target.resolve("bundlebee-core-kubectl-bundlebee.zip");
        final var checksum =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        final var manifest = Files.readString(target.resolve("krew/manifest.yaml"));
        assertTrue(
                manifest.contains("sha256: " + checksum),
                "Manifest must contain the checksum of the exact release archive");
        assertFalse(manifest.contains("${"), "Release manifest must not contain unresolved placeholders");
        final var signature = target.resolve("gpg/krew/manifest.yaml.asc");
        assertTrue(Files.isRegularFile(signature), "Krew manifest must be attached before release signing");
        assertTrue(Files.readString(signature).contains("-----BEGIN PGP SIGNATURE-----"));
    }

    @Test
    void usesCentralPortalInsteadOfRetiredOssrh() throws Exception {
        final var root = System.getProperty("bundlebee.root");
        assumeTrue(root != null, "Set bundlebee.root to verify application release configuration");
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var document =
                factory.newDocumentBuilder().parse(Path.of(root, "pom.xml").toFile());
        final var xpath = XPathFactory.newInstance().newXPath();
        assertEquals("central", xpath.evaluate("/project/distributionManagement/snapshotRepository/id", document));
        assertEquals(
                "https://central.sonatype.com/repository/maven-snapshots/",
                xpath.evaluate("/project/distributionManagement/snapshotRepository/url", document));
        assertEquals("0", xpath.evaluate("count(/project/distributionManagement/repository)", document));
        assertEquals(
                "true",
                xpath.evaluate(
                        "/project/build/plugins/plugin[artifactId='central-publishing-maven-plugin']/extensions",
                        document));
        assertEquals(
                "central",
                xpath.evaluate(
                        "/project/build/plugins/plugin[artifactId='central-publishing-maven-plugin']/configuration/publishingServerId",
                        document));
    }

    @Test
    void permitsAnExplicitSnapshotKrewArchiveLocation() throws Exception {
        final var root = System.getProperty("bundlebee.root");
        assumeTrue(root != null, "Set bundlebee.root to verify Krew configuration");
        final var template = Files.readString(Path.of(root, "bundlebee-core/src/main/krew/manifest.yaml"));
        assertTrue(template.contains("uri: ${bundlebee.krew.archiveUri}"));
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var document = factory.newDocumentBuilder()
                .parse(Path.of(root, "bundlebee-core/pom.xml").toFile());
        final var xpath = XPathFactory.newInstance().newXPath();
        assertEquals(
                "https://repo.maven.apache.org/maven2/io/yupiik/bundlebee-core/${project.version}/bundlebee-core-${project.version}-kubectl-bundlebee.zip",
                xpath.evaluate("/project/profiles/profile[id='krew']/properties/bundlebee.krew.archiveUri", document));
    }
}
