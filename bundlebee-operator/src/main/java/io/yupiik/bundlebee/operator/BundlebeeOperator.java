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
package io.yupiik.bundlebee.operator;

import io.yupiik.bundlebee.core.configuration.ConfigurableConfigSource;
import io.yupiik.bundlebee.operator.launcher.OperatorLoop;
import org.eclipse.microprofile.config.Config;

import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toMap;

public final class BundlebeeOperator {
    private BundlebeeOperator() {
        // -no-op
    }

    public static void main(final String... args) {
        final var config = IntStream.rangeClosed(0, (args.length / 2) - 1).boxed().collect(toMap(
                idx -> {
                    final var value = args[1 + idx * 2];
                    return value.startsWith("--") ? value.substring("--".length()) : value;
                },
                idx -> args[(1 + idx) * 2]));

        final var logger = Logger.getLogger(BundlebeeOperator.class.getName());
        try (final var container = SeContainerInitializer.newInstance().initialize()) {
            injectArgsInMPConfig(config, container);
            container.select(OperatorLoop.class).get().run(new AtomicBoolean(true));
            logger.info("Stopping Bundlebee Operator");
        } catch (final Exception e) {
            logger.log(SEVERE, e, e::getMessage);
            throw new IllegalStateException(e);
        }
    }

    private static void injectArgsInMPConfig(final Map<String, String> config, final SeContainer container) {
        StreamSupport.stream(container.select(Config.class).get().getConfigSources().spliterator(), false)
                .filter(ConfigurableConfigSource.class::isInstance)
                .map(ConfigurableConfigSource.class::cast)
                .findFirst()
                .orElseThrow()
                .getProperties()
                .putAll(config);
    }
}
