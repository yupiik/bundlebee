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

import static java.util.logging.Level.FINEST;
import static java.util.stream.Collectors.toList;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class ContainerSanitizer {
    private final Logger log = Logger.getLogger(ContainerSanitizer.class.getName());

    public boolean canSanitizeCpuResource(final String kindLowerCased) {
        return "cronjobs".equals(kindLowerCased)
                || "deployments".equals(kindLowerCased)
                || "daemonsets".equals(kindLowerCased)
                || "pods".equals(kindLowerCased)
                || "jobs".equals(kindLowerCased);
    }

    // for first installation if cpu value is null then it is considered as being 0 - merge patch are ok after
    public Map<String, Object> dropCpuResources(final String kind, final Map<String, Object> preparedDesc) {
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
                return preparedDesc;
        }
        return replaceIfPresent(
                replaceIfPresent(preparedDesc, containersParentPointer, "initContainers", this::dropKnownNulls),
                containersParentPointer,
                "containers",
                this::dropKnownNulls);
    }

    private Object dropKnownNulls(final Map<String, Object> container) {
        return dropIfNull(
                dropNullsCpu(container), // this is important cause there is no default which can inherit the parent
                // otherwise
                "args",
                "command");
    }

    private Map<String, Object> dropIfNull(final Map<String, Object> container, final String... keys) {
        final var toRemove = Stream.of(keys)
                .filter(it -> {
                    final var jsonValue = container.get(it);
                    return container.containsKey(it) && jsonValue == null;
                })
                .collect(toList());
        if (toRemove.isEmpty()) {
            return container;
        }
        final var result = new LinkedHashMap<>(container);
        toRemove.forEach(result::remove);
        return result;
    }

    private Map<String, Object> dropNullsCpu(final Map<String, Object> container) {
        final var resources = container.get("resources");
        if (!(resources instanceof Map<?, ?> resourcesObj)) {
            return container;
        }
        if (!resourcesObj.containsKey("requests") && !resourcesObj.containsKey("limits")) {
            return container;
        }
        final var builder = new LinkedHashMap<Object, Object>(resourcesObj);
        for (final var key : List.of("requests", "limits")) {
            if (resourcesObj.get(key) instanceof Map<?, ?> subObj && subObj.get("cpu") == null) {
                final var value = new LinkedHashMap<>(subObj);
                value.remove("cpu");
                if (value.isEmpty()) {
                    builder.remove(key);
                } else {
                    builder.put(key, value);
                }
            }
        }
        final var result = new LinkedHashMap<>(container);
        result.put("resources", builder);
        return result;
    }

    private Object dropKnownNulls(final Object jsonValue) {
        if (!(jsonValue instanceof List<?> containers)) {
            return jsonValue;
        }
        final var result = new ArrayList<Object>(containers.size());
        for (final var item : containers) {
            if (item instanceof Map<?, ?> container
                    && container.keySet().stream().allMatch(String.class::isInstance)) {
                @SuppressWarnings("unchecked")
                final var typed = (Map<String, Object>) container;
                result.add(dropKnownNulls(typed));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private <T extends Object> T replaceIfPresent(
            final T source, final String parentPtr, final String name, final Function<Object, Object> fn) {
        final var rawPtr = parentPtr + '/' + name;
        final var ptr = new GenericJsonPointer(rawPtr);
        try {
            final var value = ptr.apply(source);
            final var changed = fn.apply(value);
            if (value == changed) {
                return source;
            }
            return (T) ptr.add(source, changed);
        } catch (final IllegalArgumentException | IllegalStateException je) {
            log.log(FINEST, je, je::getMessage);
            return source;
        }
    }
}
