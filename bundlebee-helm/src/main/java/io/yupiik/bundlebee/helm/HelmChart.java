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
package io.yupiik.bundlebee.helm;

import lombok.Data;

import java.util.Map;

/**
 * Represents a loaded Helm chart with its templates, values, and metadata.
 */
@Data
public class HelmChart {
    private String name;
    private String version;
    private String appVersion;
    private Map<String, Object> values;
    private Map<String, String> templates;
    private Map<String, String> defines;
}
