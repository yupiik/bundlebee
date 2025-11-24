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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * In cluster state management when enabled.
 * <p>
 * This is a minimalistic flavor, mainly to enable auto-cleanup.
 * <p>
 * Force updates go through annotations, see apply command.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class State {
    @Description("Version of the state.")
    private int version = 1;

    @Description("Version of the state.")
    private List<Resource> resources = new ArrayList<>();

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Resource {
        @Description("Path (url part) of the resource.")
        private String path;
    }
}
