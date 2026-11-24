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
package io.yupiik.bundlebee.core.command.impl;

import static io.yupiik.bundlebee.lang.CompletionFutures.all;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.ArchiveReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public abstract class VisitorCommand implements CompletingExecutable {
    protected VisitorCommand(AlveolusHandler visitor, ArchiveReader archives) {
        this.visitor = visitor;
        this.archives = archives;
    }

    private final AlveolusHandler visitor;
    private final ArchiveReader archives;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "alveolus":
                return visitor.findCompletionAlveoli(options);
            default:
                return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String description() {
        return "Diff an alveolus against a running cluster.";
    }

    protected CompletionStage<Collected> doExecute(
            final String from,
            final String manifest,
            final String alveolus,
            final String descriptorFilter,
            final String id) {
        final var cache = archives.newCache();
        final var collected = new Collected();
        final var filter = createDescriptorFilter(descriptorFilter);
        return visitor.findRootAlveoli(from, manifest, alveolus, id)
                .thenCompose(alveoli -> all(
                        alveoli.stream()
                                .map(it -> visitor.executeOnceOnAlveolus(
                                        null,
                                        it.getManifest(),
                                        it.getAlveolus(),
                                        null,
                                        (ctx, desc) -> {
                                            if (filter.test(
                                                    desc.getConfiguration().name())) {
                                                collected.alveoli.putIfAbsent(
                                                        ctx.getAlveolus().name(), ctx.getAlveolus());
                                                collected
                                                        .descriptors
                                                        .computeIfAbsent(
                                                                ctx.getAlveolus()
                                                                        .name(),
                                                                k -> new ArrayList<>())
                                                        .add(desc);
                                            }
                                            return completedFuture(true);
                                        },
                                        cache,
                                        null,
                                        "inspected",
                                        id))
                                .collect(toList()),
                        toList(),
                        true))
                .thenApply(ok -> collected);
    }

    private Predicate<String> createDescriptorFilter(final String descriptor) {
        if (UNSET.equals(descriptor) || descriptor.isBlank()) {
            return s -> true;
        }
        if (descriptor.startsWith("r/")) {
            return Pattern.compile(descriptor.substring("r/".length())).asMatchPredicate();
        }
        return descriptor::equals;
    }

    protected static class Collected {
        private final Map<String, List<AlveolusHandler.LoadedDescriptor>> descriptors = new ConcurrentHashMap<>();
        private final Map<String, Manifest.Alveolus> alveoli = new ConcurrentHashMap<>();

        public Collected() {}

        public Map<String, List<AlveolusHandler.LoadedDescriptor>> getDescriptors() {
            return this.descriptors;
        }

        public Map<String, Manifest.Alveolus> getAlveoli() {
            return this.alveoli;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            return o instanceof VisitorCommand.Collected other
                    && other.canEqual(this)
                    && Objects.equals(getDescriptors(), other.getDescriptors())
                    && Objects.equals(getAlveoli(), other.getAlveoli());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof VisitorCommand.Collected;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getDescriptors(), getAlveoli());
        }

        @Override
        public String toString() {
            return "VisitorCommand.Collected(descriptors=" + this.getDescriptors() + ", alveoli=" + this.getAlveoli()
                    + ")";
        }
    }
}
