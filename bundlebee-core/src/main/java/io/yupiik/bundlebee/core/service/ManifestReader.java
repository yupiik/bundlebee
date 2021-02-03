package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.qualifier.BundleBee;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.bind.Jsonb;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

@ApplicationScoped
public class ManifestReader {
    @Inject
    @BundleBee
    private Jsonb jsonb;

    public Manifest readManifest(final Supplier<InputStream> manifest) {
        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(manifest.get(), StandardCharsets.UTF_8))) {
            return jsonb.fromJson(reader, Manifest.class);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
