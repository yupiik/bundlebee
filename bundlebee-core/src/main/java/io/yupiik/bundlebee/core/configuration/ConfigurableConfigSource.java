package io.yupiik.bundlebee.core.configuration;

import lombok.Getter;
import org.eclipse.microprofile.config.spi.ConfigSource;

import javax.enterprise.inject.Vetoed;
import java.util.HashMap;
import java.util.Map;

@Vetoed
public class ConfigurableConfigSource implements ConfigSource {
    @Getter(onMethod_ = @Override)
    private final Map<String, String> properties = new HashMap<>();

    @Override
    public String getValue(final String propertyName) {
        return getProperties().get(propertyName);
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }
}
