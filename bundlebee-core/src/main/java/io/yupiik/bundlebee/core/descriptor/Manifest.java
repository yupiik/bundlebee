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

import io.yupiik.bundlebee.core.configuration.Description;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.json.JsonArray;
import java.util.List;
import java.util.Map;

/**
 * IMPORTANT: the key "bundlebee" is forbidden, see {@link io.yupiik.bundlebee.core.service.ManifestReader}.
 */
@Data
public class Manifest {
    @Description("Ignored linting rule names when using `lint` command.")
    private List<IgnoredLintingRule> ignoredLintingRules;

    @Description("Enables to consider all alveoli have their `interpolateDescriptors` descriptor set to `true`, you can still set it to `false` if you want to disable it for one.")
    private Boolean interpolateAlveoli;

    @Description("" +
            "List of files referenced as other manifests. " +
            "They are merged with this (main) manifest by *appending* _requirements_ and _alveoli_. " +
            "It is relative to this manifest location. " +
            "Important: it is only about the same module references, external references are dependencies in an alveoli. " +
            "It enables to split a huge `manifest.json` for an easier maintenance.")
    private List<ManifestReference> references;

    @Description("Pre manifest execution checks (bundlebee version typically). Avoids to install using a bundlebee version not compatible with the alveoli. Can be fully omitted.")
    private List<Requirement> requirements;

    @Description("List of described applications/libraries.")
    private List<Alveolus> alveoli;

    public enum ConditionType {
        @Description("Key is read is process environment variables.")
        ENV,

        @Description("Key is read is process system properties.")
        SYSTEM_PROPERTY
    }

    public enum ConditionOperator {
        @Description("At least one condition must match.")
        ANY,

        @Description("All conditions must match.")
        ALL
    }

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

    public enum AwaitConditionType {
        @Description("JSON Pointer evaluation (fully custom).")
        JSON_POINTER,

        @Description("Evaluate items in `/status/conditions`.")
        STATUS_CONDITION
    }

    @Data
    public static class Requirement {
        @Description("Minimum bundlebee version, use `*` to replace any digit in a segment. Note that snapshot is ignored in the comparison for convenience. It is an inclusive comparison.")
        private String minBundlebeeVersion;

        @Description("Minimum bundlebee version, use `*`to replace any digit in a segment. Note that snapshot is ignored in the comparison for convenience. It is an inclusive comparison.")
        private String maxBundlebeeVersion;

        @Description("List of forbidden version (due to a bug or equivalent). Here too snapshot suffix is ignored. `*` is usable there too to replace any digit in a segment (ex: `1.*.*`). Note that `1.*` would *NOT* match `1.*.*`, version are always 3 segments.")
        private List<String> forbiddenVersions;
    }

    @Data
    public static class AwaitConditions {
        @Description("Operator to combine the conditions.")
        private ConditionOperator operator = ConditionOperator.ALL;

        @Description("List of condition to match according `operator`.")
        private List<AwaitCondition> conditions;

        @Description("" +
                "Command to apply these conditions to, if not set it will be applied on `apply` command only. " +
                "Note that for now only `apply` and `delete` commands are supported, others will be ignored.")
        private String command;
    }

    @Data
    public static class AwaitCondition {
        @Description("Type of condition.")
        private AwaitConditionType type;

        @Description("" +
                "JSON Pointer to read from the resource. " +
                "It can for example be on `/status/phase` to await a namespace creation. " +
                "(for `type=JSON_POINTER`).")
        private String pointer = "/";

        @Description("" +
                "The operation to evaluate if this condition is true or not. " +
                "(for `type=JSON_POINTER`).")
        private JsonPointerOperator operatorType = JsonPointerOperator.EQUALS;

        @Description("" +
                "When condition type is `STATUS_CONDITION` it is the expected type of the condition. " +
                "This is ignored when condition type is `JSON_POINTER`.")
        private String conditionType;

