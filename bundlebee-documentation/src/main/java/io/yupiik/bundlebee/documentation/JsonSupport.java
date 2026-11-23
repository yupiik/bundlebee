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
package io.yupiik.bundlebee.documentation;

import java.util.List;
import java.util.Map;

/** Generic JSON trees used by the offline documentation generators. */
public final class JsonSupport {
    private JsonSupport() {
        // no instance
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected a JSON object, got " + value);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(final Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Expected a JSON array, got " + value);
        }
        return (List<Object>) value;
    }

    public static Map<String, Object> object(final Map<String, Object> value, final String key) {
        return object(value.get(key));
    }

    public static List<Object> array(final Map<String, Object> value, final String key) {
        return array(value.getOrDefault(key, List.of()));
    }

    public static String string(final Map<String, Object> value, final String key) {
        return (String) value.get(key);
    }
}
