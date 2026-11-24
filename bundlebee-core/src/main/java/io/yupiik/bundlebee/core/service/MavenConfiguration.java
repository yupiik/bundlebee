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
package io.yupiik.bundlebee.core.service;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record MavenConfiguration(
        @Property(
                value = "http.timeout",
                defaultValue = "30000",
                documentation = "When fetching a dependency using HTTP, the request timeout in milliseconds.")
        int timeout,

        @Property(
                value = "cache",
                defaultValue = "\"auto\"",
                documentation = "Where to cache maven dependencies. "
                        + "If set to `auto`, tries to read the system property `maven.repo.local`"
                        + " then the `settings.xml` `localRepository`"
                        + " and finally it would fallback on `$HOME/.m2/repository`.")
        String cache,

        @Property(
                defaultValue = "true",
                documentation =
                        "If `false` we first try to read `settings.xml` file(s) in `cache` location before the default one.")
        boolean preferCustomSettingsXml,

        @Property(
                defaultValue = "false",
                documentation =
                        "If `true` we only use `cache` value and never fallback on default maven settings.xml location.")
        boolean forceCustomSettingsXml,

        @Property(
                value = "repositories.release",
                defaultValue = "\"https://repo.maven.apache.org/maven2/\"",
                documentation = "Default release repository.")
        String releaseRepository,

        @Property(
                value = "repositories.snapshot",
                defaultValue = "\"" + UNSET + "\"",
                documentation = "Default snapshot repository, not set by default.")
        String snapshotRepository,

        @Property(
                value = "repositories.httpHeaders",
                defaultValue = "\"" + UNSET + "\"",
                documentation =
                        "Properties to define the headers to set per repository, syntax is `host1=headerName headerValue` "
                                + "and it supports as much lines as used repositories. "
                                + "Note that you can use maven `~/.m2/settings.xml` servers (potentially ciphered) username/password pairs. "
                                + "In this last case the server id must be `bundlebee.<server host>`. "
                                + "Still in settings.xml case, if the username is null the password value is used as raw `Authorization` header "
                                + "else username/password is encoded as a basic header.")
        String httpHeaders,

        @Property(
                value = "repositories.downloads.enabled",
                defaultValue = "false",
                documentation = "Enable the download, i.e. ensure it runs only with local maven repository.")
        boolean downloadsEnabled) {}
