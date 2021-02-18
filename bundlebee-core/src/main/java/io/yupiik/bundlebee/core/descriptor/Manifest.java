/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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

@Data
public class Manifest {
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
                "Patches on descriptors. " +
                "It enables to inject configuration in descriptors by patching " +
                "(using JSON-Patch or plain interpolation with `${key}` values) their JSON representation. " +
                "The key is the descriptor name and each time the descriptor is found it will be applied.")
        private List<Patch> patches;
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
    public static class Descriptor {
        @Description("Type of this descriptor. For now only `kubernetes` is supported. " +
                "It also defines in which folder under `bundlebee` the descriptor(s) are looked for from its name.")
        private String type = "kubernetes";

        @Description("Name of the descriptor to install. For kubernetes descriptors you can omit the `.yaml` extension.")
        private String name;

        @Description("Optional, if coming form another manifest, the dependency to download to get the alveolus.")
        private String location;

        @Description("" +
                "If set to `true`, it will interpolate the descriptor just before applying it - i.e. after it had been patched if needed. " +
                "You can use `--<config-key> <value>` to inject bindings set as `{{config-key:-default value}}`.")
        private boolean interpolate;

        @Description("Conditions to include this descriptor.")
        private Conditions includeIf;
    }

    @Data
    public static class Patch {
        @Description("The descriptor to patch. It can be any descriptor, including transitive ones. " +
                "It can be `*` to patch all descriptors (`/metadata/label/app` for example) or " +
                "`regex:<java pattern>` to match descriptor names with a regex.")
        private String descriptorName;

        @Description("" +
                "If set to `true`, it will interpolate the patch from the execution configuration which means " +
                "you can use `--<config-key> <value>` to inject bindings too.")
        private boolean interpolate;

        @Description("" +
                "JSON-Patch to apply on the JSON representation of the descriptor. " +
                "It enables to inject configuration in descriptors for example, or changing some name/application.")
        private JsonArray patch;
    }
}
