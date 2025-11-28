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
package io.yupiik.bundlebee.core.kube;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletionStage;

// just an indirection to enable to override default client
public interface HttpKubeClient {
    //
    // config
    //

    boolean isDryRun();

    boolean isVerbose();

    String getBaseApi();

    String getNamespace();

    KubeConfig getLoadedKubeConfig();

    HttpClient getClient();

    //
    // runtime
    //

    CompletionStage<HttpResponse<String>> execute(HttpRequest.Builder builder, String urlOrPath);

    HttpRequest prepareRequest(HttpRequest.Builder builder, String urlOrPath);
}
