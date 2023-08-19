/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.qualifier.BundleBee;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.composer.ComposerException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonValue;
import javax.json.bind.Jsonb;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.joining;

@ApplicationScoped
public class Yaml2JsonConverter {
    @Inject
    @BundleBee
    private Yaml yaml;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    public <T> T convert(final Class<T> expected, final String content) {
        try {
            final var loaded = yaml.load(content);
            return jsonb.fromJson(jsonb.toJson(loaded), expected);
        } catch (final ComposerException ce) {
            if (expected == JsonValue.class || expected == JsonArray.class) {
                final var loaded = yaml.loadAll(content);
                return jsonb.fromJson(
                        StreamSupport.stream(loaded.spliterator(), false)
                                .map(it -> jsonb.toJson(it))
                                .collect(joining(",", "[", "]")),
                        expected);
            }
            throw ce;
        }
    }
}
