package io.yupiik.bundlebee.core.yaml;

import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.yaml.snakeyaml.Yaml;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.bind.Jsonb;

@ApplicationScoped
public class Yaml2JsonConverter {
    @Inject
    @BundleBee
    private Yaml yaml;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    public <T> T convert(final Class<T> expected, final String content) {
        final var loaded = yaml.load(content);
        return jsonb.fromJson(jsonb.toJson(loaded), expected);
    }
}
