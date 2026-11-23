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

import java.util.Map;
import java.util.Objects;

/**
 * Represents a loaded Helm chart with its templates, values, and metadata.
 */
public class HelmChart {
    private String name;
    private String version;
    private String appVersion;
    private Map<String, Object> values;
    private Map<String, String> templates;
    private Map<String, String> defines;

    public HelmChart() {}

    public String getName() {
        return this.name;
    }

    public String getVersion() {
        return this.version;
    }

    public String getAppVersion() {
        return this.appVersion;
    }

    public Map<String, Object> getValues() {
        return this.values;
    }

    public Map<String, String> getTemplates() {
        return this.templates;
    }

    public Map<String, String> getDefines() {
        return this.defines;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public void setVersion(final String version) {
        this.version = version;
    }

    public void setAppVersion(final String appVersion) {
        this.appVersion = appVersion;
    }

    public void setValues(final Map<String, Object> values) {
        this.values = values;
    }

    public void setTemplates(final Map<String, String> templates) {
        this.templates = templates;
    }

    public void setDefines(final Map<String, String> defines) {
        this.defines = defines;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof HelmChart other)) {
            return false;
        }
        return other.canEqual(this)
                && Objects.equals(this.getName(), other.getName())
                && Objects.equals(this.getVersion(), other.getVersion())
                && Objects.equals(this.getAppVersion(), other.getAppVersion())
                && Objects.equals(this.getValues(), other.getValues())
                && Objects.equals(this.getTemplates(), other.getTemplates())
                && Objects.equals(this.getDefines(), other.getDefines());
    }

    protected boolean canEqual(final Object other) {
        return other instanceof HelmChart;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.getName(),
                this.getVersion(),
                this.getAppVersion(),
                this.getValues(),
                this.getTemplates(),
                this.getDefines());
    }

    @Override
    public String toString() {
        return "HelmChart(name=" + this.getName() + ", version=" + this.getVersion() + ", appVersion="
                + this.getAppVersion() + ", values=" + this.getValues() + ", templates=" + this.getTemplates()
                + ", defines=" + this.getDefines() + ")";
    }
}
