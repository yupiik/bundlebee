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
package io.yupiik.bundlebee.core.command.model.sarif;

import static java.util.Collections.unmodifiableMap;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonModel
public record Rule(
        @JsonProperty(order = 0) String id,
        @JsonProperty(order = 1) Text shortDescription,
        @JsonProperty(order = 2) String helpUri,
        @JsonProperty(order = 3) Map<String, Object> properties) {
    public Rule {
        properties = properties == null ? null : unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
