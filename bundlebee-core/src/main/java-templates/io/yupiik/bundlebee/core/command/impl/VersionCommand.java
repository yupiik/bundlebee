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
package io.yupiik.bundlebee.core.command.impl;

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

@DefaultScoped
@Command(name = "version", description = "Shows the application version.")
public class VersionCommand implements Executable {
    private final Logger log = Logger.getLogger(VersionCommand.class.getName());

    public VersionCommand(final Options options) {}

    @Override
    public String name() {
        return "version";
    }

    @Override
    public String description() {
        return "Shows the application version.";
    }

    @Override
    public CompletionStage<?> execute() {
        log.info("${project.version}");
        return completedFuture("${project.version}");
    }

    @RootConfiguration("-")
    public record Options() {}
}
