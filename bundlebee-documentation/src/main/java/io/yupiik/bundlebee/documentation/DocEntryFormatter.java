package io.yupiik.bundlebee.documentation;

import io.yupiik.bundlebee.core.configuration.Description;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.util.function.Function;

import static java.util.Locale.ROOT;

public class DocEntryFormatter {
    public String format(final Field it, final Function<String, String> nameMapper) {
        final var annotation = it.getAnnotation(ConfigProperty.class);
        final var description = it.getAnnotation(Description.class);
        if (description == null) {
            throw new IllegalArgumentException("No @Description on " + it);
        }
        final var desc = description.value();
        var key = annotation.name();
        if (key.isEmpty()) {
            key = it.getDeclaringClass().getName() + "." + it.getName();
        }
        return "" +
                nameMapper.apply(key) + " (`" + key.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(ROOT) + "`" + ")::\n" +
                (desc.endsWith(".") ?
                        desc :
                        (desc + '.')) +
                (!ConfigProperty.UNCONFIGURED_VALUE.equals(annotation.defaultValue()) ?
                        (" Default value: `" + annotation.defaultValue().replace("\n", "\\\n") + "`") :
                        "");
    }
}
