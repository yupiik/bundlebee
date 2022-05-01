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

import org.eclipse.microprofile.config.Config;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Produces;
import java.util.stream.StreamSupport;

@Dependent
public class ThreadLocalConfigSourceProducer {
    @Produces
    public ThreadLocalConfigSource capture(final Config config) {
        return StreamSupport.stream(config.getConfigSources().spliterator(), false)
                .filter(ThreadLocalConfigSource.class::isInstance)
                .map(ThreadLocalConfigSource.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
