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

import static java.util.Locale.ROOT;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.command.AddAlveolusTypeHandler;
import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@DefaultScoped
@Command(
        name = "add-alveolus",
        description = "Adds a service to the alveoli available in the defined folder. It is a skaffolding command.")
public class AddAlveolusCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(AddAlveolusCommand.class.getName());

    public AddAlveolusCommand(final Options options, JsonMapper jsonb, Components components) {
        this.manifest = options.manifest();
        this.alveolus = options.alveolus();
        this.image = options.image();
        this.type = options.type();
        this.overwrite = options.overwrite();
        this.jsonb = jsonb;
        this.typeHandlers = components.values();
    }

    private final String manifest;
    private final String alveolus;
    private final String image;
    private final String type;
    private final boolean overwrite;
    private final JsonMapper jsonb;
    private final List<AddAlveolusTypeHandler> typeHandlers;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "type":
                return Stream.concat(Stream.of("web"), types().map(AddAlveolusTypeHandler::name))
                        .distinct()
                        .sorted();
            case "overwrite":
                return Stream.of("false", "true");
            case "alveolus":
                return Stream.ofNullable(manifest).flatMap(m -> {
                    final var json = Paths.get(manifest);
                    if (!Files.exists(json)) {
                        return Stream.empty();
                    }
                    try {
                        final var mf = jsonb.fromString(
                                Manifest.class, Files.readString(json).trim());
                        if (mf.alveoli() != null) {
                            return mf.alveoli().stream()
                                    .map(Manifest.Alveolus::name)
                                    .sorted();
                        }
                    } catch (final RuntimeException | IOException re) {
                    }
                    // no-op
                    return Stream.empty();
                });
            default:
                return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "add-alveolus";
    }

    @Override
    public String description() {
        return "Adds a service to the alveoli available in the defined folder. It is a skaffolding command.";
    }

    @Override
    public CompletionStage<?> execute() {
        if (UNSET.equals(alveolus)) {
            throw new IllegalArgumentException("You didn\'t set --alveolus");
        }
        try {
            if ("web".equals(type.toLowerCase(ROOT).trim())) {
                if (UNSET.equals(image)) {
                    throw new IllegalArgumentException("You didn\'t set --image");
                }
                return createWeb();
            }
            return types().filter(it -> type.equals(it.name()))
                    .findFirst()
                    .map(AddAlveolusTypeHandler::handle)
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported type: " + type));
        } catch (final Exception ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    // typeHandlers.stream() but workaround for mvn 3.6 (<4)
    private Stream<AddAlveolusTypeHandler> types() {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(typeHandlers.iterator(), Spliterator.IMMUTABLE), false);
    }

    private CompletionStage<?> createWeb() throws Exception {
        final var json = Paths.get(manifest);
        if (!Files.exists(json)) {
            throw new IllegalArgumentException(
                    "manifest.json does not exist: " + json.normalize().toAbsolutePath());
        }
        final var mf = jsonb.fromString(Manifest.class, Files.readString(json).trim());
        if (mf.alveoli() != null && mf.alveoli().stream().anyMatch(a -> Objects.equals(alveolus, a.name()))) {
            throw new IllegalArgumentException("An alveolus is already named \'" + alveolus + "\'");
        }
        final var k8sFolder = Files.createDirectories(json.getParent().resolve("kubernetes"));
        final var descPrefix = alveolus.replace(':', '_') + '.';
        final var app = alveolus.replace(':', '-');
        addConfigMap(k8sFolder, descPrefix, app);
        addDeployment(k8sFolder, descPrefix, app);
        addService(k8sFolder, descPrefix, app);
        final var alveolus = new Manifest.Alveolus(
                null,
                this.alveolus,
                null,
                Stream.of("configmap", "deployment", "service")
                        .map(ext -> new Manifest.Descriptor(
                                null, descPrefix + ext, null, null, false, false, null, false, null))
                        .collect(toList()),
                null,
                false,
                null,
                null,
                null);
        final var updated = mf.withAlveoli(
                Stream.concat(mf.alveoli() == null ? Stream.empty() : mf.alveoli().stream(), Stream.of(alveolus))
                        .collect(toList()));
        Files.writeString(json, new io.yupiik.fusion.json.pretty.PrettyJsonMapper(jsonb).toString(updated));
        log.info(() -> "Added alveolus \'" + this.alveolus + "\' to \'" + json + "\'");
        return completedFuture(true);
    }

    private void addConfigMap(final Path k8sFolder, final String descPrefix, final String app) throws IOException {
        log.info("Created "
                + writeString(
                        k8sFolder.resolve(descPrefix + "configmap.yaml"),
                        "apiVersion: v1\n" + "kind: ConfigMap\n" + "metadata:\n" + "  name: " + app + "-config\n"
                                + "  labels:\n" + "    app: " + app + "\n" + "data:\n"
                                + "  # you can drop this variable, it is here for demo purposes\n" + "  APP: " + app
                                + "\n" + "",
                        StandardOpenOption.CREATE));
    }

    private void addDeployment(final Path k8sFolder, final String descPrefix, final String app) throws IOException {
        log.info("Created "
                + writeString(
                        k8sFolder.resolve(descPrefix + "deployment.yaml"),
                        "apiVersion: apps/v1\n" + "kind: Deployment\n" + "metadata:\n" + "  name: " + app
                                + "-deployment\n" + "  labels:\n" + "    app: " + app + "\n" + "spec:\n"
                                + "  replicas: 1\n" + "  selector:\n" + "    matchLabels:\n" + "      app: " + app
                                + "\n" + "  template:\n" + "    metadata:\n" + "      labels:\n" + "        app: " + app
                                + "\n" + "      annotations:\n" + "        prometheus.io/scrape: \'false\'\n"
                                + "        prometheus.io/port: \'8080\'\n"
                                + "        prometheus.io/path: \'/metrics\'\n" + "    spec:\n" + "      containers:\n"
                                + "        - name: " + app + "\n" + "          image: "
                                + (image.contains(":") ? image : (image + ":latest")) + "\n"
                                + "          imagePullPolicy: IfNotPresent\n" + "          envFrom:\n"
                                + "            - configMapRef:\n" + "                name: " + app + "-config\n"
                                + "          ports:\n" + "            - containerPort: 8080\n"
                                + "#          livenessProbe:\n" + "#            httpGet:\n"
                                + "#              path: /api/health/live\n" + "#              port: 8080\n"
                                + "#            initialDelaySeconds: 3\n" + "#            periodSeconds: 5\n"
                                + "#          readinessProbe:\n" + "#            httpGet:\n"
                                + "#              path: /api/health/ready\n" + "#              port: 8080\n"
                                + "#            initialDelaySeconds: 3\n" + "#            periodSeconds: 5\n" + "",
                        StandardOpenOption.CREATE));
    }

    private void addService(final Path k8sFolder, final String descPrefix, final String app) throws IOException {
        log.info("Created "
                + writeString(
                        k8sFolder.resolve(descPrefix + "service.yaml"),
                        "apiVersion: v1\n" + "kind: Service\n" + "metadata:\n" + "  name: " + app + "\n" + "  labels:\n"
                                + "    app: " + app + "\n" + "spec:\n" + "  type: NodePort\n" + "  ports:\n"
                                + "    - protocol: TCP\n" + "      port: 8080\n" + "      targetPort: 8080\n"
                                + "# for convenience for testing purposes http://$(minikube ip):31080)\n"
                                + "#      nodePort: 31080\n" + "  selector:\n" + "    app: " + app + "\n" + "",
                        StandardOpenOption.CREATE));
    }

    private String writeString(final Path to, final String what, final StandardOpenOption... opts) throws IOException {
        if (!overwrite && Files.exists(to)) {
            log.info("Skipping " + to + " since it already exists and overwrite=false");
            return to.toString();
        }
        return Files.writeString(to, what, opts).toString();
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.add-alveolus.manifest",
                    defaultValue = "\"./bundlebee/manifest.json\"",
                    documentation = "Manifest to add the aveolus inside.")
            String manifest,

            @Property(
                    value = "bundlebee.add-alveolus.alveolus",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Alveolus name.")
            String alveolus,

            @Property(
                    value = "bundlebee.add-alveolus.image",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation = "Deployment image.")
            String image,

            @Property(
                    value = "bundlebee.add-alveolus.type",
                    defaultValue = "\"web\"",
                    documentation =
                            "Alveolus/template type. `web` will create a `ConfigMap`, `Deployment` and `Service`.")
            String type,

            @Property(
                    value = "bundlebee.add-alveolus.overwrite",
                    defaultValue = "false",
                    documentation = "If `false`, generated files already existing will be skipped.")
            boolean overwrite) {}

    @DefaultScoped
    public record Components(List<AddAlveolusTypeHandler> values) {}
}
