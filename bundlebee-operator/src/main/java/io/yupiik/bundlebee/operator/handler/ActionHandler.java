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
package io.yupiik.bundlebee.operator.handler;

import static java.util.Locale.ROOT;

import io.yupiik.bundlebee.operator.model.AlveoliResource;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class ActionHandler {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final CommandRunner runner;

    public ActionHandler(final CommandRunner runner) {
        this.runner = runner;
    }

    public void onEvent(final String type, final AlveoliResource obj) {
        final String command;
        switch (type.toUpperCase(ROOT)) {
            case "DELETED":
                command = "delete";
                break;
            case "ADDED":
            case "MODIFIED":
                // Reapply modified recipes; removed resources are not pruned.
                command = "apply";
                break;
            default:
                logger.warning("[E] Unknown event: type=" + type);
                return;
        }
        logger.info(() -> "Running '" + command + "' for '" + obj.metadata().name() + "'");
        final var arguments =
                obj.spec() == null || obj.spec().args() == null ? Stream.<String>empty() : obj.spec().args().stream();
        final var commandArgs = Stream.concat(Stream.of(command), arguments).toArray(String[]::new);
        runner.run(commandArgs);
    }
}
