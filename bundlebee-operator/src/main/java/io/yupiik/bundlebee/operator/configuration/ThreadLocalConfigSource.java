/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.operator.configuration;

import org.eclipse.microprofile.config.spi.ConfigSource;

import javax.enterprise.inject.Vetoed;
import java.util.Map;
import java.util.function.Supplier;

@Vetoed
public class ThreadLocalConfigSource implements ConfigSource {
    private final ThreadLocal<Map<String, String>> threadLocal = new ThreadLocal<>();

    @Override
    public Map<String, String> getProperties() {
        final var map = threadLocal.get();
        if (map == null) {
            threadLocal.remove();
            return Map.of();
        }
        return map;
    }

    @Override
    public String getValue(final String s) {
        return getProperties().get(s);
    }

    @Override
    public String getName() {
        return "thread-local-config-source";
    }

    public <T> T forConfiguration(final Map<String, String> config, final Supplier<T> task) {
        threadLocal.set(config);
        try {
            return task.get();
        } finally {
            threadLocal.remove();
        }
    }
}
