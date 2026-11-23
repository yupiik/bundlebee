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
package io.yupiik.bundlebee.core.command.impl.lint;

import java.util.Objects;

public class ContextualLintError extends LintError {
    private final String alveolus;
    private final String descriptor;

    public ContextualLintError(
            final LintLevel level, final String message, final String alveolus, final String descriptor) {
        super(level, message);
        this.alveolus = alveolus;
        this.descriptor = descriptor;
    }

    public String getAlveolus() {
        return this.alveolus;
    }

    public String getDescriptor() {
        return this.descriptor;
    }

    @Override
    public String toString() {
        return "ContextualLintError(super=" + super.toString() + ", alveolus=" + this.getAlveolus() + ", descriptor="
                + this.getDescriptor() + ")";
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ContextualLintError other)) {
            return false;
        }
        return other.canEqual(this)
                && super.equals(o)
                && Objects.equals(this.getAlveolus(), other.getAlveolus())
                && Objects.equals(this.getDescriptor(), other.getDescriptor());
    }

    protected boolean canEqual(final Object other) {
        return other instanceof ContextualLintError;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), this.getAlveolus(), this.getDescriptor());
    }
}
