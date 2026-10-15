package com.branchdc.listener;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/** The HTTP client the listener uses for the branch's WSO2 (token endpoint and gateway). */
final class HttpClients {

    private HttpClients() {
    }

    /**
     * Trusts exactly the given certificate for HTTPS (the branch's WSO2 has a self-signed one), with normal
     * hostname checking. No certificate configured: the JDK's default trust store.
     */
    static HttpClient create(ListenerProperties props) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(props.timeout());
        String location = props.trustCertificate();
        if (location != null && !location.isBlank()) {
            Resource cert = new DefaultResourceLoader().getResource(location);
            if (!cert.exists()) {
                throw new IllegalStateException("Gateway certificate not found at " + location + ". Run: node infra/wso2/init-keys.mjs");
            }
            builder.sslContext(trusting(cert));
        }
        return builder.build();
    }

    private static SSLContext trusting(Resource pem) {
        try (InputStream in = pem.getInputStream()) {
            Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("gateway", certificate);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot use gateway certificate " + pem, e);
        }
    }
}
