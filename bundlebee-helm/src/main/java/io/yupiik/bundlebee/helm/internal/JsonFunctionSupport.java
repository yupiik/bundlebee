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
package io.yupiik.bundlebee.helm.internal;

import io.yupiik.bundlebee.helm.HelmFunction;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pretty.PrettyJsonMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts template values to Fusion's generic JSON model without requiring codecs for arbitrary objects.
 * The injected mapper is borrowed, not owned by functions.
 */
public abstract class JsonFunctionSupport implements HelmFunction {
    private final JsonMapper mapper;
    private final JsonMapper prettyMapper;

    protected JsonFunctionSupport(final JsonMapper mapper) {
        this.mapper = mapper;
        this.prettyMapper = new PrettyJsonMapper(mapper);
    }

    protected String encode(final Object value, final boolean pretty, final boolean iterableValues) {
        final var normalized = toJsonValue(value, iterableValues);
        if (normalized == null) {
            return "null";
        }
        // Fusion's direct BigDecimal codec writes JSON strings, while its Object codec
        // preserves numeric values. A generated Object field selects that dynamic codec
        // without custom codecs or implementation API dependencies. This also bypasses
        // homogeneous-map specialization for heterogeneous nested collections.
        final var valueModel = new JsonValue(normalized);
        if (pretty) {
            final var envelope = prettyMapper.toString(valueModel);
            return envelope.substring("{\n  \"value\": ".length(), envelope.length() - 2)
                    .indent(-2)
                    .stripTrailing();
        }
        final var envelope = mapper.toString(valueModel);
        return envelope.substring("{\"value\":".length(), envelope.length() - 1);
    }

    protected Object decode(final String json) {
        return fromJsonValue(mapper.fromString(Object.class, json));
    }

    private Object toJsonValue(final Object value, final boolean iterableValues) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if ((number instanceof Double || number instanceof Float) && !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("Not a finite JSON number: " + number);
            }
            if (iterableValues) {
                if (number instanceof Double || number instanceof Float) {
                    return number.doubleValue();
                }
                return number.longValue();
            }
            if (number instanceof BigInteger integer) {
                return new BigDecimal(integer);
            }
            if (number instanceof BigDecimal || number instanceof Long) {
                return number;
            }
            if (number instanceof Double || number instanceof Float) {
                return number.doubleValue();
            }
            return number.intValue();
        }
        if (value instanceof Map<?, ?> map) {
            final var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), toJsonValue(item, iterableValues)));
            return result;
        }
        if (value instanceof List<?> || (iterableValues && value instanceof Iterable<?>)) {
            final var result = new ArrayList<Object>();
            for (final var item : (Iterable<?>) value) {
                result.add(toJsonValue(item, iterableValues));
            }
            return result;
        }
        return value.toString();
    }

    private Object fromJsonValue(final Object value) {
        if (value instanceof BigDecimal number) {
            if (number.scale() == 0) {
                return number.longValueExact();
            }
            return number.doubleValue();
        }
        if (value instanceof Map<?, ?> map) {
            final var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), fromJsonValue(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            final var result = new ArrayList<Object>();
            for (final var item : list) {
                result.add(fromJsonValue(item));
            }
            return result;
        }
        return value;
    }

    @JsonModel
    public record JsonValue(Object value) {}
}
