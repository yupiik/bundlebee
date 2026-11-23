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

import java.util.Objects;

public class OnPlaceholder {
    private final String name;
    private final String defaultValue;
    private final String resolvedValue;
    /**
     * an execution id, not needed anytime offline but enables to support concurrency in some commands.
     */
    private final String id;

    public OnPlaceholder(final String name, final String defaultValue, final String resolvedValue, final String id) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.resolvedValue = resolvedValue;
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public String getDefaultValue() {
        return this.defaultValue;
    }

    public String getResolvedValue() {
        return this.resolvedValue;
    }

    /**
     * an execution id, not needed anytime offline but enables to support concurrency in some commands.
     */
    public String getId() {
        return this.id;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        return o instanceof OnPlaceholder other
                && other.canEqual(this)
                && Objects.equals(getName(), other.getName())
                && Objects.equals(getDefaultValue(), other.getDefaultValue())
                && Objects.equals(getResolvedValue(), other.getResolvedValue())
                && Objects.equals(getId(), other.getId());
    }

    protected boolean canEqual(final Object other) {
        return other instanceof OnPlaceholder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getDefaultValue(), getResolvedValue(), getId());
    }

    @Override
    public String toString() {
        return "OnPlaceholder(name=" + this.getName() + ", defaultValue=" + this.getDefaultValue() + ", resolvedValue="
                + this.getResolvedValue() + ", id=" + this.getId() + ")";
    }
}
