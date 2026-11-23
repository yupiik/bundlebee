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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Fusion registry that discovers all {@link HelmFunction} beans and provides them by name.
 */
@DefaultScoped
public class HelmFunctionRegistry {
    private final Logger log = Logger.getLogger(HelmFunctionRegistry.class.getName());
    private final Map<String, HelmFunction> functions;

    public HelmFunctionRegistry(final List<HelmFunction> functions) {
        this.functions = functions.stream()
                .collect(Collectors.toMap(HelmFunction::name, Function.identity(), (a, b) -> {
                    log.warning(() -> "Duplicate helm function \'" + a.name() + "\', keeping first");
                    return a;
                }));
        log.fine(() -> "Registered " + this.functions.size() + " helm functions: " + this.functions.keySet());
    }

    public Map<String, HelmFunction> getFunctions() {
        return this.functions;
    }
}
