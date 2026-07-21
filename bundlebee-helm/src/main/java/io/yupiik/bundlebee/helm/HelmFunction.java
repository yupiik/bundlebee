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

/**
 * Represents a Helm template function available during Go template rendering.
 * Each implementation is a CDI bean discovered via {@link io.yupiik.bundlebee.helm.HelmFunctionProducer}.
 */
public interface HelmFunction {
    /**
     * @return the function name as used in Go templates (e.g. "default", "trim", "b64enc").
     */
    String name();

    /**
     * Execute the function with the given arguments.
     *
     * @param args the function arguments (may be empty, never null)
     * @return the function result (can be null for void-like functions)
     */
    Object execute(Object... args);
}
