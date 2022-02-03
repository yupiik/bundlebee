/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.lang.Substitutor;

import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.list;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class OperatorGenerator implements Runnable {
    private final Path sourceBase;

    public OperatorGenerator(final Path sourceBase) {
        this.sourceBase = sourceBase;
    }

    @Override
    public void run() {
        try {
            final var alveolus = findAlveolus();
            final var root = Files.createDirectories(sourceBase.resolve("content/_partials/generated"));
            Files.writeString(root.resolve("operator.allinone.json"), createAllInOneYaml(alveolus));
            Files.writeString(root.resolve("operator.configuration.adoc"), bundlebeePlaceholders(alveolus));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String bundlebeePlaceholders(final Manifest.Alveolus alveolus) {
        final var loader = Thread.currentThread().getContextClassLoader();
        return alveolus.getDescriptors().stream()
                .flatMap(d -> {
                    final var map = new HashMap<String, String>();
                    try (final var stream = findDescriptor(loader, d)) {
                        new Substitutor((k, v) -> {
                            map.put(k, v);
                            return v;
                        }).replace(new String(stream.readAllBytes(), UTF_8));
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return map.entrySet().stream();
                })
                .distinct()
                .map(e -> {
                    final var start = e.getKey() + ":: ";
                    final var end = " Default value: `" + e.getValue() + "`.";
                    final String desc;
                    switch (e.getKey()) {
                        case "bundlebee.operator.crd.scope":
                            desc = "scope of the operator.";
                            break;
                        case "bundlebee.operator.namespace":
                            desc = "namespace of the operator.";
                            break;
                        case "bundlebee.operator.deployment.dryRun":
                            desc = "is dry run mode enabled (for testing purposes).";
                            break;
                        case "bundlebee.operator.deployment.http.threads":
                            desc = "how many threads are allocated to the http client.";
                            break;
                        case "bundlebee.operator.deployment.downloads.enabled":
                            desc = "can the operator download alveoli or should it use the local provisionned maven repository only (kind of explicit enablement of dependencies).";
                            break;
                        case "bundlebee.operator.deployment.verbose":
                            desc = "does the operator log or not the HTTP requests/responses it does (for debug purposes).";
                            break;
                        default:
                            throw new IllegalArgumentException("unknown placeholder: '" + e.getKey() + "'");
                    }
                    return start + desc + end;
                })
                .collect(joining("\n\n"));
    }

    private InputStream findDescriptor(final ClassLoader loader, final Manifest.Descriptor d) {
        return requireNonNull(
                loader.getResourceAsStream("bundlebee/kubernetes/" + d.getName()),
                () -> d.getName() + " not found");
    }

    private String createAllInOneYaml(final Manifest.Alveolus alveolus) {
        final var loader = Thread.currentThread().getContextClassLoader();
        return alveolus.getDescriptors().stream()
                .map(d -> {
                    try (final var stream = findDescriptor(loader, d)) {
                        return new Substitutor((k, v) -> v).replace(new String(stream.readAllBytes(), UTF_8));
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                // funny but k8s does not use the yaml "---" nor a json list, just a concatenation of JSON objects
                .collect(joining("\n"));
    }

    private Manifest.Alveolus findAlveolus() throws Exception {
        final var manifest = list(Thread.currentThread().getContextClassLoader().getResources("bundlebee/manifest.json")).stream()
                .filter(url -> {
                    final var it = org.apache.xbean.finder.util.Files.toFile(url).toPath();
                    var current = it;
                    if (!current.getFileName().toString().endsWith(".jar")) {
                        final var unwrapped = List.of("manifest.json", "bundlebee", "classes", "target").iterator();
                        while (current != null && unwrapped.hasNext() && current.getFileName().toString().equals(unwrapped.next())) {
                            current = current.getParent();
                        }
                    }
                    return current != null && current.getFileName().toString().startsWith("bundlebee-operator");
                })
                .map(it -> {
                    try {
                        return it.openStream();
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No manifest.json for bundlebee-operator"));

        try (final var jsonb = JsonbBuilder.create(new JsonbConfig().setProperty("johnzon-.skip-cdi", true))) {
            final var mf = jsonb.fromJson(manifest, Manifest.class);
            if (mf.getAlveoli().size() != 1) {
                throw new IllegalArgumentException("Invalid manifest for bundlebee operator");
            }
            return mf.getAlveoli().iterator().next();
        }
    }
}
