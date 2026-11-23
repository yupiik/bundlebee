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
package io.yupiik.bundlebee.core.descriptor;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IMPORTANT: the key "bundlebee" is forbidden, see {@link io.yupiik.bundlebee.core.service.ManifestReader}.
 */
@JsonModel
public record Manifest(
        @Description("Ignored linting rule names when using `lint` command.") @JsonProperty(order = 0)
        List<IgnoredLintingRule> ignoredLintingRules,

        @Description(
                "Enables to consider all alveoli have their `interpolateDescriptors` descriptor set to `true`, you can still set it to `false` if you want to disable it for one.")
        @JsonProperty(order = 1)
        Boolean interpolateAlveoli,

        @Description("List of files referenced as other manifests. "
                + "They are merged with this (main) manifest by *appending* _requirements_ and _alveoli_. "
                + "It is relative to this manifest location. "
                + "Important: it is only about the same module references, external references are dependencies in an alveoli. "
                + "It enables to split a huge `manifest.json` for an easier maintenance.")
        @JsonProperty(order = 2)
        List<ManifestReference> references,

        @Description(
                "Pre manifest execution checks (bundlebee version typically). Avoids to install using a bundlebee version not compatible with the alveoli. Can be fully omitted.")
        @JsonProperty(order = 3)
        List<Requirement> requirements,

        @Description("List of described applications/libraries.") @JsonProperty(order = 4)
        List<Alveolus> alveoli) {
    public Manifest {
        ignoredLintingRules = ignoredLintingRules == null ? null : List.copyOf(ignoredLintingRules);
        references = references == null ? null : List.copyOf(references);
        requirements = requirements == null ? null : List.copyOf(requirements);
        alveoli = alveoli == null ? null : List.copyOf(alveoli);
    }

    @JsonModel
    public enum ConditionType {
        @Description("Key is read is process environment variables.")
        ENV,
        @Description("Key is read is process system properties.")
        SYSTEM_PROPERTY
    }

    @JsonModel
    public enum ConditionOperator {
        @Description("At least one condition must match.")
        ANY,
        @Description("All conditions must match.")
        ALL
    }

    @JsonModel
    public enum JsonPointerOperator {
        @Description("JSON Pointer exists model.")
        EXISTS,
        @Description("JSON Pointer does not exist in the resource model.")
        MISSING,
        @Description("JSON Pointer value is equal to (stringified comparison) value.")
        EQUALS,
        @Description("JSON Pointer is different from the provided value.")
        NOT_EQUALS,
        @Description("JSON Pointer value is equal (ignoring case) to (stringified comparison) value.")
        EQUALS_IGNORE_CASE,
        @Description("JSON Pointer is different (ignoring case) from the provided value.")
        NOT_EQUALS_IGNORE_CASE,
        @Description("JSON Pointer contains the configured value.")
        CONTAINS
    }

    @JsonModel
    public enum AwaitConditionType {
        @Description("JSON Pointer evaluation (fully custom).")
        JSON_POINTER,
        @Description("Evaluate items in `/status/conditions`.")
        STATUS_CONDITION
    }

    @JsonModel
    public record Requirement(
            @Description(
                    "Minimum bundlebee version, use `*` to replace any digit in a segment. Note that snapshot is ignored in the comparison for convenience. It is an inclusive comparison.")
            @JsonProperty(order = 0)
            String minBundlebeeVersion,

            @Description(
                    "Minimum bundlebee version, use `*`to replace any digit in a segment. Note that snapshot is ignored in the comparison for convenience. It is an inclusive comparison.")
            @JsonProperty(order = 1)
            String maxBundlebeeVersion,

            @Description(
                    "List of forbidden version (due to a bug or equivalent). Here too snapshot suffix is ignored. `*` is usable there too to replace any digit in a segment (ex: `1.*.*`). Note that `1.*` would *NOT* match `1.*.*`, version are always 3 segments.")
            @JsonProperty(order = 2)
            List<String> forbiddenVersions) {
        public Requirement {
            forbiddenVersions = forbiddenVersions == null ? null : List.copyOf(forbiddenVersions);
        }
    }

    @JsonModel
    public record AwaitConditions(
            @Description("Operator to combine the conditions.") @JsonProperty(order = 0)
            ConditionOperator operator,

            @Description("List of condition to match according `operator`.") @JsonProperty(order = 1)
            List<AwaitCondition> conditions,

            @Description("Command to apply these conditions to, if not set it will be applied on `apply` command only. "
                    + "Note that for now only `apply` and `delete` commands are supported, others will be ignored.")
            @JsonProperty(order = 2)
            String command) {
        public AwaitConditions {
            conditions = conditions == null ? null : List.copyOf(conditions);
            operator = operator == null ? ConditionOperator.ALL : operator;
        }
    }

    @JsonModel
    public record AwaitCondition(
            @Description("Type of condition.") @JsonProperty(order = 0)
            AwaitConditionType type,

            @Description("JSON Pointer to read from the resource. "
                    + "It can for example be on `/status/phase` to await a namespace creation. "
                    + "(for `type=JSON_POINTER`).")
            @JsonProperty(order = 1)
            String pointer,

            @Description("The operation to evaluate if this condition is true or not. " + "(for `type=JSON_POINTER`).")
            @JsonProperty(order = 2)
            JsonPointerOperator operatorType,

            @Description("When condition type is `STATUS_CONDITION` it is the expected type of the condition. "
                    + "This is ignored when condition type is `JSON_POINTER`.")
            @JsonProperty(order = 3)
            String conditionType,

            @Description(
                    "When condition type is `JSON_POINTER` and `operatorType` needs a value (`EQUALS` for example), the related value. "
                            + "It can be `Active` if you test namespace `/status/phase` for example. "
                            + "When condition type is `STATUS_CONDITION` it is the expected status.")
            @JsonProperty(order = 4)
            Object value,

            @Description(
                    "If set and not blank, such a condition will trigger an exception making the deployment stop there and process fail. "
                            + "In other cases this is a success condition which just stops the awaiting.")
            @JsonProperty(order = 5)
            String failMessage) {
        public AwaitCondition {
            pointer = pointer == null ? "/" : pointer;
            operatorType = operatorType == null ? JsonPointerOperator.EQUALS : operatorType;
        }
    }

    @JsonModel
    public record Conditions(
            @Description("Operator to combine the conditions.") @JsonProperty(order = 0)
            ConditionOperator operator,

            @Description("List of condition to match according `operator`.") @JsonProperty(order = 1)
            List<Condition> conditions) {
        public Conditions {
            conditions = conditions == null ? null : List.copyOf(conditions);
            operator = operator == null ? ConditionOperator.ALL : operator;
        }
    }

    @JsonModel
    public record Condition(
            @Description("Type of condition.") @JsonProperty(order = 0)
            ConditionType type,

            @Description("Should the condition be reversed (ie \"not in this case\").") @JsonProperty(order = 1)
            boolean negate,

            @Description(
                    "Expected key. If empty/null condition is ignored. If read value is null it defaults to an empty string.")
            @JsonProperty(order = 2)
            String key,

            @Description("Expected value. If empty/null, `true` is assumed. Note that empty is allowed.")
            @JsonProperty(order = 3)
            String value) {
        public Condition {
            type = type == null ? ConditionType.ENV : type;
        }
    }

    @JsonModel
    public record Alveolus(
            @Description(
                    "Enables to consider all descriptors have their `interpolate` descriptor set to `true`, you can still set it to `false` if you want to disable it for one. "
                            + "If not set, `interpolateAlveoli` flag from the manifest.")
            @JsonProperty(order = 0)
            Boolean interpolateDescriptors,

            @Description("Name of the alveolus (recipe). It must be unique accross the whole classpath. "
                    + "Using maven style identifier, it is recommended to name it "
                    + "`<groupId>:<artifactId>:<version>` using maven filtering but it is not enforced.")
            @JsonProperty(order = 1)
            String name,

            @Description(
                    "If name does not follow `<groupId>:<artifactId>:<version>` naming (i.e. version can't be extracted from the name) "
                            + "then you can specify the version there. "
                            + "Note that if set, this is used in priority (explicit versus deduced).")
            @JsonProperty(order = 2)
            String version,

            @Description("List of descriptors to install for this alveolus. This is required even if an empty array.")
            @JsonProperty(order = 3)
            List<Descriptor> descriptors,

            @Description("Dependencies of this alveolus. It is a way to import transitively a set of descriptors.")
            @JsonProperty(order = 4)
            List<AlveolusDependency> dependencies,

            @Description(
                    "Should dependencies be installed one after the other or in parallel (default). "
                            + "It is useful when you install a namespace for example which must be awaited before next dependencies are installed.")
            @JsonProperty(order = 5)
            boolean chainDependencies,

            @Description("List of descriptors to ignore for this alveolus (generally coming from dependencies).")
            @JsonProperty(order = 6)
            List<DescriptorRef> excludedDescriptors,

            @Description("Patches on descriptors. "
                    + "It enables to inject configuration in descriptors by patching "
                    + "(using JSON-Patch or plain interpolation with `${key}` values) their JSON representation. "
                    + "The key is the descriptor name and each time the descriptor is found it will be applied.")
            @JsonProperty(order = 7)
            List<Patch> patches,

            @Description(
                    "Local placeholders for this particular alveolus and its dependencies. "
                            + "It is primarly intended to be able to create a template alveolus and inject the placeholders inline.")
            @JsonProperty(order = 8)
            Map<String, String> placeholders) {
        public Alveolus {
            descriptors = descriptors == null ? null : List.copyOf(descriptors);
            dependencies = dependencies == null ? null : List.copyOf(dependencies);
            excludedDescriptors = excludedDescriptors == null ? null : List.copyOf(excludedDescriptors);
            patches = patches == null ? null : List.copyOf(patches);
            placeholders = placeholders == null ? null : unmodifiableMap(new LinkedHashMap<>(placeholders));
        }

        public Alveolus withChainDependencies(final boolean chainDependencies) {
            return new Alveolus(
                    interpolateDescriptors,
                    name,
                    version,
                    descriptors,
                    dependencies,
                    chainDependencies,
                    excludedDescriptors,
                    patches,
                    placeholders);
        }

        public Alveolus withDescriptors(final List<Descriptor> descriptors) {
            return new Alveolus(
                    interpolateDescriptors,
                    name,
                    version,
                    descriptors,
                    dependencies,
                    chainDependencies,
                    excludedDescriptors,
                    patches,
                    placeholders);
        }

        public Alveolus withExcludedDescriptors(final List<DescriptorRef> excludedDescriptors) {
            return new Alveolus(
                    interpolateDescriptors,
                    name,
                    version,
                    descriptors,
                    dependencies,
                    chainDependencies,
                    excludedDescriptors,
                    patches,
                    placeholders);
        }
    }

    @JsonModel
    public record AlveolusDependency(
            @Description("Alveolus name.") @JsonProperty(order = 0)
            String name,

            @Description("Where to find the alveolus. "
                    + "Note it will ensure the jar is present on the local maven repository.")
            @JsonProperty(order = 1)
            String location,

            @Description(
                    "Conditions to include this dependency. "
                            + "Enables for example to have an environment variable enabling part of the stack (ex: `MONITORING=true`)")
            @JsonProperty(order = 2)
            Conditions includeIf) {}

    @JsonModel
    public record DescriptorRef(
            @Description("Name of the descriptor (as declared, ie potentially without the extension).")
            @JsonProperty(order = 0)
            String name,

            @Description("The container of the descriptor (maven coordinates generally).") @JsonProperty(order = 1)
            String location) {}

    @JsonModel
    public record Descriptor(
            @Description("Type of this descriptor. For now only `kubernetes` and `helm` are supported. "
                    + "It also defines in which folder under `bundlebee` the descriptor(s) are looked for from its name. "
                    + "For `helm`, see xref:helm.adoc[Helm Support].")
            @JsonProperty(order = 0)
            String type,

            @Description(
                    "Name of the descriptor to install. For kubernetes descriptors you can omit the `.yaml` extension.")
            @JsonProperty(order = 1)
            String name,

            @Description("Optional, if coming from another manifest, the dependency to download to get the alveolus.")
            @JsonProperty(order = 2)
            String location,

            @Description("Helm chart configuration. Only used when `type` is `helm`. "
                    + "Points to a local Helm chart directory path. See xref:helm.adoc[Helm Support] for details.")
            @JsonProperty(order = 3)
            HelmConfig helm,

            @Description(
                    "If set to `true`, apply/delete commands will await the actual creation of the resource (`GET /x` returns a HTTP 200) before continuing to process next resources. "
                            + "It is useful for namespaces for example to ensure applications can be created in the newly created namespace. "
                            + "It avoids to run and rerun apply command in practise. "
                            + "For more advanced tests, use `awaitConditions`.")
            @JsonProperty(order = 4)
            boolean await,

            @Description(
                    "On delete we rarely want to check the resource exists before but in these rare case you can set this toggle to `true`.")
            @JsonProperty(order = 5)
            boolean awaitOnDelete,

            @Description(
                    "Test to do on created/destroyed resources, enables to synchronize and await kubernetes actually starts some resource. "
                            + "For `apply` and `delete` commands, `descriptorAwaitTimeout` is still applied. "
                            + "Note that if you use multiple array entries for the same command it will be evaluated with an `AND`.")
            @JsonProperty(order = 6)
            List<AwaitConditions> awaitConditions,

            @Description(
                    "If set to `true`, it will interpolate the descriptor just before applying it - i.e. after it had been patched if needed. "
                            + "You can use `--<config-key> <value>` to inject bindings set as `{{config-key:-default value}}`. "
                            + "If not set, `interpolateDescriptors` flag from the alveolus will be used.")
            @JsonProperty(order = 7)
            Boolean interpolate,

            @Description("Conditions to include this descriptor.") @JsonProperty(order = 8)
            Conditions includeIf) {
        public Descriptor {
            awaitConditions = awaitConditions == null ? null : List.copyOf(awaitConditions);
            type = type == null ? "kubernetes" : type;
        }

        public boolean hasInterpolateValue() {
            return interpolate != null;
        }

        public Descriptor withLocation(final String location) {
            return new Descriptor(
                    type, name, location, helm, await, awaitOnDelete, awaitConditions, interpolate, includeIf);
        }

        public Descriptor withInterpolate(final Boolean interpolate) {
            return new Descriptor(
                    type, name, location, helm, await, awaitOnDelete, awaitConditions, interpolate, includeIf);
        }
    }

    @JsonModel
    public record Patch(
            @Description(
                    "Conditions to include this patch. "
                            + "Enables for example to have an environment variable enabling part of the stack (ex: `MONITORING=true`)")
            @JsonProperty(order = 0)
            Conditions includeIf,

            @Description("The descriptor to patch. It can be any descriptor, including transitive ones. "
                    + "It can be `*` to patch all descriptors (`/metadata/label/app` for example) or "
                    + "`regex:<java pattern>` to match descriptor names with a regex.")
            @JsonProperty(order = 1)
            String descriptorName,

            @Description("If set to `true`, it will interpolate the patch from the execution configuration which means "
                    + "you can use `--<config-key> <value>` to inject bindings too. "
                    + "An interesting interpolation is the ability to extract the ip/host of the host machine (`minikube ip` equivalent) using the kubeconfig file. "
                    + "Syntax is the following one: `{{kubeconfig.cluster.minikube.ip}}` or more generally `{{kubeconfig.cluster.<cluster name>.ip}}`. "
                    + "You can also await for some secret with this syntax "
                    + "`{{kubernetes.<namespace>.serviceaccount.<account name>.secrets.<secret name prefix>.data.<entry name>[.<timeout in seconds, default to 2mn>]}}`. "
                    + "This is particular useful to access freshly created service account tokens for example.")
            @JsonProperty(order = 2)
            boolean interpolate,

            @Description(
                    "JSON-Patch to apply on the JSON representation of the descriptor. "
                            + "It enables to inject configuration in descriptors for example, or changing some name/application.")
            @JsonProperty(order = 3)
            List<Object> patch) {
        public Patch {
            // Dynamic JSON values can contain null; List.copyOf would reject them.
            patch = patch == null ? null : unmodifiableList(new ArrayList<>(patch));
        }
    }

    @JsonModel
    public record ManifestReference(
            @Description(
                    "Relative or absolute - starting by a `/` - location (referenced to the base directory of `manifest.json`). "
                            + "For example `my-manifest.json` will resolve to `/path/to/bundlebee/my-manifest.json` in a folder and `/bundlebee/my-manifest.json` in a jar. "
                            + "Important: for resources (jar/classpath), the classloader is used so ensure your name is unique accross your classpath (we recommend you to prefix it with the module name, ex :`/bundlebee/my-module.sub-manifest.json` or use a dedicated subfolder (`/bundlebee/my-module/sub.json`).")
            @JsonProperty(order = 0)
            String path) {}

    @JsonModel
    public record IgnoredLintingRule(
            @Description("Name of the rule to ignore.") @JsonProperty(order = 0)
            String name) {}

    @JsonModel
    public record HelmConfig(
            @Description("Path or URI to the Helm chart. Can be a local path, http/https URI to a tgz, or oci:// URI.")
            @JsonProperty(order = 0)
            String chart,

            @Description("Release name for the Helm context. Defaults to the alveolus name if not set.")
            @JsonProperty(order = 1)
            String releaseName,

            @Description("Release namespace for the Helm context. Defaults to the current kubernetes namespace.")
            @JsonProperty(order = 2)
            String releaseNamespace,

            @Description("Username for remote chart authentication. Password can use maven:serverId syntax.")
            @JsonProperty(order = 3)
            String username,

            @Description(
                    "Password for remote chart authentication. Can start with maven:xxxx to use Maven server credentials.")
            @JsonProperty(order = 4)
            String password,

            @Description("Whether to resolve Chart.yaml dependencies. Defaults to true.") @JsonProperty(order = 5)
            Boolean resolveDependencies,

            @Description("List of template relative paths to ignore (e.g. NOTES.txt).") @JsonProperty(order = 6)
            List<String> ignoredDescriptors) {
        public HelmConfig {
            ignoredDescriptors = ignoredDescriptors == null ? null : List.copyOf(ignoredDescriptors);
            resolveDependencies = resolveDependencies == null ? true : resolveDependencies;
        }
    }

    public Manifest withAlveoli(final List<Alveolus> alveoli) {
        return new Manifest(ignoredLintingRules, interpolateAlveoli, references, requirements, alveoli);
    }

    public Manifest withReferences(final List<ManifestReference> references) {
        return new Manifest(ignoredLintingRules, interpolateAlveoli, references, requirements, alveoli);
    }
}
