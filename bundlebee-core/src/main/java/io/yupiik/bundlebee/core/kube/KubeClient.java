package io.yupiik.bundlebee.core.kube;

import io.yupiik.bundlebee.core.configuration.Description;
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
import javax.inject.Inject;
import javax.json.JsonBuilderFactory;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.json.bind.Jsonb;
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
import java.nio.file.Path;
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
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.lang.CompletionFutures.all;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static lombok.AccessLevel.PRIVATE;

@Log
@ApplicationScoped
public class KubeClient {
    private HttpClient client;

    @Inject
    @BundleBee // just here to inherit from client config - for now the pool
    private HttpClient dontUseAtRuntime;

    @Inject
    private Yaml2JsonConverter yaml2json;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    @BundleBee
    private JsonBuilderFactory jsonBuilderFactory;

    @Inject
    @Description("Kubeconfig location. " +
            "If set to `auto` it will try to guess from your " +
            "`$HOME/.kube/config` file until you set it so `explicit` where it will use other `bundlebee.kube` properties " +
            "to create the client.")
    @ConfigProperty(name = "kubeconfig" /* to match KUBECONFIG env var */, defaultValue = "auto")
    private String kubeConfig;

    @Inject
    @Description("When kubeconfig is not set the base API endpoint.")
    @ConfigProperty(name = "bundlebee.kube.api", defaultValue = "http://localhost:8080")
    private String baseApi;

    @Inject
    @Description("Should SSL connector be validated or not.")
    @ConfigProperty(name = "bundlebee.kube.validateSSL", defaultValue = "true")
    private boolean validateSSL;

    @Inject
    @Description("When kubeconfig is not set the namespace to use.")
    @ConfigProperty(name = "bundlebee.kube.namespace", defaultValue = "default")
    private String namespace;

    private Function<HttpRequest.Builder, HttpRequest.Builder> setAuth;

    @PostConstruct
    private void createCustomClient() {
        client = doConfigure(HttpClient.newBuilder()
                .executor(dontUseAtRuntime.executor().orElseGet(ForkJoinPool::commonPool)))
                .build();
    }

    public CompletionStage<?> apply(final String descriptorContent, final String ext, final boolean injectTimeLabel) {
        final var json = "json".equals(ext) ?
                jsonb.fromJson(descriptorContent.trim(), JsonValue.class) :
                yaml2json.convert(JsonValue.class, descriptorContent.trim());
        switch (json.getValueType()) {
            case ARRAY:
                return all(
                        json.asJsonArray().stream()
                                .map(JsonValue::asJsonObject)
                                .map(it -> doApply(it, injectTimeLabel)
                                        // small trick to type it and make all() working
                                        .thenApply(ignored -> 1))
                                .collect(toList()),
                        summingInt(i -> i),
                        true);
            case OBJECT:
                return doApply(json.asJsonObject(), injectTimeLabel);
            default:
                throw new IllegalArgumentException("Unsupported json type for apply: " + json);
        }
    }

