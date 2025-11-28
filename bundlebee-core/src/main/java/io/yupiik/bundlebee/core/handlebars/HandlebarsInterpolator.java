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
package io.yupiik.bundlebee.core.handlebars;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.compiler.accessor.MapAccessor;

import javax.enterprise.inject.Vetoed;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

@Vetoed
public class HandlebarsInterpolator {
    private final Manifest.Alveolus alveolus;
    private final AlveolusHandler.LoadedDescriptor descriptor;
    private final String id;
    private final Map<String, Function<Object, String>> helpers;
    private final BiFunction<String, String, String> defaultLookup;

    public HandlebarsInterpolator(final Manifest.Alveolus alveolus,
                                  final AlveolusHandler.LoadedDescriptor descriptor,
                                  final String id,
                                  final Map<String, Function<Object, String>> customHelpers,
                                  final BiFunction<String, String, String> defaultLookup) {
        this.alveolus = alveolus;
        this.descriptor = descriptor;
        this.id = id;
        this.helpers = customHelpers;
        this.defaultLookup = defaultLookup;
    }

    public String apply(final String template) {
        final Map<String, Function<Object, String>> specificHelpers = helpers.entrySet().stream()
                .collect(toMap(
                        Map.Entry::getKey,
                        // inject the id as last param
                        p -> o -> p.getValue().apply(new Object[]{o, id})));
        specificHelpers.put("base64", a -> Base64.getEncoder().encodeToString(a.toString().getBytes(UTF_8)));
        specificHelpers.put("base64url", a -> Base64.getUrlEncoder().withoutPadding().encodeToString(a.toString().getBytes(UTF_8)));

        final var rootData = Map.of(
                "alveolus", alveolus,
                "descriptor", descriptor,
                "executionId", id == null ? "" : id);
        return new HandlebarsCompiler(
                new MapAccessor() {
                    @Override
                    public Object find(final Object data, final String name) {
                        if (data instanceof String) { // standard placeholder?
                            return doDefaultLookup(data + "." + name);
                        }

                        final var found = super.find(data, name);
                        if (found == null) {
                            return doDefaultLookup(name);
                        }

                        if (found instanceof Manifest.Alveolus) {
                            return asMap((Manifest.Alveolus) found);
                        }
                        if (found instanceof AlveolusHandler.LoadedDescriptor) {
                            return asMap((AlveolusHandler.LoadedDescriptor) found);
                        }
                        return found;
                    }
                })
                .compile(new HandlebarsCompiler.CompilationContext(new HandlebarsCompiler.Settings().helpers(specificHelpers), template))
                .render(rootData);
    }

    private String doDefaultLookup(final String name) {
        final var value = defaultLookup.apply("{{" + name + "}}", id);
        if (value == null || "null".equals(value)) {
            return name;
        }
        return value;
    }

    private Map<Object, Object> asMap(final Manifest.Alveolus alveolus) {
        final var map = new HashMap<>();
        map.put("name", alveolus.getName());
        if (alveolus.getVersion() != null) {
            map.put("version", alveolus.getVersion());
        }
        if (alveolus.getPlaceholders() != null) {
            map.putAll(alveolus.getPlaceholders());
        }
        return map;
    }

    private Map<Object, Object> asMap(final AlveolusHandler.LoadedDescriptor descriptor) {
        final var map = new HashMap<>();
        map.put("name", descriptor.getConfiguration().getName());
        if (descriptor.getConfiguration().getLocation() != null) {
            map.put("location", descriptor.getConfiguration().getLocation());
        }
        if (descriptor.getConfiguration().getType() != null) {
            map.put("type", descriptor.getConfiguration().getType());
        }
        return map;
    }
}