        @Description("" +
                "When condition type is `JSON_POINTER` and `operatorType` needs a value (`EQUALS` for example), the related value. " +
                "It can be `Active` if you test namespace `/status/phase` for example. " +
                "When condition type is `STATUS_CONDITION` it is the expected status.")
        private String value;
    }

    @Data
    public static class Conditions {
        @Description("Operator to combine the conditions.")
        private ConditionOperator operator = ConditionOperator.ALL;

        @Description("List of condition to match according `operator`.")
        private List<Condition> conditions;
    }

    @Data
    public static class Condition {
        @Description("Type of condition.")
        private ConditionType type = ConditionType.ENV;

        @Description("Should the condition be reversed (ie \"not in this case\").")
        private boolean negate;

        @Description("Expected key. If empty/null condition is ignored. If read value is null it defaults to an empty string.")
        private String key;

        @Description("Expected value. If empty/null, `true` is assumed. Note that empty is allowed.")
        private String value;
    }

    @Data
    public static class Alveolus {
        @Description("" +
                "Enables to consider all descriptors have their `interpolate` descriptor set to `true`, you can still set it to `false` if you want to disable it for one. " +
                "If not set, `interpolateAlveoli` flag from the manifest.")
        private Boolean interpolateDescriptors;

        @Description("" +
                "Name of the alveolus (recipe). It must be unique accross the whole classpath. " +
                "Using maven style identifier, it is recommended to name it " +
                "`<groupId>:<artifactId>:<version>` using maven filtering but it is not enforced.")
        private String name;

        @Description("" +
                "If name does not follow `<groupId>:<artifactId>:<version>` naming (i.e. version can't be extracted from the name) " +
                "then you can specify the version there. " +
                "Note that if set, this is used in priority (explicit versus deduced).")
        private String version;

        @Description("List of descriptors to install for this alveolus. This is required even if an empty array.")
        private List<Descriptor> descriptors;

        @Description("Dependencies of this alveolus. It is a way to import transitively a set of descriptors.")
        private List<AlveolusDependency> dependencies;

        @Description("" +
                "Should dependencies be installed one after the other or in parallel (default). " +
                "It is useful when you install a namespace for example which must be awaited before next dependencies are installed.")
        private boolean chainDependencies;

        @Description("List of descriptors to ignore for this alveolus (generally coming from dependencies).")
        private List<DescriptorRef> excludedDescriptors;

        @Description("" +
                "Patches on descriptors. " +
                "It enables to inject configuration in descriptors by patching " +
                "(using JSON-Patch or plain interpolation with `${key}` values) their JSON representation. " +
                "The key is the descriptor name and each time the descriptor is found it will be applied.")
        private List<Patch> patches;

        @Description("" +
                "Local placeholders for this particular alveolus and its dependencies. " +
                "It is primarly intended to be able to create a template alveolus and inject the placeholders inline.")
        private Map<String, String> placeholders;

        public Alveolus copy() {
            final var alveolus = new Manifest.Alveolus();
            alveolus.setDependencies(getDependencies());
            alveolus.setExcludedDescriptors(getExcludedDescriptors());
            alveolus.setName(getName());
            alveolus.setPatches(getPatches());
            alveolus.setPlaceholders(getPlaceholders());
            alveolus.setDescriptors(getDescriptors());
            alveolus.setVersion(getVersion());
            return alveolus;
        }
    }

    @Data
    public static class AlveolusDependency {
        @Description("Alveolus name.")
        private String name;

        @Description("" +
                "Where to find the alveolus. " +
                "Note it will ensure the jar is present on the local maven repository.")
        private String location;

