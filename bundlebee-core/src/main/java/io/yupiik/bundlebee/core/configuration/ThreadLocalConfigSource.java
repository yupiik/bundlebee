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
package io.yupiik.bundlebee.core.configuration;

import io.yupiik.bundlebee.core.service.AlveolusHandler;
import org.eclipse.microprofile.config.spi.ConfigSource;

import javax.enterprise.inject.Vetoed;
import java.util.Map;
import java.util.function.Supplier;

@Vetoed
public class ThreadLocalConfigSource implements ConfigSource {
    private final ThreadLocal<Map<String, String>> threadLocal = new ThreadLocal<>();

    @Override
    public Map<String, String> getProperties() {
        final var current = threadLocal.get();
        if (current == null) {
            threadLocal.remove();
            return Map.of();
        }
        return current;
    }

    @Override
    public String getValue(final String s) {
        return getProperties().get(s);
    }

    @Override
    public String getName() {
        return "thread-local";
    }

    public <T> T withContext(final Map<String, String> placeholders, final Supplier<T> task) {
        final var old = threadLocal.get();
        threadLocal.set(placeholders);
        try {
            return task.get();
        } finally {
            if (old == null) {
                threadLocal.remove();
            } else {
                threadLocal.set(old);
            }
        }
    }
}
