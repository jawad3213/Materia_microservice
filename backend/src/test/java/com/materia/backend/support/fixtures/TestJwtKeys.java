package com.materia.backend.support.fixtures;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** RSA key pairs for token tests, encoded the way deployments configure them (PEM, or the Base64 of the PEM). */
public final class TestJwtKeys {

    private TestJwtKeys() {
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String privatePem(KeyPair keys) {
        return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    public static String publicPem(KeyPair keys) {
        return pem("PUBLIC KEY", keys.getPublic().getEncoded());
    }

    /** One line, as in an environment file. */
    public static String base64(String pem) {
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
