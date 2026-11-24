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
package io.yupiik.bundlebee.core.service;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@ApplicationScoped
public class Scheduler {
    private ScheduledExecutorService scheduler;

    @Bean
    @ApplicationScoped
    @Order(500) // Fall back to this scheduler when the host application does not provide one.
    public ScheduledExecutorService scheduledExecutorService() {
        return scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final var thread = new Thread(r, Scheduler.class.getName());
            thread.setContextClassLoader(Scheduler.class.getClassLoader());
            return thread;
        });
    }

    @Destroy
    public void release() {
        final var scheduledExecutorService = scheduler;
        if (scheduledExecutorService == null) {
            return;
        }
        scheduledExecutorService.shutdownNow();
        try {
            scheduledExecutorService.awaitTermination(1, SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
