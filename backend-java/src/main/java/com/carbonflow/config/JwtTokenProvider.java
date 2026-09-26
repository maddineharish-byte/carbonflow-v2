package com.carbonflow.config;

import com.carbonflow.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and validates HS256 access tokens.
 *
 * <p>Configuration is fail-closed: the signing secret must be supplied through
 * the environment ({@code CARBONFLOW_JWT_SECRET}); there is no committed
 * default value. Startup fails fast when the secret is missing or shorter than
 * the 256 bits required for HS256.
 */
@Component
public class JwtTokenProvider {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(@Value("${carbonflow.jwt.secret:}") String secret,
                            @Value("${carbonflow.jwt.expiration-ms:900000}") long expirationMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "carbonflow.jwt.secret is not configured. Export CARBONFLOW_JWT_SECRET "
                            + "(at least 32 random bytes) before starting the application.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "carbonflow.jwt.secret must be at least 256 bits (32 bytes) for HS256; got "
                            + keyBytes.length + " bytes.");
        }
        if (expirationMs <= 0) {
            throw new IllegalStateException("carbonflow.jwt.expiration-ms must be positive.");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("orgId", user.getOrganizationId())
                .claim("role", user.getRole().name())
                .claim("fullName", user.getFullName())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
