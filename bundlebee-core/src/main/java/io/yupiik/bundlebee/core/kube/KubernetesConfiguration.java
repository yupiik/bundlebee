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

import static io.yupiik.bundlebee.core.command.Executable.UNSET;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record KubernetesConfiguration(
        @Property(
                defaultValue = "\"auto\"",
                documentation = "Kubeconfig location. " + "If set to `auto` it will try to guess from your "
                        + "`$HOME/.kube/config` file until you set it so `explicit` where it will use other `bundlebee.kube` properties "
                        + "to create the client. The content can also be set inline!")
        String kubeconfig,

        @Property(
                defaultValue = "\"http://localhost:8080\"",
                documentation = "When kubeconfig is not set the base API endpoint.")
        String api,

        @Property(
                defaultValue = "\"" + UNSET + "\"",
                documentation = "When `kubeconfig` is set to `explicit`, the bearer token to use (if set).")
        String token,

        @Property(
                defaultValue = "\"" + UNSET + "\"",
                documentation =
                        "When kubeconfig (explicit or not) is used, the context to use. If not set it is taken from the kubeconfig itself.")
        String context,

        @Property(defaultValue = "\"default\"", documentation = "When kubeconfig is not set the namespace to use.")
        String namespace,

        @Property(defaultValue = "true", documentation = "Should SSL connector be validated or not.")
        boolean validateSSL,

        @Property(
                defaultValue = "false",
                documentation = "If `true` http requests/responses to Kubernetes will be logged.")
        boolean verbose,

        @Property(
                defaultValue = "false",
                documentation = "If `true` http requests/responses are skipped. "
                        + "Note that dry run implies verbose=true for the http client. "
                        + "Note that as of today, all responses are mocked by a HTTP 200 and an empty JSON payload.")
        boolean dryRun,

        @Property(
                defaultValue = "false",
                documentation = "If `true` GET http requests are not skipped when `dryRun` is true.")
        boolean skipDryRunForGet,

        @Property(documentation = "HTTP request settings.") Http http,

        @Property(documentation = "BundleBee rate limiting settings.")
        RateLimiter rateLimiter,

        @Property(documentation = "HTTP proxy settings.") Proxy proxy,

        @Property(
                defaultValue = "\"none\"",
                documentation =
                        "Optional service-account token file, refreshed by Fusion; none disables file authentication.")
        String tokenFile,

        @Property(documentation = "Optional CA certificate file or PEM certificates.")
        String certificates) {
    public record Http(
            @Property(defaultValue = "60000L", documentation = "HTTP timeout in ms, ignored if <= 0.")
            long timeout) {}

    public record RateLimiter(
            @Property(
                    defaultValue = "false",
                    documentation = "Should HTTP client requests be limited and HTTP 427 responses be handled.")
            boolean enabled,

            @Property(
                    defaultValue = "100",
                    documentation =
                            "How many calls can be done if rate limiting is enabled. "
                                    + "Note that setting it to `Integer.MAX_VALUE` will disable the client rate limiting and only enable server one.")
            int permits,

            @Property(
                    defaultValue = "1000",
                    documentation = "Rate limiting window duration in milliseconds (default being 1 second).")
            int window) {}

    public record Proxy(
            @Property(
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If a proxy is needed to contact the target cluster API, its host, ignore if not set.")
            String host,

            @Property(
                    defaultValue = "3128",
                    documentation = "If a proxy is needed to contact the target cluster API, its port.")
            int port,

            @Property(
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If a proxy is needed to contact the target cluster API, its username if it needs an authentication (take care the JVM can nee `-Djdk.http.auth.tunneling.disabledSchemes=` options).")
            String username,

            @Property(
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If a proxy is needed to contact the target cluster API, its password if it needs an authentication (take care the JVM can nee `-Djdk.http.auth.tunneling.disabledSchemes=` options).")
            String password,

            @Property(
                    defaultValue = "true",
                    documentation =
                            "If a proxy is configured to use authentication, automatically set `-Djdk.http.auth.tunneling.disabledSchemes=`, note that setting it on the JVM is still more reliable depending how you run bundlebee (in particular with maven or embed). Important: the system property is \"leaked\", ie it is not cleaned up to limit side effect in concurrent mode.")
            boolean setProxySystemProperties) {}
}
