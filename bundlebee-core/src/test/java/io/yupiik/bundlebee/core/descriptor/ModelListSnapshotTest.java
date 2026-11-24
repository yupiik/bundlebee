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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.bundlebee.core.command.model.sarif.Artifact;
import io.yupiik.bundlebee.core.command.model.sarif.CompleteLocation;
import io.yupiik.bundlebee.core.command.model.sarif.Result;
import io.yupiik.bundlebee.core.command.model.sarif.Rule;
import io.yupiik.bundlebee.core.command.model.sarif.Run;
import io.yupiik.bundlebee.core.command.model.sarif.Sarif;
import io.yupiik.bundlebee.core.command.model.sarif.Tool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ModelListSnapshotTest {
    @Test
    void manifestListsAreNullableImmutableSnapshotsOfNonNullEntries() {
        assertSnapshot(
                new Manifest.IgnoredLintingRule("rule"),
                entries -> new Manifest(entries, null, null, null, null).ignoredLintingRules());
        assertSnapshot(
                new Manifest.ManifestReference("manifest.json"),
                entries -> new Manifest(null, null, entries, null, null).references());
        assertSnapshot(
                new Manifest.Requirement(null, null, null),
                entries -> new Manifest(null, null, null, entries, null).requirements());
        assertSnapshot(
                new Manifest.Alveolus(null, "recipe", null, null, null, false, null, null, null),
                entries -> new Manifest(null, null, null, null, entries).alveoli());
        assertSnapshot("1.0.0", entries -> new Manifest.Requirement(null, null, entries).forbiddenVersions());
        assertSnapshot(
                new Manifest.AwaitCondition(null, null, null, null, null, null),
                entries -> new Manifest.AwaitConditions(null, entries, null).conditions());
        assertSnapshot(
                new Manifest.Condition(null, false, "key", "value"),
                entries -> new Manifest.Conditions(null, entries).conditions());
        assertSnapshot(
                new Manifest.Descriptor(null, "resource", null, null, false, false, null, null, null),
                entries -> new Manifest.Alveolus(null, "recipe", null, entries, null, false, null, null, null)
                        .descriptors());
        assertSnapshot(
                new Manifest.AlveolusDependency("dependency", null, null),
                entries -> new Manifest.Alveolus(null, "recipe", null, null, entries, false, null, null, null)
                        .dependencies());
        assertSnapshot(
                new Manifest.DescriptorRef("excluded", null),
                entries -> new Manifest.Alveolus(null, "recipe", null, null, null, false, entries, null, null)
                        .excludedDescriptors());
        assertSnapshot(
                new Manifest.Patch(null, "resource", false, null),
                entries ->
                        new Manifest.Alveolus(null, "recipe", null, null, null, false, null, entries, null).patches());
        assertSnapshot(
                new Manifest.AwaitConditions(null, null, null),
                entries -> new Manifest.Descriptor(null, "resource", null, null, false, false, entries, null, null)
                        .awaitConditions());
        assertSnapshot(
                "NOTES.txt",
                entries ->
                        new Manifest.HelmConfig("chart", null, null, null, null, null, entries).ignoredDescriptors());
    }

    @Test
    void sarifListsAreNullableImmutableSnapshotsOfNonNullEntries() {
        assertSnapshot(new Run(null, null, null), entries -> new Sarif("2.1.0", null, entries).runs());
        assertSnapshot(new Artifact(null), entries -> new Run(null, entries, null).artifacts());
        assertSnapshot(new Result(null, null, null, null, null), entries -> new Run(null, null, entries).results());
        assertSnapshot(new Rule("rule", null, null, null), entries -> new Tool.Driver("tool", null, entries).rules());
        assertSnapshot(new CompleteLocation(null), entries -> new Result(null, null, null, null, entries).locations());
    }

    @Test
    void dynamicPatchKeepsNullValuesAndCopiesTheOuterList() {
        final var values = new ArrayList<Object>();
        values.add(null);
        final var operation = Map.of("op", "add", "path", "/value", "value", Arrays.asList("text", null));
        values.add(operation);
        final var patch = new Manifest.Patch(null, "resource", false, values);
        values.clear();
        assertEquals(2, patch.patch().size());
        assertNull(patch.patch().get(0));
        assertEquals(operation, patch.patch().get(1));
        assertThrows(UnsupportedOperationException.class, () -> patch.patch().add(null));
        assertNull(new Manifest.Patch(null, "resource", false, null).patch());
    }

    private <T> void assertSnapshot(final T entry, final Function<List<T>, List<T>> snapshot) {
        assertNull(snapshot.apply(null));
        assertEquals(List.of(), snapshot.apply(List.of()));
        final var source = new ArrayList<T>();
        source.add(entry);
        final var copy = snapshot.apply(source);
        source.clear();
        assertEquals(List.of(entry), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add(entry));
        assertThrows(UnsupportedOperationException.class, () -> copy.set(0, entry));
        final var invalid = new ArrayList<T>();
        invalid.add(null);
        assertThrows(NullPointerException.class, () -> snapshot.apply(invalid));
    }
}
