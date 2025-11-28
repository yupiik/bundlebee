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
package io.yupiik.bundlebee.core.event;

import lombok.Data;

@Data
public class OnPlaceholder {
    private final String name;
    private final String defaultValue;
    private final String resolvedValue;

    /**
     * an execution id, not needed anytime offline but enables to support concurrency in some commands.
     */
    private final String id;

    /**
     * @deprecated ensure to pass an id or explicitly {@code null}. Not doing it can have side effects with some commands.
     */
    @Deprecated
    public OnPlaceholder(final String name, final String defaultValue, final String resolvedValue) {
        this(name, defaultValue, resolvedValue, null);
    }

    public OnPlaceholder(final String name, final String defaultValue, final String resolvedValue, final String id) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.resolvedValue = resolvedValue;
        this.id = id;
    }
}
