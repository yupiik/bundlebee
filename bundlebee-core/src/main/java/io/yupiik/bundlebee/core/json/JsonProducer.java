package io.yupiik.bundlebee.core.json;

import io.yupiik.bundlebee.core.qualifier.BundleBee;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.json.JsonBuilderFactory;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.config.PropertyOrderStrategy;
import javax.json.spi.JsonProvider;
import java.util.Map;

@ApplicationScoped
public class JsonProducer {
    @Produces
    @BundleBee
    @ApplicationScoped
    public Jsonb jsonb() {
        return JsonbBuilder.create(new JsonbConfig()
                .setProperty("johnzon.skip-cdi", true)
                .withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
    }

    public void release(@Disposes @BundleBee final Jsonb jsonb) {
        try {
            jsonb.close();
        } catch (final Exception e) {
            // no-op: not important since we don't use cdi there
        }
    }

    @Produces
    @BundleBee
    @ApplicationScoped
    public JsonProvider jsonProvider() {
        return JsonProvider.provider();
    }

    @Produces
    @BundleBee
    @ApplicationScoped
    public JsonBuilderFactory jsonBuilderFactory(@BundleBee final JsonProvider provider) {
        return provider.createBuilderFactory(Map.of());
    }
}
