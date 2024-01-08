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
package io.yupiik.bundlebee.core.http;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.stream.Collectors.toSet;

public class RateLimiter {
    private final int permits;
    private final int window;
    private final Clock clock;
    private final ConcurrentMap<Long, AtomicLong> counterPerWindow = new ConcurrentHashMap<>();
    private final AtomicBoolean cleaning = new AtomicBoolean();

    public RateLimiter(final int rateLimiterPermits, final int rateLimiterWindow, final Clock clock) {
        this.permits = rateLimiterPermits;
        this.window = rateLimiterWindow;
        this.clock = clock;
    }

    public Clock getClock() {
        return clock;
    }

    public int getWindow() {
        return window;
    }

    public long before() {
        if (permits == Integer.MAX_VALUE) { // "disabled"
            return 0;
        }

        final long now = clock.instant().toEpochMilli();
        final long key = now / window;
        final var previousCounter = counterPerWindow.computeIfAbsent(key - 1, k -> new AtomicLong());
        final var counter = counterPerWindow.computeIfAbsent(key, k -> new AtomicLong());
        final var windowCounter = counter.incrementAndGet();
        final var previousCounterValue = previousCounter.get();
        if (previousCounterValue == 0) {
            if (windowCounter <= permits) {
                return 0;
            }
            return nextWindow(key);
        }

        final double weightInWindow = (now - key * window) * 1. / window;
        final double count = previousCounterValue * (1 - weightInWindow) + windowCounter;
        if (counterPerWindow.size() > 60 && cleaning.compareAndSet(false, true)) { // don't do it for each request
            try {
                cleanBuckets(key);
            } finally {
                cleaning.set(false);
            }
        }

        if (count <= permits) {
            return 0;
        }
        return nextWindow(key);
    }

    private long nextWindow(final long key) {
        return (key * window) + window;
    }

    public void after() {
        // no-op in this impl, hook if we update the algorithm
    }

    private void cleanBuckets(final long key) { // control memory usage, nothing more
        final var keys = counterPerWindow.keySet();
        if (keys.size() > 10) {
            final var cleanedUp = keys.stream()
                    .filter(l -> l < key - 2)
                    .collect(toSet());
            if (!cleanedUp.isEmpty()) {
                keys.removeAll(cleanedUp);
            }
        }
    }
}
