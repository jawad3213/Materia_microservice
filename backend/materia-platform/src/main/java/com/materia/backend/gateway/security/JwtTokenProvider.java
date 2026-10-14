package com.materia.backend.gateway.security;

import io.jsonwebtoken.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 🔹 JWT TOKEN PROVIDER (API GATEWAY)
 *
 * Handles:
 * - Generating access tokens (RS256) using User UUID strictly as subject and embedding email
 * - Extracting User UUID (subject), email and roles from access tokens
 * - Validating token integrity and expiration statelessly with the public key only, without database access
 * - Publishing the public key as a JWK set, so other services can verify tokens without the private key
 *
 * Refresh tokens are not JWTs: they are opaque random strings, stored hashed by the auth context.
 */
@Component
public class JwtTokenProvider {

    public static final String SERVICE_ROLE = "ROLE_SERVICE";
    private static final String SERVICE_SUBJECT_PREFIX = "service:";
    private static final String SERVICE_CLAIM = "svc";

    private final RSAPrivateKey signingKey;
    private final RSAPublicKey verificationKey;
    private final String keyId;
    private final long accessExpirationMs;

    /**
     * @param privateKey the PKCS#8 private key ({@code -----BEGIN PRIVATE KEY-----}), as PEM or as Base64 of the PEM;
     *                   blank on a service that only verifies tokens
     * @param publicKey  the X.509 public key ({@code -----BEGIN PUBLIC KEY-----}), as PEM or as Base64 of the PEM
     */
    public JwtTokenProvider(
            @Value("${jwt.private-key:}") String privateKey,
            @Value("${jwt.public-key}") String publicKey,
            @Value("${jwt.expiration-ms}") long accessExpirationMs) {

        this.verificationKey = JwtKeys.publicKey(publicKey);
        this.signingKey = StringUtils.hasText(privateKey) ? JwtKeys.privateKey(privateKey) : null;
        if (signingKey != null && !signingKey.getModulus().equals(verificationKey.getModulus())) {
            throw new IllegalStateException("jwt.private-key and jwt.public-key are not the same key pair");
        }
        this.keyId = JwtKeys.thumbprint(verificationKey);
        this.accessExpirationMs = accessExpirationMs;
    }

    // ===================================================================
    // 🔹 ACCESS TOKEN METHODS
    // ===================================================================

    public String generateAccessToken(UUID userUuid, Authentication authentication) {
        Collection<? extends GrantedAuthority> authorities = (authentication != null) ? authentication.getAuthorities() : null;
        return generateToken(userUuid, null, authorities);
    }

    public String generateAccessToken(UUID userUuid, Collection<? extends GrantedAuthority> authorities) {
        return generateToken(userUuid, null, authorities);
    }

    public String generateAccessTokenWithAuthorities(UUID userUuid, Collection<? extends GrantedAuthority> authorities) {
        return generateToken(userUuid, null, authorities);
    }

    public String generateAccessTokenWithAuthorities(UUID userUuid, String email, Collection<? extends GrantedAuthority> authorities) {
        return generateToken(userUuid, email, authorities);
    }

    public String generateAccessToken(UUID userUuid) {
        return generateToken(userUuid, null, null);
    }

    public boolean validateAccessToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID getUserIdFromAccessToken(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    /** The token's subject: a user UUID, or {@code service:<name>} for a service token. */
    public String getSubjectFromAccessToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * A token for work no user started (scheduled jobs, event handlers, start-up), so its calls to other services
     * are still authenticated. Its subject is {@code service:<name>}, not a user, and the HTTP API refuses it
     * ({@link #isServiceToken}): it only travels between services.
     */
    public String generateServiceToken(String service) {
        if (signingKey == null) {
            throw new IllegalStateException("jwt.private-key is not set: this service only verifies tokens");
        }
        return Jwts.builder()
                .header().keyId(keyId).and()
                .id(UUID.randomUUID().toString())
                .subject(SERVICE_SUBJECT_PREFIX + service)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpirationMs))
                .claim(SERVICE_CLAIM, true)
                .claim("roles", SERVICE_ROLE)
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }

    public boolean isServiceToken(String token) {
        return Boolean.TRUE.equals(parseClaims(token).get(SERVICE_CLAIM, Boolean.class));
    }

    /** How long an access token issued now stays valid. */
    public long accessExpirationMs() {
        return accessExpirationMs;
    }

    public String getEmailFromAccessToken(String token) {
        return parseClaims(token).get("email", String.class);
    }

    /**
     * Extracts the user's granted authorities (roles) directly from the JWT access token claims.
     * Used by the Gateway to build the SecurityContext WITHOUT any database call.
     */
    public List<GrantedAuthority> getAuthoritiesFromAccessToken(String token) {
        String roles = parseClaims(token).get("roles", String.class);
        if (!StringUtils.hasText(roles)) {
            return Collections.emptyList();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    /** The public key as a JWK set (RFC 7517), served at {@code /.well-known/jwks.json}. */
    public Map<String, Object> publicJwks() {
        return Map.of("keys", List.of(JwtKeys.jwk(verificationKey, keyId)));
    }

    // ===================================================================
    // 🔹 REFRESH TOKEN METHODS
    // ===================================================================

    /**
     * Generates a cryptographically secure random 256-bit string (32 bytes)
     * formatted as a URL-safe Base64 string without padding.
     */
    public String generateRandomRefreshToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Hashes a raw refresh token using SHA-256 and returns a 64-character lowercase hex string.
     */
    public String hashToken(String rawToken) {
        if (rawToken == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    // ===================================================================
    // 🔹 PRIVATE CORE METHODS
    // ===================================================================

    private String generateToken(UUID userUuid, String email, Collection<? extends GrantedAuthority> authorities) {
        Objects.requireNonNull(userUuid, "User UUID must not be null");
        if (signingKey == null) {
            throw new IllegalStateException("jwt.private-key is not set: this service only verifies tokens");
        }

        JwtBuilder builder = Jwts.builder()
                .header().keyId(keyId).and()
                .id(UUID.randomUUID().toString())
                .subject(userUuid.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpirationMs))
                .signWith(signingKey, Jwts.SIG.RS256);

        if (StringUtils.hasText(email)) {
            builder.claim("email", email);
        }

        if (authorities != null && !authorities.isEmpty()) {
            String roles = authorities.stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(","));
            builder.claim("roles", roles);
        }

        return builder.compact();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(verificationKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
