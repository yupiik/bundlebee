package io.yupiik.bundlebee.core.yaml;

import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.yaml.snakeyaml.Yaml;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;

@ApplicationScoped
public class YamlProducer {
    @Produces
    @BundleBee
    @ApplicationScoped
    public Yaml yaml() {
        return new Yaml();
    }
}
