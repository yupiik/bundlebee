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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnManifestRead;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.apache.johnzon.jsonb.extension.JsonValueReader;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

@ApplicationScoped
public class ManifestReader {
    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    private Config config;

    @Inject
    private Substitutor substitutor;

    @Inject
    private Event<OnManifestRead> onManifestReadEvent;

    /**
     * @deprecated prefer the flavor with the explicit id as parameter.
     */
    @Deprecated
    public Manifest readManifest(final String location, final Supplier<InputStream> manifest,
                                 final Function<String, InputStream> relativeResolver) {
        return readManifest(location, manifest, relativeResolver, null);
    }

    public Manifest readManifest(final String location, final Supplier<InputStream> manifest,
                                 final Function<String, InputStream> relativeResolver,
                                 final String id) {
        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(manifest.get(), StandardCharsets.UTF_8))) {
            final var content = substitutor.replace(reader.lines().collect(joining("\n")), id);
            final var json = jsonb.fromJson(content, JsonObject.class);
            final Manifest mf;
            if (json.containsKey("bundlebee")) { // it is a wrapped manifest, we enable that to easily enrich manifest.json with custom attributes without breaking jsonschema
                final var subJson = json.getJsonObject("bundlebee");
                mf = jsonb.fromJson(new JsonValueReader<>(subJson), Manifest.class);
            } else {
                mf = jsonb.fromJson(content, Manifest.class);
            }
            if (location != null && !location.isBlank() && mf.getAlveoli() != null) {
                mf.getAlveoli().stream()
                        .map(Manifest.Alveolus::getDescriptors)
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .filter(it -> it.getLocation() == null)
                        .forEach(desc -> desc.setLocation(location));
            }
            resolveReferences(location, mf, relativeResolver, id);
            initInterpolateFlags(mf);
            onManifestReadEvent.fire(new OnManifestRead(mf));
            return mf;
        } catch (final IOException | JsonException | JsonbException e) {
            throw new IllegalStateException("Can't read manifest.json: (location=" + location + ")", e);
        }
    }

    private void initInterpolateFlags(final Manifest manifest) {
        if (manifest.getAlveoli() == null) {
            return;
        }
        manifest.getAlveoli().forEach(alveolus -> {
            final boolean parentValue = alveolus.getInterpolateDescriptors() != null ?
                    alveolus.getInterpolateDescriptors() :
                    (manifest.getInterpolateAlveoli() != null && manifest.getInterpolateAlveoli());
            if (alveolus.getDescriptors() != null) {
                alveolus.getDescriptors().stream()
                        .filter(d -> !d.hasInterpolateValue())
                        .forEach(d -> d.initInterpolate(parentValue));
            }
        });
    }

    private void resolveReferences(final String location, final Manifest main,
                                   final Function<String, InputStream> relativeResolver,
                                   final String id) {
        if (main.getReferences() == null || main.getReferences().isEmpty()) {
            return;
        }

        for (final var ref : main.getReferences()) {
            final var loaded = readManifest(location, () -> relativeResolver.apply(ref.getPath()), relativeResolver, id);
            ofNullable(loaded.getAlveoli())
                    .ifPresent(it -> main.setAlveoli(Stream.concat(
                                    ofNullable(main.getAlveoli()).stream().flatMap(Collection::stream),
                                    it.stream())
                            .collect(toList())));
            ofNullable(loaded.getReferences())
                    .ifPresent(it -> main.setReferences(Stream.concat(
                                    ofNullable(main.getReferences()).stream().flatMap(Collection::stream),
                                    it.stream())
                            .collect(toList())));
        }
    }
}
