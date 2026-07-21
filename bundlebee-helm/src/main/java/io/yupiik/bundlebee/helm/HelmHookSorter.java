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
import java.util.List;
import java.util.Map;

/**
 * Sorts rendered Helm YAML documents based on hook annotations.
 * <p>
 * Helm hooks are annotated with:
 * - {@code helm.sh/hook}: hook type (e.g., pre-install, post-install)
 * - {@code helm.sh/hook-weight}: numeric weight for ordering within the same hook point
 * <p>
 * The sorting logic follows the Helm lifecycle order:
 * 1. Pre-hooks (pre-install, pre-delete, pre-upgrade, pre-rollback) - sorted by weight
 * 2. Regular resources (no hook annotation)
 * 3. Post-hooks (post-install, post-delete, post-upgrade, post-rollback) - sorted by weight
 * <p>
 * Within each group, hooks are sorted by weight (lower weight runs first, default 0).
 */
public class HelmHookSorter {

    private static final String HOOK_ANNOTATION = "helm.sh/hook";
    private static final String HOOK_WEIGHT_ANNOTATION = "helm.sh/hook-weight";

    private static final int GROUP_PRE_HOOK = 0;
    private static final int GROUP_REGULAR = 1;
    private static final int GROUP_POST_HOOK = 2;

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
        for (final var doc : documents) {
            final var trimmed = doc.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            sortable.add(new SortableDocument(trimmed, extractHookInfo(trimmed)));
        }

        sortable.sort(Comparator
                .comparingInt((SortableDocument d) -> d.hookInfo.group)
                .thenComparingInt(d -> d.hookInfo.weight)
                .thenComparing(d -> d.hookInfo.hookType, Comparator.nullsFirst(Comparator.naturalOrder())));

        final var result = new ArrayList<String>(sortable.size());
        for (final var sd : sortable) {
            result.add(sd.document);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private HookInfo extractHookInfo(final String document) {
        try {
            final var data = yaml.load(document);
            if (!(data instanceof Map)) {
                return HookInfo.REGULAR;
            }
            final var map = (Map<String, Object>) data;
            final var metadata = map.get("metadata");
            if (!(metadata instanceof Map)) {
                return HookInfo.REGULAR;
            }
            final var annotations = ((Map<String, Object>) metadata).get("annotations");
            if (!(annotations instanceof Map)) {
                return HookInfo.REGULAR;
            }
            final var annotationsMap = (Map<String, Object>) annotations;
            final var hook = annotationsMap.get(HOOK_ANNOTATION);
            if (hook == null) {
                return HookInfo.REGULAR;
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
            return new HookInfo(true, hookType, weight, group);
        } catch (final Exception e) {
            return HookInfo.REGULAR;
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

    private static final class HookInfo {
        static final HookInfo REGULAR = new HookInfo(false, null, 0, GROUP_REGULAR);

        private final boolean isHook;
        private final String hookType;
        private final int weight;
        private final int group;

        private HookInfo(final boolean isHook, final String hookType, final int weight, final int group) {
            this.isHook = isHook;
            this.hookType = hookType;
            this.weight = weight;
            this.group = group;
        }
    }

    private static final class SortableDocument {
        private final String document;
        private final HookInfo hookInfo;

        private SortableDocument(final String document, final HookInfo hookInfo) {
            this.document = document;
            this.hookInfo = hookInfo;
        }
    }
}

