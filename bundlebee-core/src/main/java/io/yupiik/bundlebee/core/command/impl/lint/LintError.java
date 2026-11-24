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

import io.yupiik.bundlebee.core.configuration.Description;
import java.util.Objects;

public class LintError {
    private final LintLevel level;
    private final String message;

    public enum LintLevel {
        @Description("Info level")
        INFO(10) {
            @Override
            public String getSarifLevel() {
                return "note";
            }
        },
        @Description("Warning level")
        WARNING(20) {
            @Override
            public String getSarifLevel() {
                return "warning";
            }
        },
        @Description("Error level")
        ERROR(30) {
            @Override
            public String getSarifLevel() {
                return "error";
            }
        },
        @Description("Off level")
        OFF(100) {
            @Override
            public String getSarifLevel() {
                return "note";
            }
        };
        private final int level;

        public abstract String getSarifLevel();

        public int getLevel() {
            return this.level;
        }

        private LintLevel(final int level) {
            this.level = level;
        }
    }

    public LintError(final LintLevel level, final String message) {
        this.level = level;
        this.message = message;
    }

    public LintLevel getLevel() {
        return this.level;
    }

    public String getMessage() {
        return this.message;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof LintError other)) {
            return false;
        }
        return other.canEqual(this)
                && Objects.equals(this.getLevel(), other.getLevel())
                && Objects.equals(this.getMessage(), other.getMessage());
    }

    protected boolean canEqual(final Object other) {
        return other instanceof LintError;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getLevel(), this.getMessage());
    }

    @Override
    public String toString() {
        return "LintError(level=" + this.getLevel() + ", message=" + this.getMessage() + ")";
    }
}
