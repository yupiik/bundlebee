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
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Data
public class LintError {
    private final LintLevel level;
    private final String message;

    @Getter
    @RequiredArgsConstructor
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
    }
}
