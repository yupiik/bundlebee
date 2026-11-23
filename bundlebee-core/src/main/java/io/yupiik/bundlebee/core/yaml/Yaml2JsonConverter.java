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
package io.yupiik.bundlebee.core.yaml;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.composer.ComposerException;

@ApplicationScoped
public class Yaml2JsonConverter {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneId.of("UTC"));

    public Yaml2JsonConverter(final RuntimeContainer container, final JsonMapper jsonb) {
        this.container = container;
        this.jsonb = jsonb;
    }

    private final RuntimeContainer container;
    private final JsonMapper jsonb;

    public <T> T convert(final Class<T> expected, final String content) {
        final Object loaded;
        try (final var instance = container.lookup(Yaml.class)) {
            final var yaml = instance.instance();
            try {
                loaded = normalize(yaml.load(content));
            } catch (final ComposerException ce) {
                if (expected == Object.class || expected == List.class) {
                    return expected.cast(
                            StreamSupport.stream(yaml.loadAll(content).spliterator(), false)
                                    .map(this::normalize)
                                    .toList());
                }
                throw ce;
            }
        }
        if (expected == Object.class || expected == Map.class || expected == List.class) {
            return expected.cast(loaded);
        }
        return jsonb.fromString(expected, jsonb.toString(loaded));
    }

    private Object normalize(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                // The previous JSON-B conversion omitted null object members, but
                // retained null array elements. Keep that descriptor wire behavior.
                if (key instanceof String name && item != null) {
                    result.put(name, normalize(item));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalize).toList();
        }
        if (value instanceof Date date) {
            // JSON-B formatted SnakeYAML timestamps in the named UTC zone, regardless of the JVM default.
            return TIMESTAMP_FORMAT.format(date.toInstant());
        }
        if (value instanceof byte[] bytes) {
            // JSON-B used signed numeric arrays for !!binary, not base64 strings or unsigned bytes.
            return IntStream.range(0, bytes.length)
                    .map(index -> bytes[index])
                    .boxed()
                    .toList();
        }
        if (value instanceof Number number) {
            // Match Fusion's generic JSON number representation without a serialization round trip.
            return new BigDecimal(number.toString());
        }
        return value;
    }
}
