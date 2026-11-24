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

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.event.OnManifestRead;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

@ApplicationScoped
public class ManifestReader {

    public ManifestReader(final JsonMapper jsonb, final Substitutor substitutor, final Emitter onManifestReadEvent) {
        this.jsonb = jsonb;
        this.substitutor = substitutor;
        this.onManifestReadEvent = onManifestReadEvent;
    }

    private final JsonMapper jsonb;
    private final Substitutor substitutor;
    private final Emitter onManifestReadEvent;

    public Manifest readManifest(
            final String location,
            final Supplier<InputStream> manifest,
            final Function<String, InputStream> relativeResolver,
            final String id) {
        try (final BufferedReader reader =
                new BufferedReader(new InputStreamReader(manifest.get(), StandardCharsets.UTF_8))) {
            final var content = substitutor.replace(reader.lines().collect(joining("\n")), id);
            final var parsed = jsonb.fromString(Object.class, content);
            if (!(parsed instanceof Map<?, ?> json)) {
                throw new IllegalArgumentException("Manifest must be an object");
            }
            Manifest mf;
            if (json.containsKey("bundlebee")) {
                // it is a wrapped manifest, we enable that to easily enrich manifest.json with custom attributes
                // without breaking jsonschema
                if (!(json.get("bundlebee") instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("Wrapped manifest must be an object");
                }
                mf = jsonb.fromString(WrappedManifest.class, content).bundlebee();
            } else {
                mf = jsonb.fromString(Manifest.class, content);
            }
            if (location != null && !location.isBlank() && mf.alveoli() != null) {
                mf = mf.withAlveoli(mf.alveoli().stream()
                        .map(alveolus -> alveolus.descriptors() == null
                                ? alveolus
                                : alveolus.withDescriptors(alveolus.descriptors().stream()
                                        .map(descriptor -> descriptor.location() == null
                                                ? descriptor.withLocation(location)
                                                : descriptor)
                                        .collect(toList())))
                        .collect(toList()));
            }
            mf = initInterpolateFlags(resolveReferences(location, mf, relativeResolver, id));
            onManifestReadEvent.emit(new OnManifestRead(mf));
            return mf;
        } catch (final IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Can't read manifest.json: (location=" + location + ")", e);
        }
    }

    private Manifest initInterpolateFlags(final Manifest manifest) {
        if (manifest.alveoli() == null) {
            return manifest;
        }

        return manifest.withAlveoli(manifest.alveoli().stream()
                .map(alveolus -> {
                    final boolean parentValue = alveolus.interpolateDescriptors() != null
                            ? alveolus.interpolateDescriptors()
                            : Boolean.TRUE.equals(manifest.interpolateAlveoli());
                    return alveolus.descriptors() == null
                            ? alveolus
                            : alveolus.withDescriptors(alveolus.descriptors().stream()
                                    .map(descriptor -> descriptor.hasInterpolateValue()
                                            ? descriptor
                                            : descriptor.withInterpolate(parentValue))
                                    .collect(toList()));
                })
                .collect(toList()));
    }

    @JsonModel
    public record WrappedManifest(Manifest bundlebee) {}

    private Manifest resolveReferences(
            final String location,
            final Manifest main,
            final Function<String, InputStream> relativeResolver,
            final String id) {
        if (main.references() == null || main.references().isEmpty()) {
            return main;
        }
        var result = main;
        for (final var ref : main.references()) {
            final var loaded = readManifest(location, () -> relativeResolver.apply(ref.path()), relativeResolver, id);
            if (loaded.alveoli() != null) {
                result = result.withAlveoli(Stream.concat(
                                ofNullable(result.alveoli()).stream().flatMap(Collection::stream),
                                loaded.alveoli().stream())
                        .collect(toList()));
            }
            if (loaded.references() != null) {
                result = result.withReferences(Stream.concat(
                                ofNullable(result.references()).stream().flatMap(Collection::stream),
                                loaded.references().stream())
                        .collect(toList()));
            }
        }
        return result;
    }
}
