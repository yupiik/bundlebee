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
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.config.PropertyOrderStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;

@Log
@Dependent
public class AddAlveolusCommand implements Executable {
    @Inject
    @Description("Manifest to add the aveolus inside.")
    @ConfigProperty(name = "bundlebee.add-alveolus.manifest", defaultValue = "./bundlebee/manifest.json")
    private String manifest;

    @Inject
    @Description("Alveolus name.")
    @ConfigProperty(name = "bundlebee.add-alveolus.alveolus", defaultValue = UNSET)
    private String alveolus;

    @Inject
    @Description("Deployment image.")
    @ConfigProperty(name = "bundlebee.add-alveolus.image", defaultValue = UNSET)
    private String image;

    @Inject
    @Description("Alveolus/template type. `web` will create a `ConfigMap`, `Deployment` and `Service`.")
    @ConfigProperty(name = "bundlebee.add-alveolus.type", defaultValue = "web")
    private String type;

    @Inject
    @BundleBee
    private Jsonb jsonb;

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
            throw new IllegalArgumentException("You didn't set --alveolus");
        }
        try {
            switch (type.toLowerCase(ROOT).trim()) {
                case "web":
                    if (UNSET.equals(image)) {
                        throw new IllegalArgumentException("You didn't set --image");
                    }
                    return createWeb();
                default:
                    throw new IllegalArgumentException("Unsupported type: " + type);
            }
        } catch (final Exception ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    private CompletionStage<?> createWeb() throws Exception {
        final var json = Paths.get(manifest);
        if (!Files.exists(json)) {
            throw new IllegalArgumentException("manifest.json does not exist: " + json.normalize().toAbsolutePath());
        }
        final var mf = jsonb.fromJson(Files.readString(json).trim(), Manifest.class);
        if (mf.getAlveoli() == null) {
            mf.setAlveoli(new ArrayList<>());
        } else if (mf.getAlveoli().stream().anyMatch(a -> Objects.equals(alveolus, a.getName()))) {
            throw new IllegalArgumentException("An alveolus is already named '" + alveolus + "'");
        }
        final var k8sFolder = Files.createDirectories(json.getParent().resolve("kubernetes"));
        final var descPrefix = alveolus.replace(':', '_') + '.';
        final var app = alveolus.replace(':', '-');
        addConfigMap(k8sFolder, descPrefix, app);
        addDeployment(k8sFolder, descPrefix, app);
        addService(k8sFolder, descPrefix, app);
        final var alveolus = new Manifest.Alveolus();
        alveolus.setName(this.alveolus);
        alveolus.setDescriptors(Stream.of("configmap", "deployment", "service")
                .map(ext -> new Manifest.Descriptor(null, descPrefix + ext, null, false))
                .collect(toList()));
        mf.getAlveoli().add(alveolus);
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig()
                .setProperty("johnzon.skip-cdi", true)
                .withFormatting(true)
                .withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL))) {
            Files.writeString(json, jsonb.toJson(mf));
        }
        log.info(() -> "Added alveolus '" + this.alveolus + "' to '" + json + "'");
        return completedFuture(true);
    }

    private void addConfigMap(final Path k8sFolder, final String descPrefix, final String app) throws IOException {
        log.info("Created " + Files.writeString(k8sFolder.resolve(descPrefix + "configmap.yaml"), "" +
                "apiVersion: v1\n" +
                "kind: ConfigMap\n" +
                "metadata:\n" +
                "  name: " + app + "-config\n" +
                "  labels:\n" +
                "    app: " + app + "\n" +
                "data:\n" +
                "  # you can drop this variable, it is here for demo purposes\n" +
                "  APP: " + app + "\n" +
                "", StandardOpenOption.CREATE));
    }

    private void addDeployment(final Path k8sFolder, final String descPrefix, final String app) throws IOException {
        log.info("Created " + Files.writeString(k8sFolder.resolve(descPrefix + "deployment.yaml"), "" +
                "apiVersion: apps/v1\n" +
                "kind: Deployment\n" +
                "metadata:\n" +
                "  name: " + app + "-deployment\n" +
                "  labels:\n" +
                "    app: " + app + "\n" +
                "spec:\n" +
                "  replicas: 1\n" +
                "  selector:\n" +
                "    matchLabels:\n" +
                "      app: " + app + "\n" +
                "  template:\n" +
                "    metadata:\n" +
                "      labels:\n" +
                "        app: " + app + "\n" +
                "    spec:\n" +
                "      containers:\n" +
                "        - name: " + app + "\n" +
                "          image: " + (image.contains(":") ? image : (image + ":latest")) + "\n" +
                "          imagePullPolicy: IfNotPresent\n" +
                "          envFrom:\n" +
                "            - configMapRef:\n" +
                "                name: " + app + "-config\n" +
                "          ports:\n" +
                "            - containerPort: 8080\n" +
                "#          livenessProbe:\n" +
                "#            httpGet:\n" +
                "#              path: /api/health/live\n" +
                "#              port: 8080\n" +
                "#            initialDelaySeconds: 3\n" +
                "#            periodSeconds: 5\n" +
                "#          readinessProbe:\n" +
                "#            httpGet:\n" +
                "#              path: /api/health/ready\n" +
                "#              port: 8080\n" +
                "#            initialDelaySeconds: 3\n" +
                "#            periodSeconds: 5\n" +
                "", StandardOpenOption.CREATE));
    }

    private void addService(final Path k8sFolder, final String descPrefix, final String app) throws IOException {
        log.info("Created " + Files.writeString(k8sFolder.resolve(descPrefix + "service.yaml"), "" +
                "apiVersion: v1\n" +
                "kind: Service\n" +
                "metadata:\n" +
                "  name: " + app + "\n" +
                "  labels:\n" +
                "    app: " + app + "\n" +
                "spec:\n" +
                "  type: NodePort\n" +
                "  ports:\n" +
                "    - protocol: TCP\n" +
                "      port: 8080\n" +
                "      targetPort: 8080\n" +
                "# for convenience for testing purposes http://$(minikube ip):31080)\n" +
                "#      nodePort: 31080\n" +
                "  selector:\n" +
                "    app: " + app + "\n" +
                "", StandardOpenOption.CREATE));
    }

}
