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
package io.yupiik.bundlebee.core.openwebbeans;

import org.apache.geronimo.arthur.api.RegisterClass;
import org.apache.xbean.finder.filter.Filter;

/**
 * Drop at raw scanning time - and not CDI reflection based scanning time - packages we know we don't need.
 * <p>
 * Most important one is handlebars since it leads to a warning otherwise since without the additional dep it will not load.
 */
@RegisterClass(allPublicConstructors = true)
public class BundleBeeFilter implements Filter {
    @Override
    public boolean accept(final String clazz) {
        return !clazz.startsWith("io.yupiik.bundlebee.core.cli.")
                && !clazz.startsWith("io.yupiik.bundlebee.core.configuration.")
                && !clazz.startsWith("io.yupiik.bundlebee.core.descriptor.")
                && !clazz.startsWith("io.yupiik.bundlebee.core.event.")
                && !clazz.startsWith("io.yupiik.bundlebee.core.handlebars.")
                && !clazz.startsWith("io.yupiik.bundlebee.core.openwebbeans.");
    }
}
