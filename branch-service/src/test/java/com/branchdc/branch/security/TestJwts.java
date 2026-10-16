package com.branchdc.branch.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

/** Builds gateway-like JWTs for tests, signed with a key pair that stands in for a WSO2 instance. */
final class TestJwts {

    static final String ISSUER = "wso2.org/products/am";

    private TestJwts() {
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JWTClaimsSet.Builder claims(String scope, Instant expiry) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("app-owner")
                .expirationTime(Date.from(expiry))
                .claim("scope", scope)
                .claim("http://wso2.org/claims/applicationname", "listener-a");
    }

    static String rs256(KeyPair key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(key.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "alg":"none": no signature at all. */
    static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    /** HS256 with the RSA public key's bytes as the HMAC secret: the classic algorithm-confusion attack. */
    static String hs256WithPublicKey(KeyPair key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(((RSAPublicKey) key.getPublic()).getEncoded()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
