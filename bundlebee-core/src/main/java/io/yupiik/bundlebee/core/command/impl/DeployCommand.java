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

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.Maven;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;

@Log
@Dependent
public class DeployCommand implements CompletingExecutable {
    @Inject
    @Description("Project to build.")
    @ConfigProperty(name = "bundlebee.deploy.dir", defaultValue = ".")
    private String directory;

    @Inject
    @Description("Where to build (for build/temp files).")
    @ConfigProperty(name = "bundlebee.deploy.builddir", defaultValue = "target")
    private String buildDirectory;

    @Inject
    @Description("If `true` it will be added to your local maven repository too.")
    @ConfigProperty(name = "bundlebee.deploy.deployInLocalRepository", defaultValue = "true")
    private boolean deployInLocalRepository;

    @Inject
    @Description("If `true` it enables to upload an artifact even if already present. " +
            "When `auto` it enables it for snapshots but not for releases.")
    @ConfigProperty(name = "bundlebee.deploy.enableReUpload", defaultValue = "auto")
    private String enableReUpload;

    @Inject
    @Description("" +
            "If not `skip` it will deploy the artifact on a remote repository (nexus v2 or v3). " +
            "Syntax must be a URL pointing to the REST API (base URL). " +
            "If you don't set the userinfo (username:password) in the " +
            "URL it will be looked up in your settings.xml - potentially ciphered - using the hostname as serverId " +
            "until you force it with `--serverId`.")
    @ConfigProperty(name = "bundlebee.deploy.nexusBaseApi", defaultValue = "skip")
    private String nexusBaseApi;

    @Inject
    @Description("Nexus repository. It is the repository name the artifact(s) will be uploaded to. " +
            "If `auto`, it will be `maven-releases` if the version is a release one and `maven-snapshots` otherwise.")
    @ConfigProperty(name = "bundlebee.deploy.nexusRepository", defaultValue = "auto")
    private String nexusRepository;

    @Inject
    @Description("ServerId to lookup from your maven settings.xml for remote deployment if enabled (nexus authentication).")
    @ConfigProperty(name = "bundlebee.deploy.serverId", defaultValue = UNSET)
    private String serverId;

    @Inject
    @Description("Bundle groupId.")
    @ConfigProperty(name = "bundlebee.deploy.group", defaultValue = UNSET)
    private String group;

    @Inject
    @Description("Bundle artifactId.")
    @ConfigProperty(name = "bundlebee.deploy.artifact", defaultValue = UNSET)
    private String artifact;

    @Inject
    @Description("Bundle version.")
    @ConfigProperty(name = "bundlebee.deploy.version", defaultValue = UNSET)
    private String version;

    @Inject
    private BuildCommand build;

    @Inject
    private Maven mvn;

