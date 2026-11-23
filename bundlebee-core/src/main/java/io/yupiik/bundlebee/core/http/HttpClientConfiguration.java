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
package io.yupiik.bundlebee.core.http;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record HttpClientConfiguration(
        @Property(
                defaultValue = "-1",
                documentation =
                        "How many threads are allocated to async HTTP client, negative or zero value means to use common pool.")
        int threads,

        @Property(
                defaultValue = "\"none\"",
                documentation =
                        "The HTTP client version, `none` mean the JVM default (v2), `HTTP_1_1` v1.1 and `HTTP_2` v2.0.")
        String forcedHttpVersion,

        @Property(
                defaultValue = "\"none\"",
                documentation =
                        "The HTTP client connect timeout (in java Duration format), `none` can be used to ignore this setting.")
        String connectTimeout,

        @Property(
                defaultValue = "\"NORMAL\"",
                documentation =
                        "The HTTP client redirect policy. Default to `NORMAL`, can be set to `ALWAYS` or `NEVER`.")
        String followRedirects) {}