        @Description("Conditions to include this dependency. " +
                "Enables for example to have an environment variable enabling part of the stack (ex: `MONITORING=true`)")
        private Conditions includeIf;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DescriptorRef {
        @Description("Name of the descriptor (as declared, ie potentially without the extension).")
        private String name;

        @Description("The container of the descriptor (maven coordinates generally).")
        private String location;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Descriptor {
        @Description("Type of this descriptor. For now only `kubernetes` is supported. " +
                "It also defines in which folder under `bundlebee` the descriptor(s) are looked for from its name.")
        private String type = "kubernetes";

        @Description("Name of the descriptor to install. For kubernetes descriptors you can omit the `.yaml` extension.")
        private String name;

        @Description("Optional, if coming from another manifest, the dependency to download to get the alveolus.")
        private String location;

        @Description("" +
                "If set to `true`, apply/delete commands will await the actual creation of the resource (`GET /x` returns a HTTP 200) before continuing to process next resources. " +
                "It is useful for namespaces for example to ensure applications can be created in the newly created namespace. " +
                "It avoids to run and rerun apply command in practise. " +
                "For more advanced tests, use `awaitConditions`.")
        private boolean await;

        @Description("On delete we rarely want to check the resource exists before but in these rare case you can set this toggle to `true`.")
        private boolean awaitOnDelete;

        @Description("" +
                "Test to do on created/destroyed resources, enables to synchronize and await kubernetes actually starts some resource. " +
                "For `apply` and `delete` commands, `descriptorAwaitTimeout` is still applied. " +
                "Note that if you use multiple array entries for the same command it will be evaluated with an `AND`.")
        private List<AwaitConditions> awaitConditions;

        @Description("" +
                "If set to `true`, it will interpolate the descriptor just before applying it - i.e. after it had been patched if needed. " +
                "You can use `--<config-key> <value>` to inject bindings set as `{{config-key:-default value}}`. " +
                "If not set, `interpolateDescriptors` flag from the alveolus will be used.")
        private Boolean interpolate;

        @Description("Conditions to include this descriptor.")
        private Conditions includeIf;

        public void initInterpolate(final boolean interpolate) {
            this.interpolate = interpolate;
        }

        public boolean hasInterpolateValue() {
            return interpolate != null;
        }
    }

    @Data
    public static class Patch {
        @Description("Conditions to include this patch. " +
                "Enables for example to have an environment variable enabling part of the stack (ex: `MONITORING=true`)")
        private Conditions includeIf;

        @Description("The descriptor to patch. It can be any descriptor, including transitive ones. " +
                "It can be `*` to patch all descriptors (`/metadata/label/app` for example) or " +
                "`regex:<java pattern>` to match descriptor names with a regex.")
        private String descriptorName;

        @Description("" +
                "If set to `true`, it will interpolate the patch from the execution configuration which means " +
                "you can use `--<config-key> <value>` to inject bindings too. " +
                "An interesting interpolation is the ability to extract the ip/host of the host machine (`minikube ip` equivalent) using the kubeconfig file. " +
                "Syntax is the following one: `{{kubeconfig.cluster.minikube.ip}}` or more generally `{{kubeconfig.cluster.<cluster name>.ip}}`. " +
                "You can also await for some secret with this syntax " +
                "`{{kubernetes.<namespace>.serviceaccount.<account name>.secrets.<secret name prefix>.data.<entry name>[.<timeout in seconds, default to 2mn>]}}`. " +
                "This is particular useful to access freshly created service account tokens for example.")
        private boolean interpolate;

        @Description("" +
                "JSON-Patch to apply on the JSON representation of the descriptor. " +
                "It enables to inject configuration in descriptors for example, or changing some name/application.")
        private JsonArray patch;
    }

    @Data
    public static class ManifestReference {
        @Description("" +
                "Relative or absolute - starting by a `/` - location (referenced to the base directory of `manifest.json`). " +
                "For example `my-manifest.json` will resolve to `/path/to/bundlebee/my-manifest.json` in a folder and `/bundlebee/my-manifest.json` in a jar. " +
                "Important: for resources (jar/classpath), the classloader is used so ensure your name is unique accross your classpath (we recommend you to prefix it with the module name, ex :`/bundlebee/my-module.sub-manifest.json` or use a dedicated subfolder (`/bundlebee/my-module/sub.json`).")
        private String path;
    }

    @Data
    public static class IgnoredLintingRule {
        @Description("Name of the rule to ignore.")
        private String name;
    }
}
