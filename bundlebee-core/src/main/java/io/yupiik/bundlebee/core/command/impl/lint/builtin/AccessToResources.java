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
package io.yupiik.bundlebee.core.command.impl.lint.builtin;

import io.yupiik.bundlebee.core.command.impl.lint.ContextualLintError;
import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.bundlebee.core.lang.Tuple2;

import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.WARNING;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static javax.json.JsonValue.EMPTY_JSON_ARRAY;
import static javax.json.JsonValue.ValueType.STRING;

public abstract class AccessToResources extends CheckByKind {
    protected static final Tuple2<List<String>, List<String>> EMPTY_TUPLE2 = new Tuple2<>(List.of(), List.of());

    private final Set<String> resources;
    private final Set<String> verb;

    // state
    private final Map<String, LintableDescriptor> roles = new ConcurrentHashMap<>();
    private final Map<String, LintableDescriptor> clusterRoles = new ConcurrentHashMap<>();
    private final Collection<LintableDescriptor> roleBindings = new CopyOnWriteArrayList<>();
    private final Collection<LintableDescriptor> clusterRoleBindings = new CopyOnWriteArrayList<>();

    protected AccessToResources(final Set<String> resources, final Set<String> verb, final Set<String> objectKinds) {
        super(objectKinds);
        this.resources = resources;
        this.verb = verb;
    }

    @Override
    public boolean accept(final LintableDescriptor descriptor) { // capture role related descriptors
        try {
            switch (descriptor.kind()) {
                case "Role":
                    roles.put(descriptor.namespace() + ":" + descriptor.name(), descriptor);
                    break;
                case "ClusterRole":
                    clusterRoles.put(descriptor.name(), descriptor);
                    break;
                case "RoleBinding":
                    roleBindings.add(descriptor);
                    break;
                case "ClusterRoleBinding":
                    clusterRoleBindings.add(descriptor);
                    break;
                default:
            }
        } catch (final RuntimeException re) {
            // no-op
        }
        return false;
    }

    @Override
    public Stream<LintError> validate(final LintableDescriptor descriptor) {
        return Stream.empty(); // validation done in after all to ensure we have roles
    }

    @Override // todo: handle aggregated roles by using kube client *if needed*
    public Stream<ContextualLintError> afterAll() {
        return Stream.concat(clusterRoleBindings.stream(), roleBindings.stream())
                .flatMap(this::validateBinding);
    }

    // desc.getString("namespace", "") + ":" + desc.getString("name")
    private Stream<ContextualLintError> validateBinding(final LintableDescriptor descriptor) {
        final var ref = descriptor.getDescriptor().getJsonObject("roleRef");
        if (ref == null) {
            return Stream.empty();
        }

        final var apiGroup = ref.getString("apiGroup", "");
        if (!"rbac.authorization.k8s.io".equals(apiGroup)) {
            return Stream.empty(); // unknown, not current validation, skip
        }

        final var name = ref.getString("name", "");
        if (name.isBlank()) {
            return Stream.empty(); // unknown, not current validation, skip
        }

        final var kind = ref.getString("kind", "");
        if (kind.isBlank() || !("ClusterRole".equals(kind) || "Role".equals(kind))) {
            return Stream.empty(); // unknown, not current validation, skip
        }

        switch (kind) {
            case "ClusterRole": {
                final var desc = clusterRoles.get(name);
                if (desc == null) {
                    lazyLogger().warning("ClusterRole '" + name + "' is assumed already deployed, skipping its validation");
                    return Stream.empty();
                }
                return validateRole(descriptor, desc.getDescriptor());
            }
            case "Role": {
                final var desc = roles.get(descriptor.namespace() + ":" + name);
                if (desc == null) {
                    lazyLogger().warning("Role '" + name + "' is assumed already deployed, skipping its validation");
                    return Stream.empty();
                }
                return validateRole(descriptor, desc.getDescriptor());
            }
            default:
                return Stream.empty();
        }
    }

    private Stream<ContextualLintError> validateRole(final LintableDescriptor binding, final JsonObject descriptor) {
        return ofNullable(descriptor.getJsonArray("rules"))
                .map(rules -> rules.stream()
                        .map(JsonValue::asJsonObject)
                        .filter(r -> "".equals(r.getString("apiGroups", ""))) // for now we only handle default apiGroups
                        .map(r -> {
                            final var resources = ofNullable(r.getJsonArray("resources")).orElse(EMPTY_JSON_ARRAY);
                            final var verbs = ofNullable(r.getJsonArray("verbs")).orElse(EMPTY_JSON_ARRAY);

                            final var filteredVerbs = verbs.stream()
                                    .filter(it -> it.getValueType() == STRING)
                                    .map(it -> ((JsonString) it).getString())
                                    .filter(this.verb::contains)
                                    .collect(toList());
                            if (filteredVerbs.isEmpty()) {
                                return EMPTY_TUPLE2;
                            }

                            final var filteredResources = resources.stream()
                                    .filter(it -> it.getValueType() == STRING)
                                    .map(it -> ((JsonString) it).getString())
                                    .filter(this.resources::contains)
                                    .collect(toList());
                            return new Tuple2<>(filteredVerbs, filteredResources);
                        })
                        .filter((Tuple2<List<String>, List<String>> it) -> !it.getFirst().isEmpty() && !it.getSecond().isEmpty())
                        .map(it -> new ContextualLintError(
                                WARNING,
                                binding.getDescriptor().getString("kind") + " '" + binding.name() + "' " +
                                        "enables to " + String.join(",", it.getFirst()) + " " + String.join(", ", it.getSecond()),
                                binding.getAlveolus(), binding.getName())))
                .orElseGet(Stream::empty);
    }

    private Logger lazyLogger() {
        return Logger.getLogger(getClass().getName());
    }
}
