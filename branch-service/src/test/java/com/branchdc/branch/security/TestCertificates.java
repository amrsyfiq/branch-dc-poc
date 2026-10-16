package com.branchdc.branch.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.List;

/**
 * A key pair plus its self-signed certificate (PEM file), made with the JDK's keytool, the same way
 * infra/wso2/init-keys.mjs makes the WSO2 keys. Stands in for one WSO2 instance in tests.
 */
final class TestCertificates {

    private static final char[] PASSWORD = "test-only".toCharArray();

    private TestCertificates() {
    }

    record Gateway(KeyPair key, Path certificatePem) {
    }

    static Gateway create(String commonName) {
        try {
            Path dir = Files.createTempDirectory("gateway-cert");
            Path keystore = dir.resolve("gateway.p12");
            Path pem = dir.resolve("gateway.pem");
            keytool("-genkeypair", "-alias", "gw", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=" + commonName, "-storetype", "PKCS12", "-keystore", keystore.toString(),
                    "-storepass", new String(PASSWORD));
            keytool("-exportcert", "-rfc", "-alias", "gw", "-keystore", keystore.toString(),
                    "-storepass", new String(PASSWORD), "-file", pem.toString());

            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, PASSWORD);
            }
            Certificate cert = store.getCertificate("gw");
            PrivateKey privateKey = (PrivateKey) store.getKey("gw", PASSWORD);
            return new Gateway(new KeyPair(cert.getPublicKey(), privateKey), pem);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void keytool(String... args) throws IOException {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new java.util.ArrayList<>(List.of(keytool.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
