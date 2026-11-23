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
package io.yupiik.bundlebee.core.command;

import java.util.concurrent.CompletionStage;

/**
 * Represents a command, must be scope {@link io.yupiik.fusion.framework.api.scope.DefaultScoped}.
 */
public interface Executable extends Runnable {

    @Override
    default void run() {
        try {
            execute().toCompletableFuture().get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execution interrupted", e);
        } catch (final java.util.concurrent.ExecutionException e) {
            final var cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Optional command arguments use this sentinel when no explicit value is available
     * to mark a config entry as required for a command.
     * Command must then test it and fail if needed in its execute method.
     */
    String UNSET = "<unset>";

    /**
     * @return the command name.
     */
    String name();

    /**
     * @return the command must not be seen in the healp.
     */
    default boolean hidden() {
        return false;
    }

    /**
     * The command help text.
     *
     * @return command description.
     */
    String description();

    /**
     * @return execute this command.
     */
    CompletionStage<?> execute();
}
