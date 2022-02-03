/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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

import lombok.Data;

import javax.json.JsonObject;
import javax.json.bind.annotation.JsonbProperty;
import java.util.List;

@Data
public class KubeConfig {
    private String apiVersion;
    private String kind;

    @JsonbProperty("current-context")
    private String currentContext;

    private List<NamedCluster> clusters;
    private List<NamedContext> contexts;
    private List<NamedUser> users;

    @Data
    public static class NamedExtension {
        @JsonbProperty("name") // to let arthur detect it with our conf, not strictly needed in jvm mode
        private String name;
        private JsonObject extension;
    }

    @Data
    public static class NamedCluster {
        @JsonbProperty("name") // to let arthur detect it with our conf, not strictly needed in jvm mode
        private String name;
        private Cluster cluster;
    }

    @Data
    public static class NamedContext {
        @JsonbProperty("name") // to let arthur detect it with our conf, not strictly needed in jvm mode
        private String name;
        private Context context;
    }

    @Data
    public static class NamedUser {
        @JsonbProperty("name") // to let arthur detect it with our conf, not strictly needed in jvm mode
        private String name;
        private User user;
    }

    @Data
    public static class User {
        private String username;
        private String password;

        private String token;
        private String tokenFile;

        @JsonbProperty("client-certificate")
        private String clientCertificate;

        @JsonbProperty("client-certificate-data")
        private String clientCertificateData;

        @JsonbProperty("client-key")
        private String clientKey;

        @JsonbProperty("client-key-data")
        private String clientKeyData;
    }

    @Data
    public static class Context {
        private String cluster;
        private String namespace;
        private String user;

        @JsonbProperty("certificate-authority")
        private String certificateAuthority;

        private List<NamedExtension> extensions;
    }

    @Data
    public static class Cluster {
        private String server;

        @JsonbProperty("certificate-authority")
        private String certificateAuthority;

        @JsonbProperty("certificate-authority-data")
        private String certificateAuthorityData;

        @JsonbProperty("insecure-skip-tls-verify")
        private boolean insecureSkipTlsVerify;

        private List<NamedExtension> extensions;
    }
}
