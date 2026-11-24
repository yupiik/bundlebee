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
package io.yupiik.bundlebee.extension;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.util.concurrent.atomic.AtomicInteger;

@DefaultScoped
@Command(name = "metadata-only-extension", description = "Extension metadata.")
public class MetadataOnlyCommand implements Runnable {
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    public MetadataOnlyCommand(final ExtensionOptions options, final UnavailableDependency dependency) {
        CONSTRUCTIONS.incrementAndGet();
        throw new IllegalStateException("Metadata must not initialize commands");
    }

    public static int constructions() {
        return CONSTRUCTIONS.get();
    }

    @Override
    public void run() {
        throw new AssertionError("Metadata must not run commands");
    }

    @DefaultScoped
    public static class UnavailableDependency {
        private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        public UnavailableDependency() {
            CONSTRUCTIONS.incrementAndGet();
            throw new IllegalStateException("Offline dependency");
        }

        public static int constructions() {
            return CONSTRUCTIONS.get();
        }
    }

    @RootConfiguration("-")
    public record ExtensionOptions(
            @Property(value = "test.extension", documentation = "Extension settings.")
            NestedOptions nested) {}

    public record NestedOptions(
            @Property(defaultValue = "\"value\"", documentation = "An extension value.")
            String value) {}
}
