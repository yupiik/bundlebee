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
package io.yupiik.bundlebee.helm;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sorts rendered Helm YAML documents based on hook annotations and kind.
 * <p>
 * Helm hooks are annotated with:
 * - {@code helm.sh/hook}: hook type (e.g., pre-install, post-install)
 * - {@code helm.sh/hook-weight}: numeric weight for ordering within the same hook point
 * <p>
 * The sorting logic follows the Helm lifecycle order:
 * 1. Pre-hooks (pre-install, pre-delete, pre-upgrade, pre-rollback) - sorted by weight
 * 2. Regular resources (no hook annotation) - sorted by kind using InstallOrder
 * 3. Post-hooks (post-install, post-delete, post-upgrade, post-rollback) - sorted by weight
 * <p>
 * Within each group, hooks are sorted by weight (lower weight runs first, default 0).
 * Within the same kind, original file order is preserved (stable sort).
 */
public class HelmHookSorter {

    private static final String HOOK_ANNOTATION = "helm.sh/hook";
    private static final String HOOK_WEIGHT_ANNOTATION = "helm.sh/hook-weight";

    private static final int GROUP_PRE_HOOK = 0;
    private static final int GROUP_REGULAR = 1;
    private static final int GROUP_POST_HOOK = 2;

    // Helm's InstallOrder for sorting regular resources
    private static final String[] INSTALL_ORDER = {
            "PriorityClass",
            "Namespace",
            "NetworkPolicy",
            "ResourceQuota",
            "LimitRange",
            "PodSecurityPolicy",
            "PodDisruptionBudget",
            "ServiceAccount",
            "Secret",
            "SecretList",
            "ConfigMap",
            "StorageClass",
            "PersistentVolume",
            "PersistentVolumeClaim",
            "CustomResourceDefinition",
            "ClusterRole",
            "ClusterRoleList",
            "ClusterRoleBinding",
            "ClusterRoleBindingList",
            "Role",
            "RoleList",
            "RoleBinding",
            "RoleBindingList",
            "Service",
            "DaemonSet",
            "Pod",
            "ReplicationController",
            "ReplicaSet",
            "Deployment",
            "HorizontalPodAutoscaler",
            "StatefulSet",
            "Job",
            "CronJob",
            "IngressClass",
            "Ingress",
            "APIService",
            "MutatingWebhookConfiguration",
            "ValidatingWebhookConfiguration",
    };

    private static final Map<String, Integer> KIND_ORDER;
    static {
        KIND_ORDER = new HashMap<>(INSTALL_ORDER.length);
        for (int i = 0; i < INSTALL_ORDER.length; i++) {
            KIND_ORDER.put(INSTALL_ORDER[i], i);
        }
    }

    private final Yaml yaml;

    public HelmHookSorter(final Yaml yaml) {
        this.yaml = yaml;
    }

    /**
     * Sort the rendered YAML documents based on hook annotations.
     *
     * @param documents list of rendered YAML document strings
     * @return sorted list of YAML document strings
     */
    public List<String> sort(final List<String> documents) {
        if (documents == null || documents.size() <= 1) {
            return documents != null ? documents : List.of();
        }

        final var sortable = new ArrayList<SortableDocument>();
        for (int i = 0; i < documents.size(); i++) {
            final var trimmed = documents.get(i).strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            sortable.add(new SortableDocument(trimmed, extractInfo(trimmed), i));
        }

        sortable.sort(Comparator
                .comparingInt((SortableDocument d) -> d.info.group)
                .thenComparingInt(d -> d.info.weight)
                .thenComparing(d -> d.info.hookType, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(d -> kindOrder(d.info.kind))
                .thenComparingInt(d -> d.index));

        final var result = new ArrayList<String>(sortable.size());
        for (final var sd : sortable) {
            result.add(sd.document);
        }
        return result;
    }

    private int kindOrder(final String kind) {
        if (kind == null) {
            return Integer.MAX_VALUE;
        }
        return KIND_ORDER.getOrDefault(kind, Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    private DocumentInfo extractInfo(final String document) {
        try {
            final var data = yaml.load(document);
            if (!(data instanceof Map)) {
                return DocumentInfo.REGULAR;
            }
            final var map = (Map<String, Object>) data;
            final var kind = map.get("kind") instanceof String ? (String) map.get("kind") : null;
            final var metadata = map.get("metadata");
            if (!(metadata instanceof Map)) {
                return DocumentInfo.regular(kind);
            }
            final var annotations = ((Map<String, Object>) metadata).get("annotations");
            if (!(annotations instanceof Map)) {
                return DocumentInfo.regular(kind);
            }
            final var annotationsMap = (Map<String, Object>) annotations;
            final var hook = annotationsMap.get(HOOK_ANNOTATION);
            if (hook == null) {
                return DocumentInfo.regular(kind);
            }
            final var hookType = hook.toString();
            int weight = 0;
            final var weightStr = annotationsMap.get(HOOK_WEIGHT_ANNOTATION);
            if (weightStr != null) {
                try {
                    weight = Integer.parseInt(weightStr.toString());
                } catch (final NumberFormatException e) {
                    // ignore, use default 0
                }
            }
            final var group = resolveGroup(hookType);
            return new DocumentInfo(true, hookType, weight, group, kind);
        } catch (final Exception e) {
            return DocumentInfo.REGULAR;
        }
    }

    private int resolveGroup(final String hookType) {
        if (hookType == null) {
            return GROUP_REGULAR;
        }
        if (hookType.startsWith("pre-")) {
            return GROUP_PRE_HOOK;
        }
        if (hookType.startsWith("post-")) {
            return GROUP_POST_HOOK;
        }
        // test hooks go after regular resources
        return GROUP_REGULAR;
    }

    private static final class DocumentInfo {
        static final DocumentInfo REGULAR = new DocumentInfo(false, null, 0, GROUP_REGULAR, null);

        private final boolean isHook;
        private final String hookType;
        private final int weight;
        private final int group;
        private final String kind;

        private DocumentInfo(final boolean isHook, final String hookType, final int weight, final int group, final String kind) {
            this.isHook = isHook;
            this.hookType = hookType;
            this.weight = weight;
            this.group = group;
            this.kind = kind;
        }

        static DocumentInfo regular(final String kind) {
            return new DocumentInfo(false, null, 0, GROUP_REGULAR, kind);
        }
    }

    private static final class SortableDocument {
        private final String document;
        private final DocumentInfo info;
        private final int index;

        private SortableDocument(final String document, final DocumentInfo info, final int index) {
            this.document = document;
            this.info = info;
            this.index = index;
        }
    }
}

