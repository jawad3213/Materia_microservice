package com.materia.backend.gateway.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the RSA key pair that signs access tokens, and describes its public half as a JWK.
 *
 * <p>Keys are configured as PEM, or as the Base64 of the PEM: a single line, so it fits an environment file or an
 * SSM parameter.
 */
final class JwtKeys {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private JwtKeys() {
    }

    static RSAPrivateKey privateKey(String configured) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der(configured, "PRIVATE KEY", "jwt.private-key")));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("jwt.private-key is not a PKCS#8 RSA private key", e);
        }
    }

    static RSAPublicKey publicKey(String configured) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der(configured, "PUBLIC KEY", "jwt.public-key")));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("jwt.public-key is not an X.509 RSA public key", e);
        }
    }

    /** The key's RFC 7638 thumbprint, used as its key id ({@code kid}). */
    static String thumbprint(RSAPublicKey key) {
        String canonical = "{\"e\":\"" + base64Url(key.getPublicExponent()) + "\",\"kty\":\"RSA\",\"n\":\""
                + base64Url(key.getModulus()) + "\"}";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return BASE64_URL.encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    static Map<String, Object> jwk(RSAPublicKey key, String keyId) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", keyId);
        jwk.put("n", base64Url(key.getModulus()));
        jwk.put("e", base64Url(key.getPublicExponent()));
        return jwk;
    }

    private static byte[] der(String configured, String type, String property) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(property + " is not set");
        }
        String pem = configured.trim();
        if (!pem.startsWith("-----BEGIN")) {
            try {
                pem = new String(Base64.getMimeDecoder().decode(pem), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(property + " is neither PEM nor Base64-encoded PEM", e);
            }
        }
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            throw new IllegalStateException(property + " must be a PEM block of type " + type);
        }
        return Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
    }

    /** Unsigned big-endian bytes, as JWK requires (no leading sign byte). */
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return BASE64_URL.encodeToString(bytes);
    }
}
