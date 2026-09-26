package com.carbonflow.config;

import com.carbonflow.model.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private static final String VALID_SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "another-test-secret-9876543210fedcba9876543210fedcba";
    private static final long ONE_HOUR_MS = 3_600_000L;

    /** Identity arguments for the Phase 3 signature (userId/email/fullName/orgId/role). */
    private static String sampleToken(JwtTokenProvider provider, Role role) {
        return provider.generateToken("usr-test-1", "tester@acmeglobal.com",
                "Test User", "org-test-1", role);
    }

    @Test
    void refusesToStartWithoutASecret() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("", ONE_HOUR_MS));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("   ", ONE_HOUR_MS));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(null, ONE_HOUR_MS));
    }

    @Test
    void refusesToStartWithASecretShorterThan256Bits() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("only-16-bytes-long", ONE_HOUR_MS));
    }

    @Test
    void refusesNonPositiveExpiration() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(VALID_SECRET, 0));
    }

    @Test
    void generatesParsableTokenCarryingIdentityClaims() {
        JwtTokenProvider provider = new JwtTokenProvider(VALID_SECRET, ONE_HOUR_MS);
        String token = sampleToken(provider, Role.SUSTAINABILITY_MANAGER);

        Claims claims = provider.parseToken(token);
        assertEquals("usr-test-1", claims.getSubject());
        assertEquals("tester@acmeglobal.com", claims.get("email", String.class));
        assertEquals("org-test-1", claims.get("orgId", String.class));
        assertEquals("SUSTAINABILITY_MANAGER", claims.get("role", String.class));
        assertNotNull(claims.getExpiration());
    }

    @Test
    void rejectsTamperedToken() {
        JwtTokenProvider provider = new JwtTokenProvider(VALID_SECRET, ONE_HOUR_MS);
        String token = sampleToken(provider, Role.COMPANY_ADMIN);

        int midpoint = token.length() / 2;
        char original = token.charAt(midpoint);
        char flipped = original == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, midpoint) + flipped + token.substring(midpoint + 1);

        assertThrows(JwtException.class, () -> provider.parseToken(tampered));
    }

    @Test
    void rejectsTokenSignedWithADifferentKey() {
        String token = sampleToken(new JwtTokenProvider(VALID_SECRET, ONE_HOUR_MS), Role.COMPANY_ADMIN);
        JwtTokenProvider other = new JwtTokenProvider(OTHER_SECRET, ONE_HOUR_MS);

        assertThrows(JwtException.class, () -> other.parseToken(token));
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtTokenProvider provider = new JwtTokenProvider(VALID_SECRET, 1);
        String token = sampleToken(provider, Role.REVIEWER);
        Thread.sleep(20);

        assertThrows(ExpiredJwtException.class, () -> provider.parseToken(token));
    }

    @Test
    void rejectsGarbageInput() {
        JwtTokenProvider provider = new JwtTokenProvider(VALID_SECRET, ONE_HOUR_MS);
        assertThrows(JwtException.class, () -> provider.parseToken("not-a-jwt"));
        assertTrue(sampleToken(provider, Role.DATA_OWNER).split("\\.").length == 3);
    }
}
