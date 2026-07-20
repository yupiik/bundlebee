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
import javax.json.JsonNumber;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.spi.JsonProvider;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Dependent
//metadata:start
// category = JSON/YAML
//metadata:end
@Description("Decodes a JSON string, throws on error")
public class MustFromJsonFunc implements HelmFunction {
    @Override
    public String name() {
        return "mustFromJson";
    }

    @Override
    public Object execute(final Object... args) {
        if (args == null || args.length < 1 || args[0] == null) {
            return Map.of();
        }
        final var json = args[0].toString();
        try {
            final var provider = JsonProvider.provider();
            try (var reader = provider.createReader(new StringReader(json))) {
                return toJsonValue(reader.read());
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object toJsonValue(final JsonValue value) {
        switch (value.getValueType()) {
            case OBJECT: {
                final var obj = value.asJsonObject();
                final var map = new LinkedHashMap<String, Object>();
                for (final var entry : obj.entrySet()) {
                    map.put(entry.getKey(), toJsonValue(entry.getValue()));
                }
                return map;
            }
            case ARRAY: {
                final var arr = value.asJsonArray();
                final var list = new ArrayList<Object>();
                for (final var item : arr) {
                    list.add(toJsonValue(item));
                }
                return list;
            }
            case STRING:
                return ((JsonString) value).getString();
            case NUMBER: {
                final var number = (JsonNumber) value;
                if (number.isIntegral()) {
                    return number.longValueExact();
                }
                return number.doubleValue();
            }
            case TRUE:
                return true;
            case FALSE:
                return false;
            case NULL:
                return null;
            default:
                return null;
        }
    }
}
