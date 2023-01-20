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
package io.yupiik.bundlebee.core.test.http;

import lombok.Getter;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.internal.impl.DefaultResponseLocator;
import org.talend.sdk.component.junit.http.internal.impl.ResponseImpl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public class SpyingResponseLocator extends DefaultResponseLocator {
    @Getter
    private final List<Response> found = new ArrayList<>();

    public SpyingResponseLocator(final String test) {
        super("mock/http/", test);
    }

    @Override
    public Optional<Response> findMatching(final Request request, final Predicate<String> headerFilter) {
        return findProvidedResponse(request).or(() -> super.findMatching(request, headerFilter).map(it -> {
            synchronized (found) {
                found.add(it);
            }
            return it;
        }));
    }

    private Optional<Response> findProvidedResponse(final Request request) {
        if (request.uri().endsWith("/api/v1")) {
            return Optional.of(new ResponseImpl(Map.of(), 200, ("{\n" +
                    "  \"kind\": \"APIResourceList\",\n" +
                    "  \"groupVersion\": \"v1\",\n" +
                    "  \"resources\": [\n" +
                    "    {\n" +
                    "      \"name\": \"bindings\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Binding\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"componentstatuses\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"ComponentStatus\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"list\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"cs\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"configmaps\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"ConfigMap\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"cm\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"qFsyl6wFWjQ=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"endpoints\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Endpoints\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"ep\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"fWeeMqaN/OA=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"events\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Event\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"ev\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"r2yiGXH7wu8=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"limitranges\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"LimitRange\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"limits\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"EBKMFVe6cwo=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"namespaces\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"Namespace\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"ns\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"Q3oi5N2YM8M=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"namespaces/finalize\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"Namespace\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"namespaces/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"Namespace\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"nodes\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"Node\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"no\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"XwShjMxG9Fs=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"nodes/proxy\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"NodeProxyOptions\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"nodes/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"Node\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"persistentvolumeclaims\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"PersistentVolumeClaim\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"pvc\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"QWTyNDq0dC4=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"persistentvolumeclaims/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"PersistentVolumeClaim\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"persistentvolumes\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"PersistentVolume\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"pv\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"HN/zwEC+JgM=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"persistentvolumes/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": false,\n" +
                    "      \"kind\": \"PersistentVolume\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Pod\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"po\"\n" +
                    "      ],\n" +
                    "      \"categories\": [\n" +
                    "        \"all\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"xPOwRZ+Yhw8=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/attach\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"PodAttachOptions\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"get\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/binding\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Binding\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/eviction\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"group\": \"policy\",\n" +
                    "      \"version\": \"v1beta1\",\n" +
                    "      \"kind\": \"Eviction\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/exec\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"PodExecOptions\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"get\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/log\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Pod\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/portforward\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"PodPortForwardOptions\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"get\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/proxy\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"PodProxyOptions\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"pods/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Pod\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"podtemplates\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"PodTemplate\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"LIXB2x4IFpk=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"replicationcontrollers\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"ReplicationController\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"rc\"\n" +
                    "      ],\n" +
                    "      \"categories\": [\n" +
                    "        \"all\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"Jond2If31h0=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"replicationcontrollers/scale\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"group\": \"autoscaling\",\n" +
                    "      \"version\": \"v1\",\n" +
                    "      \"kind\": \"Scale\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"replicationcontrollers/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"ReplicationController\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"resourcequotas\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"ResourceQuota\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"quota\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"8uhSgffRX6w=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"resourcequotas/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"ResourceQuota\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"secrets\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Secret\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"S6u1pOWzb84=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"serviceaccounts\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"ServiceAccount\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"deletecollection\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"sa\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"pbx9ZvyFpBE=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"serviceaccounts/token\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"group\": \"authentication.k8s.io\",\n" +
                    "      \"version\": \"v1\",\n" +
                    "      \"kind\": \"TokenRequest\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"services\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Service\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"get\",\n" +
                    "        \"list\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\",\n" +
                    "        \"watch\"\n" +
                    "      ],\n" +
                    "      \"shortNames\": [\n" +
                    "        \"svc\"\n" +
                    "      ],\n" +
                    "      \"categories\": [\n" +
                    "        \"all\"\n" +
                    "      ],\n" +
                    "      \"storageVersionHash\": \"0/CO1lhkEBI=\"\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"services/proxy\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"ServiceProxyOptions\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"create\",\n" +
                    "        \"delete\",\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"name\": \"services/status\",\n" +
                    "      \"singularName\": \"\",\n" +
                    "      \"namespaced\": true,\n" +
                    "      \"kind\": \"Service\",\n" +
                    "      \"verbs\": [\n" +
                    "        \"get\",\n" +
                    "        \"patch\",\n" +
                    "        \"update\"\n" +
                    "      ]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}").getBytes(StandardCharsets.UTF_8)));
        }
        return Optional.empty();
    }
}
