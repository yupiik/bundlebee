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
package io.yupiik.bundlebee.core.event;

import io.yupiik.bundlebee.core.service.ArchiveReader;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class OnLoadArchive {
    private final String coordinates;
    private CompletionStage<ArchiveReader.Archive> loader;

    public OnLoadArchive(final String coordinates) {
        this.coordinates = coordinates;
    }

    public String getCoordinates() {
        return this.coordinates;
    }

    public CompletionStage<ArchiveReader.Archive> getLoader() {
        return this.loader;
    }

    public void setLoader(final CompletionStage<ArchiveReader.Archive> loader) {
        this.loader = loader;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof OnLoadArchive other)) {
            return false;
        }
        return Objects.equals(this.getCoordinates(), other.getCoordinates())
                && Objects.equals(this.getLoader(), other.getLoader());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getCoordinates(), this.getLoader());
    }

    @Override
    public String toString() {
        return "OnLoadArchive(coordinates=" + this.getCoordinates() + ", loader=" + this.getLoader() + ")";
    }
}
