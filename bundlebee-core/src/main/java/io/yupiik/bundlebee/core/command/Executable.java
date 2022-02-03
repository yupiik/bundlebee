/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * Represents a command, must be scope {@link javax.enterprise.context.Dependent}.
 */
public interface Executable {
    /**
     * Since microprofile-config fails at startup when data is not available we use this default value
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

    /**
     * @return a completer to propose option values when relevant. See {@link CompletingExecutable}.
     */
    default Completer completer() {
        return new Completer() {
        };
    }

    interface Completer {
        /**
         * @param config     the current line option configurations (can be wrong so take with cautious).
         * @param optionName the option name to complete the value for.
         * @return the list of proposal for the option.
         */
        default Stream<String> complete(final Map<String, String> config, final String optionName) {
            return Stream.empty();
        }
    }
}