    @Inject
    @BundleBee
    private HttpClient client;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "deployInLocalRepository":
            case "enableReUpload":
                return Stream.of("false", "true");
            default:
                return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "deploy";
    }

    @Override
    public String description() {
        return "Build and deploy a project. It inherits from most configuration of build command and adds remote repository settings.";
    }

    @Override
    public CompletionStage<?> execute() {
        return build
                .doBuild(directory, buildDirectory, group, artifact, version, deployInLocalRepository)
                .thenCompose(buildResult -> {
                    if (!"skip".equals(nexusBaseApi)) {
                        return doRemoteDeploy(buildResult);
                    }
                    log.severe("Ensure to set --nexusBaseApi to enable the upload.");
                    return completedFuture(null);
                });
    }

    private CompletionStage<?> doRemoteDeploy(final BuildCommand.BuildResult build) {
        final var server = findServer(URI.create(nexusBaseApi));
        final var basic = "Basic " + Base64.getEncoder().encodeToString((server.getUsername() + ':' + server.getPassword()).getBytes(StandardCharsets.UTF_8));
        final var baseUri = nexusBaseApi + (nexusBaseApi.endsWith("/") ? "" : "/");
        return isNexusV2(baseUri, basic)
                .thenCompose(isV2 -> {
                    if (!isV2) {
                        return isNexusV3(baseUri, basic).thenCompose(isV3 -> {
                            if (!isV3) {
                                return isNexusV3Beta(baseUri, basic).thenCompose(isV3Beta -> {
                                    if (!isV3Beta) {
                                        throw new IllegalArgumentException("Unsupported nexus: " + baseUri);
                                    }
                                    return doUpload(baseUri, basic, "repository", build, "PUT");
                                });
                            }
                            return doUpload(baseUri, basic, "repository", build, "PUT");
                        });
                    }
                    return doUpload(baseUri, basic, "content/repositories", build, "POST");
                });
    }

    private CompletionStage<BuildCommand.BuildResult> doUpload(final String baseUri, final String basic, final String endpoint,
                                                               final BuildCommand.BuildResult build, final String uploadMethod) {
        final var repository = "auto".equals(nexusRepository) ? build.getVersion().endsWith("-SNAPSHOT") ? "maven-snapshots" : "maven-releases" : nexusRepository;
        final var uploadEndpoint = URI.create(baseUri + endpoint + '/' +
                repository + '/' +
                build.getGroup().replace('.', '/') + '/' + build.getArtifact() + '/' + build.getVersion() + '/' +
                build.getArtifact() + '-' + build.getVersion() + ".jar");
        return client.sendAsync(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(uploadEndpoint)
                        .header("Authorization", basic)
                        .build(),
                HttpResponse.BodyHandlers.discarding())
                .thenCompose(test -> {
                    switch (test.statusCode()) {
                        case 401:
                            throw new IllegalArgumentException("Invalid authorization");
                        case 404: // upload whatever enableReUpload value is since it is not there
                            try {
                                return doUpload(basic, build, uploadMethod, uploadEndpoint, repository);
                            } catch (final FileNotFoundException e) {
                                throw new IllegalStateException(e);
                            }
                        default:
                            if (("auto".equals(enableReUpload) && build.getVersion().endsWith("-SNAPSHOT")) ||
                                    Boolean.parseBoolean(enableReUpload)) {
                                try {
                                    return doUpload(basic, build, uploadMethod, uploadEndpoint, repository);
                                } catch (final FileNotFoundException e) {
                                    throw new IllegalStateException(e);
                                }
                            }
                            throw new IllegalStateException("" +
                                    "Artifact already uploaded, " +
                                    "ensure to change its version to reupload it or set '--enableReUpload true'");
                    }
                });
    }

    private CompletionStage<BuildCommand.BuildResult> doUpload(final String basic, final BuildCommand.BuildResult build,
                                                               final String uploadMethod, final URI uploadEndpoint,
                                                               final String repository) throws FileNotFoundException {
        return client.sendAsync(
                HttpRequest.newBuilder()
                        .method(uploadMethod, HttpRequest.BodyPublishers.ofFile(build.getJar()))
                        .uri(uploadEndpoint)
                        .header("Authorization", basic)
                        .header("Content-Type", "multipart/form-data")
                        .header("Accept", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(res -> {
                    if (res.statusCode() > 299) {
                        throw new IllegalStateException(
                                "Can't upload artifact: " + res + "\n" + res.body());
                    }
                    log.info("Uploaded " + build.getJar() + " on Nexus repository " + repository);
                    return build;
                });
    }

    private CompletionStage<Boolean> isNexusV3(final String baseUri, final String basic) {
        return client.sendAsync(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create(baseUri + "service/rest/v1/repositories"))
                        .header("Accept", "application/json")
                        .header("Authorization", basic)
                        .build(),
                HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> r.statusCode() == 200);
    }

    private CompletionStage<Boolean> isNexusV3Beta(final String baseUri, final String basic) {
        return client.sendAsync(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create(baseUri + "service/rest/beta/repositories"))
                        .header("Accept", "application/json")
                        .header("Authorization", basic)
                        .build(),
                HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> r.statusCode() == 200);
    }

    private CompletionStage<Boolean> isNexusV2(final String baseUri, final String basic) {
        return client.sendAsync(
                HttpRequest.newBuilder()
                        .GET()
                        .uri(URI.create(baseUri + "service/local/status"))
                        .header("Accept", "application/json")
                        .header("Authorization", basic)
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(r -> {
                    try {
                        return r.statusCode() >= 200 && r.statusCode() <= 299 &&
                                jsonb.fromJson(r.body(), JsonObject.class)
                                        .getJsonObject("data")
                                        .getString("version")
                                        .startsWith("2.");
                    } catch (final RuntimeException re) {
                        log.log(Level.FINEST, re.getMessage(), re);
                        return false;
                    }
                });
    }

    private Maven.Server findServer(final URI uri) {
        final Maven.Server server;
        final var userInfo = uri.getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            final var id = ofNullable(serverId).filter(it -> !UNSET.equals(it)).orElseGet(uri::getHost);
            server = mvn.findServerPassword(id).orElseThrow(() -> new IllegalArgumentException("No maven server found (id=" + id + ")"));
        } else {
            final var sep = userInfo.indexOf(":");
            if (sep <= 0) {
                throw new IllegalArgumentException("Expecting user:pass pattern when setting userinfo in the uri directly.");
            }
            server = new Maven.Server();
            server.setId(uri.getHost());
            server.setUsername(userInfo.substring(0, sep));
            server.setPassword(userInfo.substring(sep + 1));
        }
        return server;
    }
}
