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

import java.util.Map;

@Data
public class OnPrepareDescriptor {
    private final String id;
    private final String alveolus;
    private final String descriptor;
    private final String content;
    private final Map<String, String> placeholders;

    /**
     * @deprecated ensure to pass an id or explicitly {@code null}. Not doing it can have side effects with some commands.
     */
    @Deprecated
    public OnPrepareDescriptor(final String alveolus, final String descriptor, final String content, final Map<String, String> placeholders) {
        this(null, alveolus, descriptor, content, placeholders);
    }

    public OnPrepareDescriptor(final String id, final String alveolus, final String descriptor, final String content, final Map<String, String> placeholders) {
        this.id = id;
        this.alveolus = alveolus;
        this.descriptor = descriptor;
        this.content = content;
        this.placeholders = placeholders;
    }
}
