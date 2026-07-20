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

import lombok.Getter;
import lombok.extern.java.Log;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CDI registry that discovers all {@link HelmFunction} beans and provides them by name.
 */
@Log
@ApplicationScoped
public class HelmFunctionProducer {
    @Inject
    @Any
    private Instance<HelmFunction> functionInstance;

    @Getter
    private Map<String, HelmFunction> functions;

    @PostConstruct
    private void init() {
        functions = functionInstance.stream()
                .collect(Collectors.toMap(
                        HelmFunction::name,
                        Function.identity(),
                        (a, b) -> {
                            log.warning(() -> "Duplicate helm function '" + a.name() + "', keeping first");
                            return a;
                        }));
        log.fine(() -> "Registered " + functions.size() + " helm functions: " + functions.keySet());
    }
}
