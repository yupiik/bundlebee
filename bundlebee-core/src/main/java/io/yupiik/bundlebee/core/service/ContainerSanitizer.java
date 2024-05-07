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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.qualifier.BundleBee;
import lombok.extern.java.Log;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonArrayBuilder;
import javax.json.JsonBuilderFactory;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import javax.json.spi.JsonProvider;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.toMap;

@Log
@ApplicationScoped
public class ContainerSanitizer {
    @Inject
    @BundleBee
    private JsonProvider jsonProvider;

    @Inject
    @BundleBee
    private JsonBuilderFactory jsonBuilderFactory;

    public boolean canSanitizeCpuResource(final String kindLowerCased) {
        return "cronjobs".equals(kindLowerCased) || "deployments".equals(kindLowerCased) ||
                "daemonsets".equals(kindLowerCased) || "pods".equals(kindLowerCased) || "jobs".equals(kindLowerCased);
    }

    // for first installation if cpu value is null then it is considered as being 0 - merge patch are ok after
    public JsonObject dropCpuResources(final String kind, final JsonObject preparedDesc) {
        final String containersParentPointer;
        switch (kind) {
            case "deployments":
            case "daemonsets":
            case "jobs":
                containersParentPointer = "/spec/template/spec";
                break;
            case "cronjobs":
                containersParentPointer = "/spec/jobTemplate/spec/template/spec";
                break;
            case "pods":
                containersParentPointer = "/spec";
                break;
            default:
                containersParentPointer = null;
        }

        return replaceIfPresent(
                replaceIfPresent(preparedDesc, containersParentPointer, "initContainers", this::dropNullCpu),
                containersParentPointer, "containers", this::dropNullCpu);
    }

    private JsonValue dropNullCpu(final JsonObject container) {
        final var resources = container.get("resources");
        if (resources == null) {
            return container;
        }

        final var resourcesObj = resources.asJsonObject();
        if (!resourcesObj.containsKey("requests") && !resourcesObj.containsKey("limits")) {
            return container;
        }

        final var builder = jsonBuilderFactory.createObjectBuilder(resourcesObj.entrySet().stream()
                .filter(it -> !"requests".equals(it.getKey()) && !"limits".equals(it.getKey()))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Stream.of("requests", "limits")
                .filter(resourcesObj::containsKey)
                .forEach(k -> {
                    final var subObj = resourcesObj.get(k).asJsonObject();
                    if (!JsonValue.NULL.equals(subObj.get("cpu"))) {
                        builder.add(k, subObj);
                    } else {
                        final var value = jsonBuilderFactory.createObjectBuilder(subObj
                                        .entrySet().stream()
                                        .filter(it -> !"cpu".equals(it.getKey()))
                                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                                .build();
                        if (!value.isEmpty()) {
                            builder.add(k, value);
                        }
                    }
                });
        return jsonBuilderFactory.createObjectBuilder(container.entrySet().stream()
                        .filter(it -> !"resources".equals(it.getKey()))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .add("resources", builder)
                .build();
    }

    private JsonValue dropNullCpu(final JsonValue jsonValue) {
        try {
            return jsonValue.asJsonArray().stream()
                    .map(JsonValue::asJsonObject)
                    .map(this::dropNullCpu)
                    .collect(Collector.of(jsonBuilderFactory::createArrayBuilder, JsonArrayBuilder::add, JsonArrayBuilder::addAll))
                    .build();
        } catch (final RuntimeException re) {
            log.log(FINEST, re, () -> "Can't check null cpu resources: " + re.getMessage());
            return jsonValue;
        }
    }

    private <T extends JsonStructure> T replaceIfPresent(final T source,
                                                         final String parentPtr, final String name,
                                                         final Function<JsonValue, JsonValue> fn) {
        final var rawPtr = parentPtr + '/' + name;
        final var ptr = jsonProvider.createPointer(rawPtr);
        try {
            final var value = ptr.getValue(source);
            final var changed = fn.apply(value);
            if (value == changed) {
                return source;
            }
            return ptr.replace(source, changed);
        } catch (final JsonException je) {
            log.log(FINEST, je, je::getMessage);
            return source;
        }
    }
}
