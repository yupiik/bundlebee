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
package io.yupiik.bundlebee.operator.handler;

import io.yupiik.bundlebee.operator.configuration.OperatorConfiguration;
import io.yupiik.bundlebee.operator.model.AlveoliResource;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.kubernetes.operator.base.spi.Operator;
import java.util.List;

@ApplicationScoped
public class AlveoliOperator implements Operator<AlveoliResource> {
    private final ActionHandler handler;
    private final OperatorConfiguration configuration;

    public AlveoliOperator(final ActionHandler handler, final OperatorConfiguration configuration) {
        this.handler = handler;
        this.configuration = configuration;
    }

    @Override
    public void onAdd(final AlveoliResource resource) {
        handler.onEvent("ADDED", resource);
    }

    @Override
    public void onModify(final AlveoliResource resource) {
        // A modification reapplies; never delete first or prune removed descriptors implicitly.
        handler.onEvent("MODIFIED", resource);
    }

    @Override
    public void onDelete(final AlveoliResource resource) {
        handler.onEvent("DELETED", resource);
    }

    @Override
    public Class<AlveoliResource> resourceType() {
        return AlveoliResource.class;
    }

    @Override
    public String pluralName() {
        return "alveoli";
    }

    @Override
    public String apiVersion() {
        return "bundlebee.yupiik.io/v1";
    }

    @Override
    public List<String> namespaces() {
        return configuration.namespaces();
    }
}
