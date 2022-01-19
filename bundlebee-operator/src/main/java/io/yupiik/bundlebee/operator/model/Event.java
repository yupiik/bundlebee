/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.operator.model;

import java.util.List;

public class Event {
    private String type;
    private AlveoliObject object;

    public String getType() {
        return type;
    }

    public void setType(final String type) {
        this.type = type;
    }

    public AlveoliObject getObject() {
        return object;
    }

    public void setObject(final AlveoliObject object) {
        this.object = object;
    }

    public static class ObjectMeta {
        private String name;
        private String resourceVersion;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getResourceVersion() {
            return resourceVersion;
        }

        public void setResourceVersion(final String resourceVersion) {
            this.resourceVersion = resourceVersion;
        }
    }

    public static class AlveoliObject {
        private ObjectMeta metadata;
        private AlveoliSpec spec;

        public AlveoliSpec getSpec() {
            return spec;
        }

        public void setSpec(final AlveoliSpec spec) {
            this.spec = spec;
        }

        public ObjectMeta getMetadata() {
            return metadata;
        }

        public void setMetadata(final ObjectMeta metadata) {
            this.metadata = metadata;
        }
    }

    public static class AlveoliSpec { // todo: generate
        private List<String> args;

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(final List<String> args) {
            this.args = args;
        }
    }
}
