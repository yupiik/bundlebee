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
package io.yupiik.bundlebee.helm.fn;

import io.yupiik.bundlebee.helm.HelmFunction;

import javax.enterprise.context.Dependent;

import io.yupiik.bundlebee.core.configuration.Description;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;

import java.security.cert.CertificateEncodingException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Generates a self-signed CA certificate and key pair.
 * Uses JDK internal sun.security.x509 classes for X509 certificate generation.
 */
@Dependent
//metadata:start
// category = Crypto
//metadata:end
@Description("Generates a self-signed certificate authority")
public class GenCAFunc implements HelmFunction {
    @Override
    public String name() {
        return "genCA";
    }

    @Override
    public Object execute(final Object... args) {
        if (args.length < 2 || args[0] == null || args[1] == null) {
            throw new IllegalArgumentException("genCA requires cn and days arguments");
        }
        final var cn = args[0].toString();
        final var days = toInt(args[1]);

        try {
            final var kpGen = KeyPairGenerator.getInstance("RSA");
            kpGen.initialize(2048, new SecureRandom());
            final var kp = kpGen.generateKeyPair();

            final var cert = generateSelfSignedCert(cn, days, kp.getPublic(), kp.getPrivate());

            final var result = new LinkedHashMap<String, String>();
            result.put("Cert", certToPem(cert));
            result.put("Key", rsaPrivateKeyToPem((RSAPrivateCrtKey) kp.getPrivate()));
            return result;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate CA certificate", e);
        }
    }

    private X509Certificate generateSelfSignedCert(final String cn, final int days,
                                                   final java.security.PublicKey publicKey,
                                                   final java.security.PrivateKey privateKey)
            throws Exception {
        final var now = Instant.now();
        final var notBefore = Date.from(now);
        final var notAfter = Date.from(now.plus(days, ChronoUnit.DAYS));

        final var serial = BigInteger.valueOf(now.toEpochMilli());

        // Use reflection to access sun.security.x509 internal API for X509 cert generation
        try {
            return generateWithSunApi(cn, serial, notBefore, notAfter, publicKey, privateKey);
        } catch (final Exception e) {
            return generateWithKeytoolApproach(cn, serial, notBefore, notAfter, publicKey, privateKey);
        }
    }

    private static X509Certificate generateWithSunApi(final String cn, final BigInteger serial,
                                                       final Date notBefore, final Date notAfter,
                                                       final java.security.PublicKey publicKey,
                                                       final java.security.PrivateKey privateKey) throws Exception {
        // sun.security.x509.X509CertImpl approach
        final var certClass = Class.forName("sun.security.x509.X509CertImpl");
        final var builderClass = Class.forName("sun.security.x509.X509CertInfo");
        final var algIdClass = Class.forName("sun.security.x509.AlgorithmId");
        final var certInfo = builderClass.getConstructor().newInstance();

        // Set validity
        final var validityClass = Class.forName("sun.security.x509.CertificateValidity");
        final var validity = validityClass.getConstructor(Date.class, Date.class)
                .newInstance(notBefore, notAfter);
        builderClass.getMethod("set", String.class, Object.class)
                .invoke(certInfo, "validity", validity);

        // Set serial
        final var serialClass = Class.forName("sun.security.x509.CertificateSerialNumber");
        final var serialNumber = serialClass.getConstructor(BigInteger.class).newInstance(serial);
        builderClass.getMethod("set", String.class, Object.class)
                .invoke(certInfo, "serialNumber", serialNumber);

        // Set subject and issuer (self-signed)
        final var x500NameClass = Class.forName("sun.security.x509.X500Name");
        final var x500Name = x500NameClass.getConstructor(String.class)
                .newInstance("CN=" + cn);
        builderClass.getMethod("set", String.class, Object.class)
                .invoke(certInfo, "subject", x500Name);
        builderClass.getMethod("set", String.class, Object.class)
                .invoke(certInfo, "issuer", x500Name);

        // Set public key
        final var pubKeyClass = Class.forName("sun.security.x509.CertificateX509Key");
        final var certPubKey = pubKeyClass.getConstructor(java.security.PublicKey.class)
                .newInstance(publicKey);
        builderClass.getMethod("set", String.class, Object.class)
                .invoke(certInfo, "key", certPubKey);

        // Set version
        builderClass.getMethod("set", String.class, Object.class)
                .invoke(certInfo, "version", new Integer(3));

        // Set algorithm
        final var algId = algIdClass.getMethod("get", String.class).invoke(null, "SHA256withRSA");
        builderClass.getMethod("set", String.class, Object.class)
                .invoke(certInfo, "algorithmId", algId);

        // Create the certificate
        final var cert = certClass.getConstructor(builderClass).newInstance(certInfo);

        // Sign it
        final var signatureClass = Class.forName("sun.security.x509.CertificateAlgorithmId");
        final var sigAlgId = algIdClass.getMethod("get", String.class).invoke(null, "SHA256withRSA");
        signatureClass.getMethod("set", algIdClass).invoke(
                certClass.getMethod("get", String.class).invoke(cert, "algorithmId"), sigAlgId);

        final var sigClass = Class.forName("sun.security.x509.CertificateExtensions");

        final var signerClass = Class.forName("sun.security.x509.X509CertImpl");
        signerClass.getMethod("sign", java.security.PrivateKey.class, String.class)
                .invoke(cert, privateKey, "SHA256withRSA");

        return (X509Certificate) cert;
    }

    private static X509Certificate generateWithKeytoolApproach(final String cn, final BigInteger serial,
                                                                 final Date notBefore, final Date notAfter,
                                                                 final java.security.PublicKey publicKey,
                                                                 final java.security.PrivateKey privateKey)
            throws Exception {
        try {
            final var certGenClass = Class.forName("sun.security.tools.keytool.CertAndKeyGen");
            final var certGen = certGenClass.getConstructor(String.class, String.class)
                    .newInstance("RSA", "SHA256withRSA");
            certGenClass.getMethod("setGenerator", SecureRandom.class)
                    .invoke(certGen, new SecureRandom());
            certGenClass.getMethod("gen", int.class).invoke(certGen, 2048);

            final var x500NameClass = Class.forName("sun.security.x509.X500Name");
            final var x500Name = x500NameClass.getConstructor(String.class)
                    .newInstance("CN=" + cn);
            final var durationDays = java.time.Duration.between(notBefore.toInstant(), notAfter.toInstant()).toDays();
            final var certExtClass = Class.forName("sun.security.x509.CertificateExtensions");
            final var cert = (X509Certificate) certGenClass.getMethod("getSelfCertificate",
                    x500NameClass, Date.class, long.class, certExtClass)
                    .invoke(certGen, x500Name, notBefore, durationDays * 24 * 60 * 60, null);
            return cert;
        } catch (final ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                    "Certificate generation requires sun.security.tools.keytool classes. " +
                    "Add Bouncy Castle as a dependency for full support.", e);
        }
    }

    private int toInt(final Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (final NumberFormatException e) {
            return 365;
        }
    }

    private String certToPem(final java.security.cert.X509Certificate cert) throws CertificateEncodingException {
        final var base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
    }

    private String rsaPrivateKeyToPem(final RSAPrivateCrtKey key) {
        final var sb = new StringBuilder();
        sb.append("-----BEGIN RSA PRIVATE KEY-----\n");
        sb.append(java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(key.getEncoded()));
        sb.append("\n-----END RSA PRIVATE KEY-----\n");
        return sb.toString();
    }
}
