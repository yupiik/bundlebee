/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.command.Executable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.util.concurrent.CompletionStage;

@Log
@Dependent
public class ApplyCommand implements Executable {
    @Inject
    @Description("Alveolus name to deploy. When set to `auto`, it will deploy all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.apply.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to deploy. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.apply.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.apply.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("If `true`, a `bundlebee.timestamp` label will be injected into the descritors with current date before applying the descriptor.")
    @ConfigProperty(name = "bundlebee.apply.injectTimestamp", defaultValue = "false")
    private boolean injectTimestamp;

    @Inject
    private KubeClient kube;

    @Inject
    private AlveolusHandler visitor;

    @Override
    public String name() {
        return "apply";
    }

    @Override
    public String description() {
        return "Apply/deploy a set of descriptors from a root one.";
    }

    @Override
    public CompletionStage<?> execute() {
        return visitor.executeOnAlveolus(
                "Deploying", from, manifest, alveolus, null,
                (ctx, desc) -> kube.apply(desc.getContent(), desc.getContent(), injectTimestamp));
    }
}
