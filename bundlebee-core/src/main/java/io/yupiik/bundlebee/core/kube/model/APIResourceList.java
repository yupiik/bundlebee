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
package io.yupiik.bundlebee.core.kube.model;

import lombok.Data;

import javax.json.bind.annotation.JsonbProperty;
import java.util.List;

@Data
public class APIResourceList {
    @JsonbProperty
    private String kind;
    private String apiVersion;
    private List<Item> resources;

    @Data
    public static class Item {
        @JsonbProperty
        private String group;
        private String kind;
        private String name; // plural name
        private String singularName;
        private String version;
        private boolean namespaced;
        private List<String> verb;
    }
}
