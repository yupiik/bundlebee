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

import java.util.Map;
import java.util.Objects;

public class OnPrepareDescriptor {
    private final String id;
    private final String alveolus;
    private final String descriptor;
    private final String content;
    private final Map<String, String> placeholders;

    public OnPrepareDescriptor(
            final String id,
            final String alveolus,
            final String descriptor,
            final String content,
            final Map<String, String> placeholders) {
        this.id = id;
        this.alveolus = alveolus;
        this.descriptor = descriptor;
        this.content = content;
        this.placeholders = placeholders;
    }

    public String getId() {
        return this.id;
    }

    public String getAlveolus() {
        return this.alveolus;
    }

    public String getDescriptor() {
        return this.descriptor;
    }

    public String getContent() {
        return this.content;
    }

    public Map<String, String> getPlaceholders() {
        return this.placeholders;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        return o instanceof OnPrepareDescriptor other
                && other.canEqual(this)
                && Objects.equals(getId(), other.getId())
                && Objects.equals(getAlveolus(), other.getAlveolus())
                && Objects.equals(getDescriptor(), other.getDescriptor())
                && Objects.equals(getContent(), other.getContent())
                && Objects.equals(getPlaceholders(), other.getPlaceholders());
    }

    protected boolean canEqual(final Object other) {
        return other instanceof OnPrepareDescriptor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getAlveolus(), getDescriptor(), getContent(), getPlaceholders());
    }

    @Override
    public String toString() {
        return "OnPrepareDescriptor(id=" + this.getId() + ", alveolus=" + this.getAlveolus() + ", descriptor="
                + this.getDescriptor() + ", content=" + this.getContent() + ", placeholders=" + this.getPlaceholders()
                + ")";
    }
}
