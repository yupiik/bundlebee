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

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelmHookSorterTest {

    private final HelmHookSorter sorter = new HelmHookSorter(new Yaml());

    @Test
    void preHooksBeforeRegularBeforePostHooks() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: post-install\n  annotations:\n    helm.sh/hook: post-install",
                "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: pre-install\n  annotations:\n    helm.sh/hook: pre-install");
        final var sorted = sorter.sort(docs);
        assertEquals(3, sorted.size());
        assertTrue(sorted.get(0).contains("pre-install"), "Pre-hook should come first");
        assertTrue(sorted.get(1).contains("app-config"), "Regular resource should come second");
        assertTrue(sorted.get(2).contains("post-install"), "Post-hook should come last");
    }

    @Test
    void hookWeightOrdering() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-high\n  annotations:\n    helm.sh/hook: pre-install\n    helm.sh/hook-weight: \"100\"",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-low\n  annotations:\n    helm.sh/hook: pre-install\n    helm.sh/hook-weight: \"-100\"",
                "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config");
        final var sorted = sorter.sort(docs);
        assertEquals(3, sorted.size());
        assertTrue(sorted.get(0).contains("hook-low"), "Lower weight pre-hook should come first");
        assertTrue(sorted.get(1).contains("hook-high"), "Higher weight pre-hook should come second");
        assertTrue(sorted.get(2).contains("app-config"), "Regular resource should come last");
    }

    @Test
    void defaultWeightIsZero() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-with-weight\n  annotations:\n    helm.sh/hook: pre-install\n    helm.sh/hook-weight: \"10\"",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-no-weight\n  annotations:\n    helm.sh/hook: pre-install",
                "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config");
        final var sorted = sorter.sort(docs);
        assertEquals(3, sorted.size());
        assertTrue(sorted.get(0).contains("hook-no-weight"), "No weight (default 0) should come before positive weight");
        assertTrue(sorted.get(1).contains("hook-with-weight"), "Positive weight should come second");
        assertTrue(sorted.get(2).contains("app-config"), "Regular resource should come last");
    }

    @Test
    void multiplePreHookTypes() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: pre-delete\n  annotations:\n    helm.sh/hook: pre-delete",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: pre-install\n  annotations:\n    helm.sh/hook: pre-install",
                "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config");
        final var sorted = sorter.sort(docs);
        assertEquals(3, sorted.size());
        // Both pre-hooks have same weight (0), sorted by hook type string
        assertTrue(sorted.get(0).contains("pre-delete"), "Pre-delete should come first (alphabetical)");
        assertTrue(sorted.get(1).contains("pre-install"), "Pre-install should come second (alphabetical)");
        assertTrue(sorted.get(2).contains("app-config"), "Regular resource should come last");
    }

    @Test
    void multiplePostHookTypes() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: post-install\n  annotations:\n    helm.sh/hook: post-install",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: post-delete\n  annotations:\n    helm.sh/hook: post-delete",
                "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config");
        final var sorted = sorter.sort(docs);
        assertEquals(3, sorted.size());
        assertTrue(sorted.get(0).contains("app-config"), "Regular resource should come first");
        // Both post-hooks have same weight (0), sorted by hook type string
        assertTrue(sorted.get(1).contains("post-delete"), "Post-delete should come first (alphabetical)");
        assertTrue(sorted.get(2).contains("post-install"), "Post-install should come second (alphabetical)");
    }

    @Test
    void emptyInput() {
        assertEquals(List.of(), sorter.sort(List.of()));
        assertEquals(List.of(), sorter.sort(null));
    }

    @Test
    void singleDocument() {
        final var docs = List.of("---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config");
        assertEquals(docs, sorter.sort(docs));
    }

    @Test
    void allPreHooksNoRegular() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-b\n  annotations:\n    helm.sh/hook: pre-install\n    helm.sh/hook-weight: \"5\"",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-a\n  annotations:\n    helm.sh/hook: pre-install\n    helm.sh/hook-weight: \"-5\"");
        final var sorted = sorter.sort(docs);
        assertEquals(2, sorted.size());
        assertTrue(sorted.get(0).contains("hook-a"), "Lower weight should come first");
        assertTrue(sorted.get(1).contains("hook-b"), "Higher weight should come second");
    }

    @Test
    void allPostHooksNoRegular() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-b\n  annotations:\n    helm.sh/hook: post-install\n    helm.sh/hook-weight: \"5\"",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: hook-a\n  annotations:\n    helm.sh/hook: post-install\n    helm.sh/hook-weight: \"-5\"");
        final var sorted = sorter.sort(docs);
        assertEquals(2, sorted.size());
        assertTrue(sorted.get(0).contains("hook-a"), "Lower weight should come first");
        assertTrue(sorted.get(1).contains("hook-b"), "Higher weight should come second");
    }

    @Test
    void testHooksWithRegular() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: test-hook\n  annotations:\n    helm.sh/hook: test",
                "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config");
        final var sorted = sorter.sort(docs);
        assertEquals(2, sorted.size());
        assertTrue(sorted.get(0).contains("app-config"), "Regular resource should come first");
        assertTrue(sorted.get(1).contains("test-hook"), "Test hook should come second (treated as regular)");
    }

    @Test
    void complexOrdering() {
        final var docs = List.of(
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: post-upgrade\n  annotations:\n    helm.sh/hook: post-upgrade\n    helm.sh/hook-weight: \"0\"",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: pre-delete\n  annotations:\n    helm.sh/hook: pre-delete\n    helm.sh/hook-weight: \"-10\"",
                "---\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: app-config",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: pre-install\n  annotations:\n    helm.sh/hook: pre-install\n    helm.sh/hook-weight: \"5\"",
                "---\napiVersion: v1\nkind: Pod\nmetadata:\n  name: post-install\n  annotations:\n    helm.sh/hook: post-install\n    helm.sh/hook-weight: \"-5\"");
        final var sorted = sorter.sort(docs);
        assertEquals(5, sorted.size());
        assertTrue(sorted.get(0).contains("pre-delete"), "Pre-hook with lowest weight first");
        assertTrue(sorted.get(1).contains("pre-install"), "Pre-hook with higher weight second");
        assertTrue(sorted.get(2).contains("app-config"), "Regular resource in middle");
        assertTrue(sorted.get(3).contains("post-install"), "Post-hook with lowest weight first");
        assertTrue(sorted.get(4).contains("post-upgrade"), "Post-hook with higher weight second");
    }
}
