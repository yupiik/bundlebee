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

import javax.enterprise.context.Dependent;

import io.yupiik.bundlebee.core.configuration.Description;
import javax.json.Json;
import javax.json.stream.JsonGenerator;
import java.io.StringWriter;

@Dependent
//metadata:start
// category = JSON/YAML
//metadata:end
@Description("Encodes a value to JSON, returns empty string on null")
public class ToJsonFunc implements io.yupiik.bundlebee.helm.HelmFunction {
    @Override
    public String name() {
        return "toJson";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length == 0) {
            return "";
        }
        final var value = args[0];
        if (value == null) {
            return "";
        }
        return toJson(value);
    }

    private String toJson(final Object value) {
        final var writer = new StringWriter();
        try (var generator = Json.createGenerator(writer)) {
            writeValue(generator, value);
        }
        return writer.toString();
    }

    private void writeValue(final JsonGenerator generator, final Object value) {
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof String) {
            final String s = (String) value;
            generator.write(s);
        } else if (value instanceof Boolean) {
            final Boolean b = (Boolean) value;
            generator.write(b);
        } else if (value instanceof Number) {
            final Number n = (Number) value;
            if (n instanceof java.math.BigDecimal) {
                generator.write((java.math.BigDecimal) n);
            } else if (n instanceof java.math.BigInteger) {
                generator.write((java.math.BigInteger) n);
            } else if (n instanceof Long) {
                generator.write(n.longValue());
            } else if (n instanceof Double) {
                generator.write(n.doubleValue());
            } else if (n instanceof Float) {
                generator.write(n.doubleValue());
            } else {
                generator.write(n.intValue());
            }
        } else if (value instanceof java.util.Map) {
            final java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
            generator.writeStartObject();
            for (final var entry : map.entrySet()) {
                generator.writeKey(String.valueOf(entry.getKey()));
                writeValue(generator, entry.getValue());
            }
            generator.writeEnd();
        } else if (value instanceof java.util.List) {
            final java.util.List<?> list = (java.util.List<?>) value;
            generator.writeStartArray();
            for (final var item : list) {
                writeValue(generator, item);
            }
            generator.writeEnd();
        } else {
            generator.write(value.toString());
        }
    }
}
