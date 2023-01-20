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
package io.yupiik.bundlebee.core.kube;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.event.OnKubeRequest;
import io.yupiik.bundlebee.core.http.DelegatingClient;
import io.yupiik.bundlebee.core.http.DryRunClient;
import io.yupiik.bundlebee.core.http.LoggingClient;
import io.yupiik.bundlebee.core.lang.ConfigHolder;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.yaml.Yaml2JsonConverter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.json.JsonException;
import javax.json.bind.JsonbException;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.Executable.UNSET;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static lombok.AccessLevel.PRIVATE;

@Log
@ApplicationScoped
public class HttpKubeClient implements ConfigHolder {
    @Inject
    @BundleBee // just here to inherit from client config - for now the pool
    private HttpClient dontUseAtRuntime;

    @Inject
    private Yaml2JsonConverter yaml2json;

    @Inject
    @Description("Kubeconfig location. " +
            "If set to `auto` it will try to guess from your " +
            "`$HOME/.kube/config` file until you set it so `explicit` where it will use other `bundlebee.kube` properties " +
            "to create the client. The content can also be set inline!")
    @ConfigProperty(name = "kubeconfig" /* to match KUBECONFIG env var */, defaultValue = "auto")
    private String kubeConfig;

    @Inject
    @Getter
    @Description("When kubeconfig is not set the base API endpoint.")
    @ConfigProperty(name = "bundlebee.kube.api", defaultValue = "http://localhost:8080")
    private String baseApi;

    @Inject
    @Description("When `kubeconfig` is set to `explicit`, the bearer token to use (if set).")
    @ConfigProperty(name = "bundlebee.kube.token", defaultValue = UNSET)
    private String token;

    @Inject
    @Description("When kubeconfig (explicit or not) is used, the context to use. If not set it is taken from the kubeconfig itself.")
    @ConfigProperty(name = "bundlebee.kube.context", defaultValue = UNSET)
    private String kubeConfigContext;

    @Inject
    @Description("Should SSL connector be validated or not.")
    @ConfigProperty(name = "bundlebee.kube.validateSSL", defaultValue = "true")
    private boolean validateSSL;

    @Inject
    @Getter
    @Description("When kubeconfig is not set the namespace to use.")
    @ConfigProperty(name = "bundlebee.kube.namespace", defaultValue = "default")
    private String namespace;

    @Inject
    @Getter
    @Description("If `true` http requests/responses to Kubernetes will be logged.")
    @ConfigProperty(name = "bundlebee.kube.verbose", defaultValue = "false")
    private boolean verbose;

    @Inject
    @Getter
    @Description("" +
            "If `true` http requests/responses are skipped. " +
            "Note that dry run implies verbose=true for the http client. " +
            "Note that as of today, all responses are mocked by a HTTP 200 and an empty JSON payload.")
    @ConfigProperty(name = "bundlebee.kube.dryRun", defaultValue = "false")
    private boolean dryRun;

    @Inject
    @Getter
    @Description("If `true` GET http requests are not skipped when `dryRun` is true.")
    @ConfigProperty(name = "bundlebee.kube.skipDryRunForGet", defaultValue = "false")
    private boolean skipDryRunForGet;

    @Inject
    private Event<OnKubeRequest> onKubeRequestEvent;

    private Function<HttpRequest.Builder, HttpRequest.Builder> setAuth;

    @Getter
    private HttpClient client;

    @Getter
    private KubeConfig loadedKubeConfig;

    private Map<String, String> resourceMapping;
    private List<String> kindsToSkipUpdateIfPossible;

