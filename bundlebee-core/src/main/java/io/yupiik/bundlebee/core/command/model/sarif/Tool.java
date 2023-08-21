/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.command.model.sarif;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.json.bind.annotation.JsonbProperty;
import javax.json.bind.annotation.JsonbPropertyOrder;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tool {
    @JsonbProperty
    private Driver driver;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonbPropertyOrder({"name", "informationUri", "rules"})
    public static class Driver {
        @JsonbProperty
        private String name;
        private String informationUri;
        private List<Rule> rules;
    }
}
