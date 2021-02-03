package io.yupiik.bundlebee.core.json;

import io.yupiik.bundlebee.core.qualifier.BundleBee;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.config.PropertyOrderStrategy;
import javax.json.spi.JsonProvider;

@ApplicationScoped
public class JsonProducer {
    @Produces
    @BundleBee
    @ApplicationScoped
    public Jsonb jsonb() {
        return JsonbBuilder.create(new JsonbConfig().withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
    }

    @Produces
    @BundleBee
    @ApplicationScoped
    public JsonProvider jsonProvider() {
        return JsonProvider.provider();
    }
}