    @PostConstruct
    private void init() {
        client = new DelegatingClient(doConfigure(HttpClient.newBuilder()
                .executor(dontUseAtRuntime.executor().orElseGet(ForkJoinPool::commonPool)))
                .build()) {
            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                                    final HttpResponse.BodyHandler<T> responseBodyHandler) {
                final OnKubeRequest kubeRequest = new OnKubeRequest(request);
                onKubeRequestEvent.fire(kubeRequest);
                if (kubeRequest.getUserResponse() != null) {
                    return CompletableFuture.class.cast(kubeRequest.getUserResponse().toCompletableFuture());
                }
                if (kubeRequest.getUserRequest() != null) {
                    return doSendAsync(kubeRequest.getUserRequest(), responseBodyHandler);
                }
                return doSendAsync(request, responseBodyHandler);
            }

            private <T> CompletableFuture<HttpResponse<T>> doSendAsync(final HttpRequest request,
                                                                       final HttpResponse.BodyHandler<T> responseBodyHandler) {
                return super.sendAsync(request, responseBodyHandler)
                        // enforce the right classloader
                        .whenCompleteAsync((r, t) -> {
                        }, client.executor().orElseGet(ForkJoinPool::commonPool));
            }
        };
        if (dryRun) {
            client = new LoggingClient(log, new DryRunClient(client, skipDryRunForGet));
        } else if (verbose) {
            client = new LoggingClient(log, client);
        }
        if (loadedKubeConfig == null || loadedKubeConfig.getClusters() == null || loadedKubeConfig.getClusters().isEmpty()) {
            final var c = new KubeConfig.Cluster();
            c.setServer(baseApi);

            final var cluster = new KubeConfig.NamedCluster();
            cluster.setName("default");
            cluster.setCluster(c);

            loadedKubeConfig = new KubeConfig();
            loadedKubeConfig.setClusters(List.of(cluster));
        }
    }

    public CompletionStage<HttpResponse<String>> execute(final HttpRequest.Builder builder, final String urlOrPath) {
        return client.sendAsync(
                prepareRequest(builder, urlOrPath),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public HttpRequest prepareRequest(final HttpRequest.Builder builder, final String urlOrPath) {
        return setAuth.apply(builder)
                .uri(URI.create(
                        urlOrPath.startsWith("http:") || urlOrPath.startsWith("https:") ?
                                urlOrPath :
                                (baseApi + urlOrPath)))
                .build();
    }

    private HttpClient.Builder doConfigure(final HttpClient.Builder builder) {
        if (!"auto".equals(kubeConfig) && !"explicit".equals(kubeConfig)) {
            final var location = Paths.get(kubeConfig);
            if (Files.exists(location)) {
                try {
                    return doConfigureFrom(location.toString(), Files.readString(location, StandardCharsets.UTF_8), builder);
                } catch (final IOException ioe) {
                    throw new IllegalStateException(ioe);
                }
            }
            if (kubeConfig.startsWith("{") /* json */ || kubeConfig.contains("apiVersion") /* weak yaml test */) {
                log.info(() -> "Using in memory kubeconfig");
                return doConfigureFrom("in-memory", kubeConfig.strip(), builder);
            }
        }
        if (!"explicit".equals(kubeConfig)) {
            final var location = Paths.get(System.getProperty("user.home")).resolve(".kube/config");
            if (Files.exists(location)) {
                try {
                    return doConfigureFrom(location.toString(), Files.readString(location, StandardCharsets.UTF_8), builder);
                } catch (final IOException ioe) {
                    throw new IllegalStateException(ioe);
                }
            }
        }
        if (setAuth == null) {
            setAuth = UNSET.equals(token) ? identity() : r -> r.header("Authorization", "Bearer " + token);
        }
        if (!validateSSL && baseApi.startsWith("https")) {
            // can be too late but let's try anyway, drawback is it is global but it is protected by this validateSSL toggle
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification",
                    System.getProperty("jdk.internal.httpclient.disableHostnameVerification", "true"));

            try {
                final var sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, newNoopTrustManager(), new SecureRandom());

                return builder.sslContext(sslContext);
            } catch (final GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
        }
        return builder;
    }

    private HttpClient.Builder doConfigureFrom(final String from, final String content, final HttpClient.Builder builder) {
        try {
            loadedKubeConfig = yaml2json.convert(KubeConfig.class, content);
            log.info("Read kubeconfig from " + from);
        } catch (final JsonException | JsonbException jsonEx) {
            throw new IllegalStateException("Can't read '" + from + "': " + jsonEx.getMessage(), jsonEx);
        }
        return doConfigureFromLoadedKubeConfig(from, builder);
    }

    private HttpClient.Builder doConfigureFromLoadedKubeConfig(final String location, final HttpClient.Builder builder) {
        final var currentContext = of(kubeConfigContext)
                .filter(it -> !UNSET.equals(it))
                .orElseGet(() -> ofNullable(loadedKubeConfig.getCurrentContext())
                        .orElseGet(() -> {
                            if (loadedKubeConfig.getClusters() == null || loadedKubeConfig.getClusters().isEmpty()) {
                                throw new IllegalArgumentException("No current context in " + location + ", ensure to configure kube client please.");
                            }
                            final var key = loadedKubeConfig.getClusters().iterator().next();
                            log.info(() -> "Will use kube context '" + key + "'");
                            return key.getName();
                        }));

        final var contextError = "No kube context '" + currentContext + "', ensure to configure kube client please";
        final var context = requireNonNull(
                requireNonNull(loadedKubeConfig.getContexts(), contextError).stream()
                        .filter(c -> Objects.equals(c.getName(), currentContext))
                        .findFirst()
                        .map(KubeConfig.NamedContext::getContext)
                        .orElseThrow(() -> new IllegalArgumentException(contextError)),
                contextError);
        if (context.getNamespace() != null && "default".equals(namespace) /*else user set it*/) {
            namespace = context.getNamespace();
        }

        final var clusterError = "No kube cluster '" + context.getCluster() + "', ensure to configure kube client please";
        final var cluster = requireNonNull(
                requireNonNull(loadedKubeConfig.getClusters(), clusterError).stream()
                        .filter(c -> Objects.equals(c.getName(), context.getCluster()))
                        .findFirst()
                        .map(KubeConfig.NamedCluster::getCluster)
                        .orElseThrow(() -> new IllegalArgumentException(clusterError)),
                clusterError);

        final var server = cluster.getServer();
        if (server != null && !server.contains("://")) {
            if (server.contains(":443")) {
                this.baseApi = "https://" + server;
            } else {
                this.baseApi = "http://" + server;
            }
        } else if (server != null) {
            this.baseApi = server;
        }
        if (this.baseApi.endsWith("/")) {
            this.baseApi = this.baseApi.substring(0, this.baseApi.length() - 1);
        }

        final var userError = "No kube user '" + context.getUser() + "', ensure to configure kube client please";
        final var user = requireNonNull(
                requireNonNull(loadedKubeConfig.getUsers(), userError).stream()
                        .filter(c -> Objects.equals(c.getName(), context.getUser()))
                        .findFirst()
                        .map(KubeConfig.NamedUser::getUser)
                        .orElseThrow(() -> new IllegalArgumentException(userError)),
                userError);

        KeyManager[] keyManagers = null;
        if (user.getUsername() != null && user.getPassword() != null) {
            final var auth = "Basic " + Base64.getEncoder().encodeToString((user.getUsername() + ':' + user.getPassword()).getBytes(StandardCharsets.UTF_8));
            setAuth = r -> r.header("Authorization", auth);
        } else if (user.getToken() != null) {
            setAuth = r -> r.header("Authorization", "Bearer " + user.getToken());
        } else if (user.getTokenFile() != null) {
            try {
                final var token = Files.readString(Paths.get(user.getTokenFile()), StandardCharsets.UTF_8).trim();
                setAuth = r -> r.header("Authorization", "Bearer " + token);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        } else if ((user.getClientCertificate() != null || user.getClientCertificateData() != null) &&
                (user.getClientKey() != null || user.getClientKeyData() != null)) {
            final byte[] certificateBytes;
            final byte[] keyBytes;
            try {
                certificateBytes = user.getClientCertificateData() != null ?
                        Base64.getDecoder().decode(user.getClientCertificateData()) :
                        Files.readAllBytes(Paths.get(user.getClientCertificate()));
                keyBytes = user.getClientKeyData() != null ?
                        Base64.getDecoder().decode(user.getClientKeyData()) :
                        Files.readAllBytes(Paths.get(user.getClientKey()));
                final var keyStr = new String(keyBytes, StandardCharsets.UTF_8);
                final String algo;
                if (keyStr.contains("BEGIN EC PRIVATE KEY")) {
                    algo = "EC";
                } else if (keyStr.contains("BEGIN RSA PRIVATE KEY")) {
                    algo = "RSA";
                } else {
                    algo = "";
                }
                try (final var certStream = new ByteArrayInputStream(certificateBytes)) {
                    final var certificateFactory = CertificateFactory.getInstance("X509");
                    final var cert = X509Certificate.class.cast(certificateFactory.generateCertificate(certStream));
                    final var privateKey = PEM.readPrivateKey(keyStr, algo);
                    final var keyStore = KeyStore.getInstance("JKS");
                    keyStore.load(null);

                    keyStore.setKeyEntry(
                            cert.getSubjectX500Principal().getName(),
                            privateKey, new char[0], new X509Certificate[]{cert});

                    final var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    keyManagerFactory.init(keyStore, new char[0]);

                    keyManagers = keyManagerFactory.getKeyManagers();
                } catch (final NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException | KeyStoreException | IOException e) {
                    throw new IllegalStateException(e);
                }
            } catch (final RuntimeException re) {
                throw re;
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            setAuth = identity(); // only SSL
        } else { // shouldn't happen
            log.info("No security found for Kuber client, this is an unusual setup");
            setAuth = identity();
        }

        if (cluster.getCertificateAuthorityData() == null && cluster.getCertificateAuthority() == null) {
            return builder;
        }

        final byte[] certificateBytes;
        try {
            certificateBytes = cluster.getCertificateAuthorityData() != null ?
                    Base64.getDecoder().decode(cluster.getCertificateAuthorityData()) :
                    Files.readAllBytes(Paths.get(cluster.getCertificateAuthority()));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        try {
            final var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, findTrustManager(cluster, certificateBytes), new SecureRandom());

            return builder.sslContext(sslContext);
        } catch (final IOException | GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    private TrustManager[] findTrustManager(final KubeConfig.Cluster cluster, final byte[] certificateBytes)
            throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException {
        if (cluster.isInsecureSkipTlsVerify()) {
            return newNoopTrustManager();
        }
        final var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (certificateBytes == null) {
            trustManagerFactory.init((KeyStore) null);
        } else {
            final var certificateFactory = CertificateFactory.getInstance("X.509");
            final var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            try (final var stream = new ByteArrayInputStream(certificateBytes)) {
                final var certificates = certificateFactory.generateCertificates(stream);
                if (certificates.isEmpty()) {
                    throw new IllegalArgumentException("No certificate found for kube client");
                }
                final var idx = new AtomicInteger();
                certificates.forEach(cert -> {
                    try {
                        keyStore.setCertificateEntry("ca-" + idx.incrementAndGet(), cert);
                    } catch (final KeyStoreException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            trustManagerFactory.init(keyStore);
        }
        return trustManagerFactory.getTrustManagers();
    }

    private TrustManager[] newNoopTrustManager() {
        return new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
                        // no-op
                    }

                    @Override
                    public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
                        // no-op
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
    }

    @NoArgsConstructor(access = PRIVATE)
    private static class PEM {
        private static PrivateKey rsaPrivateKeyFromPKCS8(final byte[] pkcs8) {
            try {
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IllegalStateException(e);
            }
        }

        private static PrivateKey rsaPrivateKeyFromPKCS1(final byte[] pkcs1) {
            try {
                return KeyFactory.getInstance("RSA").generatePrivate(newRSAPrivateCrtKeySpec(pkcs1));
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IllegalStateException(e);
            }
        }

        private static RSAPrivateCrtKeySpec newRSAPrivateCrtKeySpec(final byte[] keyInPkcs1) throws IOException {
            final DerReader parser = new DerReader(keyInPkcs1);
            final Asn1Object sequence = parser.read();
            if (sequence.getType() != DerReader.SEQUENCE) {
                throw new IllegalArgumentException("Invalid DER: not a sequence");
            }

            final DerReader derReader = sequence.getParser();
            derReader.read(); // version
            return new RSAPrivateCrtKeySpec(
                    derReader.read().getInteger(),
                    derReader.read().getInteger(),
                    derReader.read().getInteger(),
                    derReader.read().getInteger(),
                    derReader.read().getInteger(),
                    derReader.read().getInteger(),
                    derReader.read().getInteger(),
                    derReader.read().getInteger());
        }

        private static PrivateKey ecPrivateKeyFromPKCS8(final byte[] pkcs8) {
            try {
                return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            } catch (final InvalidKeySpecException | NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static PrivateKey readPrivateKey(final String pem, final String alg) throws IOException {
            return readPEMObjects(pem).stream()
                    .map(object -> {
                        switch (PEMType.fromBegin(object.getBeginMarker())) {
                            case PRIVATE_KEY_PKCS1:
                                return rsaPrivateKeyFromPKCS1(object.getDerBytes());
                            case PRIVATE_EC_KEY_PKCS8:
                                return ecPrivateKeyFromPKCS8(object.getDerBytes());
                            case PRIVATE_KEY_PKCS8:
                                if (alg.equalsIgnoreCase("rsa")) {
                                    return rsaPrivateKeyFromPKCS8(object.getDerBytes());
                                }
                                return ecPrivateKeyFromPKCS8(object.getDerBytes());
                            default:
                                return null;
                        }
                    }).filter(Objects::nonNull)
                    .findFirst()
                    .orElseGet(() -> {
                        if (!pem.startsWith("---")) {
                            if (alg.equalsIgnoreCase("rsa")) {
                                return rsaPrivateKeyFromPKCS8(Base64.getDecoder().decode(pem));
                            }
                            return ecPrivateKeyFromPKCS8(Base64.getDecoder().decode(pem));
                        }
                        throw new IllegalArgumentException("Invalid key: " + pem);
                    });
        }

        private static List<PEMObject> readPEMObjects(final String pem) throws IOException {
            try (final BufferedReader reader = new BufferedReader(new StringReader(pem))) {
                final List<PEMObject> pemContents = new ArrayList<>();
                boolean readingContent = false;
                String beginMarker = null;
                String endMarker = null;
                StringBuffer sb = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (readingContent) {
                        if (line.contains(endMarker)) {
                            pemContents.add(new PEMObject(beginMarker, Base64.getDecoder().decode(sb.toString())));
                            readingContent = false;
                        } else {
                            sb.append(line.trim());
                        }
                    } else {
                        if (line.contains("-----BEGIN ")) {
                            readingContent = true;
                            beginMarker = line.trim();
                            endMarker = beginMarker.replace("BEGIN", "END");
                            sb = new StringBuffer();
                        }
                    }
                }
                return pemContents;
            }
        }

        @Data
        private static class Asn1Object {
            protected final int type;
            protected final int length;
            protected final byte[] value;
            protected final int tag;

            private boolean isConstructed() {
                return (tag & DerReader.CONSTRUCTED) == DerReader.CONSTRUCTED;
            }

            private DerReader getParser() throws IOException {
                if (!isConstructed()) {
                    throw new IOException("Invalid DER: can't parse primitive entity");
                }

                return new DerReader(value);
            }

            private BigInteger getInteger() throws IOException {
                if (type != DerReader.INTEGER) {
                    throw new IOException("Invalid DER: object is not integer");
                }

                return new BigInteger(value);
            }
        }

        private static class DerReader {
            private final static int CONSTRUCTED = 0x20;
            private final static int INTEGER = 0x02;
            private final static int SEQUENCE = 0x10;

            private final InputStream in;

            private DerReader(final byte[] bytes) {
                in = new ByteArrayInputStream(bytes);
            }

            private Asn1Object read() throws IOException {
                final int tag = in.read();
                if (tag == -1) {
                    throw new IOException("Invalid DER: stream too short, missing tag");
                }

                final int length = length();
                final byte[] value = new byte[length];
                final int n = in.read(value);
                if (n < length) {
                    throw new IOException("Invalid DER: stream too short, missing value");
                }
                return new Asn1Object(tag & 0x1F, length, value, tag);
            }

            private int length() throws IOException {
                final int i = in.read();
                if (i == -1) {
                    throw new IOException("Invalid DER: length missing");
                }
                if ((i & ~0x7F) == 0) {
                    return i;
                }
                final int num = i & 0x7F;
                if (i >= 0xFF || num > 4) {
                    throw new IOException("Invalid DER: length field too big (" + i + ")");
                }
                final byte[] bytes = new byte[num];
                final int n = in.read(bytes);
                if (n < num) {
                    throw new IOException("Invalid DER: length too short");
                }
                return new BigInteger(1, bytes).intValue();
            }
        }

        @Getter
        @AllArgsConstructor
        private static class PEMObject {
            private final String beginMarker;
            private final byte[] derBytes;
        }

        private enum PEMType {
            PRIVATE_KEY_PKCS1("-----BEGIN RSA PRIVATE KEY-----"),
            PRIVATE_EC_KEY_PKCS8("-----BEGIN EC PRIVATE KEY-----"),
            PRIVATE_KEY_PKCS8("-----BEGIN PRIVATE KEY-----"),
            PUBLIC_KEY_X509("-----BEGIN PUBLIC KEY-----"),
            CERTIFICATE_X509("-----BEGIN CERTIFICATE-----");

            private final String beginMarker;

            PEMType(final String beginMarker) {
                this.beginMarker = beginMarker;
            }

            private static PEMType fromBegin(final String beginMarker) {
                return Stream.of(values())
                        .filter(it -> it.beginMarker.equalsIgnoreCase(beginMarker))
                        .findFirst()
                        .orElse(null);
            }
        }
    }
}
