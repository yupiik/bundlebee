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
package io.yupiik.bundlebee.operator.configuration;

import io.yupiik.fusion.cli.CliAwaiter;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import java.util.function.BiPredicate;

/** Keeps Fusion Launcher dedicated to the operator without disabling BundleBee CLI embedding. */
public final class OperatorLauncherModule implements FusionModule {
    @Override
    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
        // Launcher explicitly registers ArgsConfigSource before module beans are filtered.
        // BundleBee.launch registers its own configuration sources instead and still needs CliAwaiter.
        // Inspect registrations only: resolving beans here would happen before module registration completes.
        return (container, bean) -> bean.type() != CliAwaiter.class
                || !container.getBeans().getBeans().containsKey(ArgsConfigSource.class);
    }
}
