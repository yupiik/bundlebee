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
package io.yupiik.bundlebee.core.command.impl.lint.builtin;

import io.yupiik.bundlebee.core.command.impl.lint.ContextualLintError;
import io.yupiik.bundlebee.core.command.impl.lint.LintError;

import javax.enterprise.context.Dependent;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.impl.lint.LintError.LintLevel.WARNING;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

@Dependent
public class DuplicateEnvVar extends CheckValue {
    private final Map<String, LintableDescriptor> configMaps = new ConcurrentHashMap<>();
    private final Collection<LintableDescriptor> descriptors = new CopyOnWriteArraySet<>();

    public DuplicateEnvVar() {
        super(
                Set.of("DeploymentConfig", "Deployment", "CronJob", "Pod", "Job"),
                Map.of(
                        "DeploymentConfig", "/spec/template/spec/containers",
                        "Deployment", "/spec/template/spec/containers",
                        "CronJob", "/spec/jobTemplate/template/spec/containers",
                        "Job", "/spec/template/spec/containers",
                        "Pod", "/spec/containers"),
                true);
    }

    @Override
    public boolean accept(final LintableDescriptor descriptor) { // capture role related descriptors
        final var kind = descriptor.kind();
        if (kind.equals("ConfigMap")) {
            configMaps.put(descriptor.namespace() + ":" + descriptor.name(), descriptor);
        } else if (super.pointers.containsKey(kind)) {
            descriptors.add(descriptor);
        }
        return false;
    }

    @Override
    public Stream<ContextualLintError> afterAllSync() {
        return descriptors.stream()
                .flatMap(it -> super.validateSync(it)
                        .map(e -> new ContextualLintError(e.getLevel(), e.getMessage(), it.getAlveolus(), it.getName())));
    }

    @Override // triggered from afterAll() but makes the impl easier to read
    protected Stream<LintError> doValidate(final LintableDescriptor descriptor, final JsonValue value) {
        if (value.getValueType() != JsonValue.ValueType.ARRAY) {
            return Stream.empty();
        }
        final var namespace = descriptor.namespace();
        return value.asJsonArray().stream()
                .map(JsonValue::asJsonObject)
                .flatMap(it -> validateContainer(namespace, it));
    }

    @Override
    public String name() {
        return "duplicate-env-var";
    }

    @Override
    public String description() {
        return "Check that duplicate named env vars aren't passed to a deployment like.";
    }

    @Override
    public String remediation() {
        return "Confirm that your DeploymentLike doesn't have duplicate env vars names.";
    }

    private Stream<LintError> validateContainer(final String namespace, final JsonObject container) {
        final var keys = findKeys(namespace, container);

        final var duplicated = keys.stream()
                .collect(groupingBy(identity(), counting()))
                .entrySet().stream()
                .filter(it -> it.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(toList());
        if (duplicated.isEmpty()) {
            return Stream.empty();
        }

        return Stream.of(new LintError(WARNING, "Duplicated environment variables (container=" + container.getString("name", "") + "): " + duplicated));
    }

    private List<String> findKeys(final String namespace, final JsonObject container) {
        final var out = new ArrayList<String>();

        final var env = container.getJsonArray("env");
        if (env != null && env.getValueType() == JsonValue.ValueType.ARRAY) {
            out.addAll(env.asJsonArray().stream()
                    .map(JsonValue::asJsonObject)
                    .map(it -> it.getString("name", ""))
                    .filter(Predicate.not(String::isBlank))
                    .collect(toList()));
        }

        final var envFrom = container.getJsonArray("envFrom");
        if (envFrom != null && envFrom.getValueType() == JsonValue.ValueType.ARRAY) {
            out.addAll(envFrom.asJsonArray().stream()
                    .map(JsonValue::asJsonObject)
                    .flatMap(it -> {
                        final var configMapRef = it.getJsonObject("configMapRef");
                        if (configMapRef != null) {
                            final var key = namespace + ':' + configMapRef.getString("name", "");
                            final var values = configMaps.get(key);
                            if (values != null) {
                                final var data = values.getDescriptor().asJsonObject().getJsonObject("data");
                                if (data != null) {
                                    return data.keySet().stream();
                                }
                                return Stream.empty();
                            }

                            lazyLogger().warning(() -> "ConfigMap '" + key + "' was not part of the deployment, ignoring");
                        }

                        return Stream.empty();
                    })
                    .filter(Predicate.not(String::isBlank))
                    .collect(toList()));
        }

        return out;
    }
}
