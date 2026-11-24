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
package io.yupiik.bundlebee.extension;

import io.yupiik.bundlebee.core.event.OnPlaceholder;
import io.yupiik.bundlebee.core.event.OnPrepareDescriptor;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;

/** A real processor-registered extension: no hand-written bean or module. */
@DefaultScoped
public class ScriptExtension {
    public String value() {
        return "extension";
    }

    public void first(@OnEvent @Order(100) final OnPrepareDescriptor event) {
        if ("script-extension".equals(event.getId())) {
            event.getPlaceholders().put("observer.order", "first");
            event.getPlaceholders()
                    .put("observer.thread", Thread.currentThread().getName());
        }
    }

    public void second(@OnEvent @Order(200) final OnPrepareDescriptor event) {
        if ("script-extension".equals(event.getId())) {
            event.getPlaceholders().compute("observer.order", (key, value) -> value + ",second");
        }
    }

    public void reject(@OnEvent final OnPlaceholder event) {
        if ("script-extension".equals(event.getId()) && "script-extension.fail".equals(event.getName())) {
            throw new IllegalArgumentException("extension rejected placeholder");
        }
    }
}
