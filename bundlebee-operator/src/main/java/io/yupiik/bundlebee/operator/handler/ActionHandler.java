/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import io.yupiik.bundlebee.core.cli.Args;
import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.command.impl.ApplyCommand;
import io.yupiik.bundlebee.core.command.impl.DeleteCommand;
import io.yupiik.bundlebee.operator.configuration.ThreadLocalConfigSource;
import io.yupiik.bundlebee.operator.model.Event;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.Locale.ROOT;
import static java.util.logging.Level.SEVERE;

@ApplicationScoped
public class ActionHandler {
    private final Logger logger = Logger.getLogger(getClass().getName());

    @Inject
    private Provider<DeleteCommand> deleteCommandProvider;

    @Inject
    private Provider<ApplyCommand> applyCommandProvider;

    @Inject
    private ThreadLocalConfigSource threadLocalConfigSource;

    public void onEvent(final String type, final Event.AlveoliObject obj) {
        switch (type.toUpperCase(ROOT)) {
            case "DELETED":
                logger.info(() -> "[D] Deleting '" + obj.getMetadata().getName() + "'");
                execute("delete", deleteCommandProvider, obj);
                break;
            case "ADDED":
                logger.info(() -> "[A] Applying '" + obj.getMetadata().getName() + "'");
                execute("apply", applyCommandProvider, obj);
                break;
            case "MODIFIED":
                logger.info(() -> "[U] Updating '" + obj.getMetadata().getName() + "'");
                // NOTE on modified:
                // strictly speaking we just relaunch apply command on modify
                // IMPORTANT/TODO: review the resources associated to previous deployment and delete the ones no more in the recipe
                execute("apply", applyCommandProvider, obj);
                break;
            default:
                logger.warning("[E] Unknown event: type=" + type);
        }
    }

    private void execute(final String cmd, final Provider<? extends Executable> provider, final Event.AlveoliObject obj) {
        threadLocalConfigSource.forConfiguration(toConf(cmd, obj), () -> {
            try {
                return provider.get().execute().toCompletableFuture().get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (final ExecutionException e) {
                logger.log(SEVERE, e, e::getMessage);
                throw new IllegalStateException(e);
            }
        });
    }

    private Map<String, String> toConf(final String cmd, final Event.AlveoliObject obj) {
        return obj.getSpec() == null || obj.getSpec().getArgs() == null || obj.getSpec().getArgs().isEmpty() ?
                Map.of() :
                Args.toProperties(cmd, Stream.concat(Stream.of(cmd), obj.getSpec().getArgs().stream()).toArray(String[]::new));
    }
}