    private CompletionStage<?> doApply(final JsonObject rawDesc, final boolean injectTimeLabel) {
        // apply logic is a "create or replace" one
        // so first thing we have to do is to test if the resource exists, and if not create it
        // for that we will need to extract the resource "kind" and "name" (id):
        //
        // kind: ConfigMap
        // metadata:
        //   name: my-config
        //
        // 1. curl -H "Accept: application/json" 'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>/<name>'
        // if HTTP 200 -> replace
        //   2. curl -XPATCH -H "Content-Type: application/strategic-merge-patch+json" -H "Accept: application/json"
        //          'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>/<name>?fieldManager=kubectl-client-side-apply'
        //          <descriptor in json - or send yaml>
        // else -> create
        //   2. curl -XPOST -H "Accept: application/json" -H "Content-Type: application/json"
        //          'https://192.168.49.2:8443/api/v1/namespaces/<namespace>/<lowercase(kind)>?fieldManager=kubectl-client-side-apply'
        //          <descriptor>
        // end
        final var desc = !injectTimeLabel ? rawDesc : injectTimestampLabel(rawDesc);

        final var kindLowerCased = desc.getString("kind").toLowerCase(ROOT) + 's';
        final var metadata = desc.getJsonObject("metadata");
        final var name = metadata.getString("name");
        final var namespace = metadata.containsKey("namespace") ? metadata.getString("namespace") : this.namespace;
        log.info(() -> "Applying '" + name + "' (kind=" + kindLowerCased + ") for namespace '" + namespace + "'");

        final var fieldManager = "?fieldManager=kubectl-client-side-apply";
        final var baseUri = baseApi + findApiPrefix(kindLowerCased) +
                (!isSkipNameSpace(kindLowerCased) ? "/namespaces/" + namespace : "") +
                "/" + kindLowerCased;
        return client.sendAsync(setAuth.apply(
                HttpRequest.newBuilder(URI.create(baseUri + "/" + name))
                        .GET()
                        .header("Accept", "application/json"))
                        .build(),
                HttpResponse.BodyHandlers.discarding())
                .thenCompose(findResponse -> {
                    if (findResponse.statusCode() > 404) {
                        throw new IllegalStateException("Invalid HTTP response: " + findResponse);
                    }

                    // we re-serialize the json there, we could use the raw descriptor
                    // but in case it is a list it is saner to do it this way
                    if (findResponse.statusCode() == 200) {
                        log.finest(() -> name + " (" + kindLowerCased + ") already exists, updating it");
                        return client.sendAsync(
                                setAuth.apply(HttpRequest.newBuilder(URI.create(baseUri + "/" + name + fieldManager))
                                        .method("PATCH", HttpRequest.BodyPublishers.ofString(desc.toString()))
                                        .header("Content-Type", "application/strategic-merge-patch+json")
                                        .header("Accept", "application/json"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                                .thenApply(response -> {
                                    if (response.statusCode() != 200) {
                                        throw new IllegalStateException("" +
                                                "Can't patch " + name + " (" + kindLowerCased + "): " + response + "\n" +
                                                response.body());
                                    }
                                    return response;
                                });
                    } else {
                        log.finest(() -> name + " (" + kindLowerCased + ") does ont exist, creating it");
                        return client.sendAsync(
                                setAuth.apply(HttpRequest.newBuilder(URI.create(baseUri + fieldManager))
                                        .POST(HttpRequest.BodyPublishers.ofString(desc.toString()))
                                        .header("Content-Type", "application/json")
                                        .header("Accept", "application/json"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                                .thenApply(response -> {
                                    if (response.statusCode() != 201) {
                                        throw new IllegalStateException(
                                                "Can't create " + name + " (" + kindLowerCased + "): " + response + "\n" +
                                                        response.body());
                                    } else {
                                        log.info(() -> "Created " + name + " (" + kindLowerCased + ") successfully");
                                    }
                                    return response;
                                });
                    }
                });
    }

    private boolean isSkipNameSpace(final String kindLowerCased) {
        switch (kindLowerCased) {
            case "nodes":
            case "persistentvolumes":
                return true;
            default:
                return false;
        }
    }

    private String findApiPrefix(final String kindLowerCased) {
        switch (kindLowerCased) {
            case "deployments":
            case "statefulsets":
            case "daemonsets":
            case "replicasets":
            case "controllerrevisions":
                return "/apis/apps/v1";
            case "cronjobs":
                return "/apis/batch/v1beta1";
            case "apiservices":
                return "/apis/apiregistration.k8s.io/v1";
            case "customresourcedefinitions":
                return "/apis/apiextensions.k8s.io/v1beta1";
            case "mutatingwebhookconfigurations":
            case "validatingwebhookconfigurations":
                return "/apis/admissionregistration.k8s.io/v1";
            default:
                return "/api/v1";
        }
    }

    private JsonObject injectTimestampLabel(final JsonObject rawDesc) {
        return jsonBuilderFactory.createObjectBuilder(
                rawDesc.entrySet().stream()
                        .flatMap(entry -> {
                            final var timestampLabelName = "bundlebee.timestamp";
                            final var value = Long.toString(Instant.now().toEpochMilli());

                            if ("metadata".equals(entry.getKey())) {
                                final var metadata = entry.getValue().asJsonObject();
                                if (metadata.containsKey("labels")) {
                                    return mergeTimestampLabel(entry, value, metadata);
                                }
                                // no labels, just add ours
                                return Stream.of(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), jsonBuilderFactory.createObjectBuilder(
                                        Stream.concat(
                                                metadata.entrySet().stream(),
                                                Stream.of(new AbstractMap.SimpleImmutableEntry<>("labels", jsonBuilderFactory.createObjectBuilder()
                                                        .add(timestampLabelName, value)
                                                        .build())))
                                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                                        .build()));
                            }
                            return Stream.of(entry);
                        })
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .build();
    }

    private Stream<Map.Entry<String, JsonValue>> mergeTimestampLabel(
            final Map.Entry<String, JsonValue> entry, final String value,
            final JsonObject metadata) {
        return Stream.of(new AbstractMap.SimpleImmutableEntry<>(
                entry.getKey(),
                jsonBuilderFactory.createObjectBuilder(
                        metadata.entrySet().stream()
                                .flatMap(metadataEntry -> {
                                    if ("labels".equals(metadataEntry.getKey())) {
                                        return Stream.of(new AbstractMap.SimpleImmutableEntry<>(
                                                metadataEntry.getKey(),
                                                jsonBuilderFactory.createObjectBuilder(Stream.concat(
                                                        metadataEntry.getValue().asJsonObject().entrySet().stream()
                                                                .filter(e -> !"bundlebee.timestamp".equals(e.getKey())),
                                                        Stream.of(new AbstractMap.SimpleImmutableEntry<>("labels", jsonBuilderFactory.createObjectBuilder()
                                                                .add("bundlebee.timestamp", value)
                                                                .build())))
                                                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                                                        .build()));
                                    }
                                    return Stream.of(metadataEntry);
                                })
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                        .build()));
    }

    private HttpClient.Builder doConfigure(final HttpClient.Builder builder) {
        if (!"auto".equals(kubeConfig) && !"explicit".equals(kubeConfig)) {
            final var location = Paths.get(kubeConfig);
            if (Files.exists(location)) {
                return doConfigureFrom(location, builder);
            }
        }
        if (!"explicit".equals(kubeConfig)) {
            final var location = Paths.get(System.getProperty("user.home")).resolve(".kube/config");
            if (Files.exists(location)) {
                return doConfigureFrom(location, builder);
            }
        }
        if (setAuth == null) {
            setAuth = identity();
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

    private HttpClient.Builder doConfigureFrom(final Path location, final HttpClient.Builder builder) {
        final KubeConfig config;
        try {
            config = yaml2json.convert(KubeConfig.class, Files.readString(location, StandardCharsets.UTF_8));
            log.info("Read kubeconfig from " + location);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }

        final var currentContext = ofNullable(config.getCurrentContext())
                .orElseGet(() -> {
                    if (config.getClusters() == null || config.getClusters().isEmpty()) {
                        throw new IllegalArgumentException("No current context in " + location + ", ensure to configure kube client please.");
                    }
                    final var key = config.getClusters().iterator().next();
                    log.info(() -> "Will use kube context '" + key + "'");
                    return key.getName();
                });

        final var contextError = "No kube context '" + currentContext + "', ensure to configure kube client please";
        final var context = requireNonNull(
                requireNonNull(config.getContexts(), contextError).stream()
                        .filter(c -> Objects.equals(c.getName(), currentContext))
                        .findFirst()
                        .map(KubeConfig.NamedContext::getContext)
                        .orElseThrow(() -> new IllegalArgumentException(contextError)),
                contextError);
        if (context.getNamespace() != null) {
            namespace = context.getNamespace();
        }

        final var clusterError = "No kube cluster '" + currentContext + "', ensure to configure kube client please";
        final var cluster = requireNonNull(
                requireNonNull(config.getClusters(), clusterError).stream()
                        .filter(c -> Objects.equals(c.getName(), currentContext))
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

        final var userError = "No kube user '" + currentContext + "', ensure to configure kube client please";
        final var user = requireNonNull(
                requireNonNull(config.getUsers(), userError).stream()
                        .filter(c -> Objects.equals(c.getName(), currentContext))
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
        if (!cluster.isInsecureSkipTlsVerify()) {
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
