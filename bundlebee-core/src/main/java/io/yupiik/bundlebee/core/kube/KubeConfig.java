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
package io.yupiik.bundlebee.core.kube;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.apache.johnzon.mapper.JohnzonAny;

import javax.json.bind.annotation.JsonbProperty;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static lombok.AccessLevel.NONE;

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
        private String name;
        private Extension extension;
    }

    @Data
    public static class NamedCluster {
        private String name;
        private Cluster cluster;
    }

    @Data
    public static class NamedContext {
        private String name;
        private Context context;
    }

    @Data
    public static class NamedUser {
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

    @Data
    public static class Extension {
        @Setter(NONE)
        @Getter(NONE)
        @JohnzonAny
        private Map<String, Object> values = new TreeMap<>();

        public Map<String, Object> values() {
            return values;
        }
    }
}
