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
package io.yupiik.bundlebee.helm.fn;

import io.yupiik.bundlebee.helm.HelmFunction;

import javax.enterprise.context.Dependent;

import io.yupiik.bundlebee.core.configuration.Description;
import javax.json.Json;
import javax.json.JsonValue;
import javax.json.spi.JsonProvider;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;

import java.io.StringWriter;
import java.util.Map;

@Dependent
//metadata:start
// category = JSON/YAML
//metadata:end
@Description("Encodes a value to pretty JSON")
public class ToPrettyJsonFunc implements HelmFunction {
    @Override
    public String name() {
        return "toPrettyJson";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1 || args[0] == null) {
            return "{}";
        }
        try {
            final var provider = JsonProvider.provider();
            final var writer = new StringWriter();
            final var factory = provider.createGeneratorFactory(
                    Map.of(JsonGenerator.PRETTY_PRINTING, true));
            try (var generator = factory.createGenerator(writer)) {
                generator.write(toJsonValue(args[0]));
            }
            return writer.toString();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonValue toJsonValue(final Object value) {
        final var provider = JsonProvider.provider();
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof Map) {
            final var builder = provider.createObjectBuilder();
            for (final var entry : ((Map<?, ?>) value).entrySet()) {
                builder.add(entry.getKey().toString(), toJsonValue(entry.getValue()));
            }
            return builder.build();
        }
        if (value instanceof Iterable) {
            final var builder = provider.createArrayBuilder();
            for (final var item : (Iterable<?>) value) {
                builder.add(toJsonValue(item));
            }
            return builder.build();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? JsonValue.TRUE : JsonValue.FALSE;
        }
        if (value instanceof Number) {
            final var num = (Number) value;
            if (value instanceof Double || value instanceof Float) {
                return provider.createValue(num.doubleValue());
            }
            return provider.createValue(num.longValue());
        }
        return provider.createValue(value.toString());
    }
}
