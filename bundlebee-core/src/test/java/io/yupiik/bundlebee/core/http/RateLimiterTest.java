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
package io.yupiik.bundlebee.core.http;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimiterTest {
    @Test
    void rateLimit() {
        final var instant = new AtomicReference<>(Instant.ofEpochMilli(0));
        final var rateLimiter = new RateLimiter(3, 1_000, new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return instant.get();
            }
        });

        assertEquals(0, rateLimiter.before());
        assertEquals(0, rateLimiter.before());
        assertEquals(0, rateLimiter.before());
        {
            final var nextWindow = instant.get().toEpochMilli() + 1000;
            assertEquals(nextWindow, rateLimiter.before());
            instant.set(Instant.ofEpochMilli(nextWindow));
        }

        assertEquals(2000, rateLimiter.before());
        assertEquals(2000, rateLimiter.before());
        assertEquals(2000, rateLimiter.before());
        {
            final var nextWindow = instant.get().toEpochMilli() + 1000;
            assertEquals(nextWindow, rateLimiter.before());
            instant.set(Instant.ofEpochMilli(nextWindow + 1000));
        }

        assertEquals(0, rateLimiter.before());
        assertEquals(0, rateLimiter.before());
        assertEquals(0, rateLimiter.before());
    }
}
