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
package io.yupiik.bundlebee.core.descriptor;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/**
 * In cluster state management when enabled.
 * <p>
 * This is a minimalistic flavor, mainly to enable auto-cleanup.
 * <p>
 * Force updates go through annotations, see apply command.
 */
@JsonModel
public record State(
        @Description("Version of the state.") @JsonProperty(order = 0)
        Integer version,

        @Description("Version of the state.") @JsonProperty(order = 1)
        List<Resource> resources) {
    public State {
        version = version == null ? 1 : version;
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    @JsonModel
    public record Resource(
            @Description("Path (url part) of the resource.") @JsonProperty(order = 0)
            String path,

            @Description("Absent for an applied resource; otherwise its pending or failed operation.")
            @JsonProperty(order = 1)
            Status status,

            @Description("HTTP status associated with the pending or failed cleanup, when available.")
            @JsonProperty(order = 2)
            Integer httpStatus) {
        public Resource(final String path) {
            this(path, null, null);
        }
    }

    @JsonModel
    public enum Status {
        APPLY_FAILED,
        PENDING_DELETION,
        DELETE_FAILED
    }
}
