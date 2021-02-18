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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.lang.Substitutor;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.bind.Jsonb;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static java.util.stream.Collectors.joining;

@ApplicationScoped
public class ManifestReader {
    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    private Config config;

    private final Substitutor substitutor = new Substitutor(k -> config.getOptionalValue(k, String.class).orElse(k));

    public Manifest readManifest(final Supplier<InputStream> manifest) {
        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(manifest.get(), StandardCharsets.UTF_8))) {
            return jsonb.fromJson(substitutor.replace(reader.lines().collect(joining("\n"))), Manifest.class);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
