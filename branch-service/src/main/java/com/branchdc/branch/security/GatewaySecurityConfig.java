package com.branchdc.branch.security;

import com.branchdc.branch.BranchProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

/**
 * {@code branch.security.mode=wso2} (the default): every request must come through this branch's WSO2.
 * {@code none} switches the check off; only for tests and local runs without WSO2.
 */
@Configuration
@ConditionalOnProperty(name = "branch.security.mode", havingValue = "wso2", matchIfMissing = true)
class GatewaySecurityConfig {

    @Bean
    GatewayJwtVerifier gatewayJwtVerifier(@Value("${branch.security.certificate}") Resource certificate,
                                          @Value("${branch.security.issuer}") String issuer)
            throws IOException, CertificateException {
        return new GatewayJwtVerifier(readPublicKey(certificate), issuer, Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<GatewayJwtFilter> gatewayJwtFilter(GatewayJwtVerifier verifier, BranchProperties branch,
                                                              JsonMapper json) {
        return new FilterRegistrationBean<>(new GatewayJwtFilter(verifier, branch.id(), json));
    }

    /** This branch's WSO2 signing certificate (PEM, from infra/wso2/init-keys.mjs). Only its public key is used. */
    static RSAPublicKey readPublicKey(Resource certificate) throws IOException, CertificateException {
        if (!certificate.exists()) {
            throw new IllegalStateException("WSO2 signing certificate not found at " + certificate
                    + ". Run: node infra/wso2/init-keys.mjs");
        }
        try (InputStream in = certificate.getInputStream()) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return (RSAPublicKey) cert.getPublicKey();
        }
    }
}
