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
package io.yupiik.bundlebee.core.kube;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import java.util.List;

public record DeploymentConfiguration(
        @Property(
                value = "fieldValidation",
                defaultValue = "\"Strict\"",
                documentation =
                        "`fieldValidation` - server side validation - value when applying a descriptor, values can be `Strict`, `Warn` pr `Ignore`. Note that using `skip` will ignore the query parameter.")
        String fieldValidation,

        @Property(
                value = "resourceMapping",
                defaultValue = "\"\"",
                documentation =
                        "Enables to define resource mapping, syntax uses propeties one: `<lowercased resource kind>s = /apis/....`.")
        String rawResourceMapping,

        @Property(
                value = "skipUpdateForKinds",
                defaultValue = "\"PersistentVolumeClaim\"",
                documentation =
                        "List of _kind_ of descriptors updates can be skipped, it is often useful for `PersistentVolumeClaim`.")
        String skipUpdateForKinds,

        @Property(
                value = "logDescriptorOnParsingError",
                defaultValue = "true",
                documentation = "Should YAML/JSON be logged when it can't be parsed.")
        boolean logDescriptorOnParsingError,

        @Property(
                value = "filters.statefuleset.spec.allowed",
                defaultValue =
                        "java.util.List.of(\"replicas\", \"template\", \"updateStrategy\", \"persistentVolumeClaimRetentionPolicy\", \"minReadySeconds\", \"serviceName\", \"selector\")",
                documentation = "The attributes to keep from `StatefulSet` (`spec` children) descriptor on updates.")
        List<String> statefulsetSpecAllowedAttributes,

        @Property(
                value = "implicitlyDroppedAttributes",
                defaultValue = "\"/$schema|/$bundlebeeIgnoredLintingRules\"",
                documentation = "Enables to tolerate custom attributes in the descriptors. "
                        + "Typically used to drop `/$schema` attribute which enables a nice completion in editors. "
                        + "Values are `|` delimited and are either a JSON-Pointer (wrapped in a remove JSON-Patch) or directly a JSON-Patch. "
                        + "Using `none` ignores this processing.")
        String implicitlyDroppedAttributes,

        @Property(
                value = "putOnUpdate",
                defaultValue = "false",
                documentation =
                        "By default a descriptor update is done using `PATCH` with strategic merge patch logic, if set to `true` it will use a plain `PUT`. "
                                + "Note that `io.yupiik.bundlebee/putOnUpdate` annotations can be set to `true` to force that in the descriptor itself.")
        boolean putOnUpdate,

        @Property(
                value = "force",
                defaultValue = "false",
                documentation =
                        "By default a descriptor update is done using `PATCH` with strategic merge patch logic, if set to `true` it will use a plain `PUT`. "
                                + "Note that `io.yupiik.bundlebee/putOnUpdate` annotations can be set to `true` to force that in the descriptor itself and for cases it is not enough, "
                                + "you can set `force` to `true` to delete the descriptor before applying it again (move from clusterip to nodeport or the opposite in a serice for ex). "
                                + "Note that you can set it to `true` in a descriptor annotation `io.yupiik.bundlebee/force` too to not be global.")
        boolean force,

        @Property(
                value = "defaultPropagationPolicy",
                defaultValue = "\"Foreground\"",
                documentation =
                        "Default value for deletions of `propagationPolicy`. Values can be `Orphan`, `Foreground` and `Background`.")
        String defaultPropagationPolicy,

        @Property(
                value = "customMetadataInjectionPoint",
                defaultValue = "\"labels\"",
                documentation =
                        "When using custom metadata (bundlebee ones or timestamp to force a rollout), where to inject them. "
                                + "Default uses labels since it enables to query them later on but you can switch it to annotations.")
        String customMetadataInjectionPoint,

        @Property(
                value = "patchContentType",
                defaultValue = "\"application/strategic-merge-patch+json\"",
                documentation = "Default header value for `PATCH` `content-type` requests header. "
                        + "It uses strategic merge patch algorithm but in some cases you just want to use `application/json` or (better) `application/merge-patch+json`. "
                        + "Annotation `io.yupiik.bundlebee/patchContentType` is also supported.")
        String patchContentType) {}
