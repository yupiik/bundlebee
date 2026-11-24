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
package io.yupiik.bundlebee.core.configuration;

import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import java.util.Map;

/** Adds legacy names only after all explicit sources, including Fusion's built-in sources. */
@DefaultScoped
public class LegacyConfigurationAliases {
    private static final Map<String, String> ALIASES =
            Map.of("bundlebee.maven.http.timeout", "bundlebee.maven.http.connectTimeout");

    public void register(@OnEvent final ConfigurationRegistration registration) {
        registration.addSource().accept(key -> {
            final var legacy = ALIASES.get(key);
            return legacy == null
                    ? null
                    : registration.configuration().get(legacy).orElse(null);
        });
    }
}
