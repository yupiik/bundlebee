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

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.service.Maven;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
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
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "deploy",
        description =
                "Build and deploy a project. It inherits from most configuration of build command and adds remote repository settings.")
public class DeployCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(DeployCommand.class.getName());

    public DeployCommand(final Options options, BuildCommand build, Maven mvn, HttpClient client, JsonMapper jsonb) {
        this.directory = options.directory();
        this.buildDirectory = options.buildDirectory();
        this.deployInLocalRepository = options.deployInLocalRepository();
        this.enableReUpload = options.enableReUpload();
        this.nexusBaseApi = options.nexusBaseApi();
        this.nexusRepository = options.nexusRepository();
        this.serverId = options.serverId();
        this.group = options.group();
        this.artifact = options.artifact();
        this.version = options.version();
        this.build = build;
        this.mvn = mvn;
        this.client = client;
        this.jsonb = jsonb;
    }

    private final String directory;
    private final String buildDirectory;
    private final boolean deployInLocalRepository;
    private final String enableReUpload;
    private final String nexusBaseApi;
    private final String nexusRepository;
    private final String serverId;
    private final String group;
    private final String artifact;
    private final String version;
    private final BuildCommand build;
    private final Maven mvn;
    private final HttpClient client;
    private final JsonMapper jsonb;

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
        return build.doBuild(directory, buildDirectory, group, artifact, version, deployInLocalRepository)
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
        final var basic = "Basic "
                + Base64.getEncoder()
                        .encodeToString(
                                (server.getUsername() + ':' + server.getPassword()).getBytes(StandardCharsets.UTF_8));
        final var baseUri = nexusBaseApi + (nexusBaseApi.endsWith("/") ? "" : "/");
        return isNexusV2(baseUri, basic).thenCompose(isV2 -> {
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

    private CompletionStage<BuildCommand.BuildResult> doUpload(
            final String baseUri,
            final String basic,
            final String endpoint,
            final BuildCommand.BuildResult build,
            final String uploadMethod) {
        final var repository = "auto".equals(nexusRepository)
                ? build.getVersion().endsWith("-SNAPSHOT") ? "maven-snapshots" : "maven-releases"
                : nexusRepository;
        final var uploadEndpoint = URI.create(baseUri + endpoint + '/' + repository + '/'
                + build.getGroup().replace('.', '/') + '/' + build.getArtifact() + '/' + build.getVersion() + '/'
                + build.getArtifact() + '-' + build.getVersion() + ".jar");
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
                        case // upload whatever enableReUpload value is since it is not there
                        404:
                            try {
                                return doUpload(basic, build, uploadMethod, uploadEndpoint, repository);
                            } catch (final FileNotFoundException e) {
                                throw new IllegalStateException(e);
                            }
                        default:
                            if (("auto".equals(enableReUpload)
                                            && build.getVersion().endsWith("-SNAPSHOT"))
                                    || Boolean.parseBoolean(enableReUpload)) {
                                try {
                                    return doUpload(basic, build, uploadMethod, uploadEndpoint, repository);
                                } catch (final FileNotFoundException e) {
                                    throw new IllegalStateException(e);
                                }
                            }
                            throw new IllegalStateException("Artifact already uploaded, "
                                    + "ensure to change its version to reupload it or set \'--enableReUpload true\'");
                    }
                });
    }

    private CompletionStage<BuildCommand.BuildResult> doUpload(
            final String basic,
            final BuildCommand.BuildResult build,
            final String uploadMethod,
            final URI uploadEndpoint,
            final String repository)
            throws FileNotFoundException {
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
                        throw new IllegalStateException("Can\'t upload artifact: " + res + "\n" + res.body());
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
                        return r.statusCode() >= 200
                                && r.statusCode() <= 299
                                && jsonb.fromString(NexusStatus.class, r.body())
                                        .data()
                                        .version()
                                        .startsWith("2.");
                    } catch (final RuntimeException re) {
                        log.log(Level.FINEST, re.getMessage(), re);
                        return false;
                    }
                });
    }

    @JsonModel
    public record NexusStatus(NexusData data) {}

    @JsonModel
    public record NexusData(String version) {}

    private Maven.Server findServer(final URI uri) {
        final Maven.Server server;
        final var userInfo = uri.getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            final var id = ofNullable(serverId).filter(it -> !UNSET.equals(it)).orElseGet(uri::getHost);
            server = mvn.findServerPassword(id)
                    .orElseThrow(() -> new IllegalArgumentException("No maven server found (id=" + id + ")"));
        } else {
            final var sep = userInfo.indexOf(":");
            if (sep <= 0) {
                throw new IllegalArgumentException(
                        "Expecting user:pass pattern when setting userinfo in the uri directly.");
            }
            server = new Maven.Server();
            server.setId(uri.getHost());
            server.setUsername(userInfo.substring(0, sep));
            server.setPassword(userInfo.substring(sep + 1));
        }
        return server;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(value = "bundlebee.deploy.dir", defaultValue = "\".\"", documentation = "Project to build.")
            String directory,

            @Property(
                    value = "bundlebee.deploy.builddir",
                    defaultValue = "\"target\"",
                    documentation = "Where to build (for build/temp files).")
            String buildDirectory,

            @Property(
                    value = "bundlebee.deploy.deployInLocalRepository",
                    defaultValue = "true",
                    documentation = "If `true` it will be added to your local maven repository too.")
            boolean deployInLocalRepository,

            @Property(
                    value = "bundlebee.deploy.enableReUpload",
                    defaultValue = "\"auto\"",
                    documentation = "If `true` it enables to upload an artifact even if already present. "
                            + "When `auto` it enables it for snapshots but not for releases.")
            String enableReUpload,

            @Property(
                    value = "bundlebee.deploy.nexusBaseApi",
                    defaultValue = "\"skip\"",
                    documentation =
                            "If not `skip` it will deploy the artifact on a remote repository (nexus v2 or v3). "
                                    + "Syntax must be a URL pointing to the REST API (base URL). "
                                    + "If you don't set the userinfo (username:password) in the "
                                    + "URL it will be looked up in your settings.xml - potentially ciphered - using the hostname as serverId "
                                    + "until you force it with `--serverId`.")
            String nexusBaseApi,

            @Property(
                    value = "bundlebee.deploy.nexusRepository",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Nexus repository. It is the repository name the artifact(s) will be uploaded to. "
                                    + "If `auto`, it will be `maven-releases` if the version is a release one and `maven-snapshots` otherwise.")
            String nexusRepository,

            @Property(
                    value = "bundlebee.deploy.serverId",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "ServerId to lookup from your maven settings.xml for remote deployment if enabled (nexus authentication).")
            String serverId,

            @Property(
                    value = "bundlebee.deploy.group",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle groupId.")
            String group,

            @Property(
                    value = "bundlebee.deploy.artifact",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle artifactId.")
            String artifact,

            @Property(
                    value = "bundlebee.deploy.version",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Bundle version.")
            String version) {}
}
