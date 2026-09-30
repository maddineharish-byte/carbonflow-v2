package com.carbonflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context-free checks of AuthService's security constants: the refresh HMAC
 * secret fails closed (mirroring the JWT secret), refresh-token hashing is a
 * deterministic keyed hash, and the timing-equalizer hash is a genuinely
 * verifiable BCrypt hash so unknown-account logins pay the full comparison
 * cost.
 */
class AuthServiceTest {

    private static final String VALID_SECRET = "unit-refresh-secret-0123456789abcdef0123456789ab";

    private static AuthService service(String refreshSecret) {
        // Repositories/token provider are irrelevant for these checks: the
        // constructor validates the secret before anything is used.
        return new AuthService(null, null, null, null, null,
                new LoginThrottle(5, 900_000L, 900_000L, java.time.Clock.systemUTC()),
                refreshSecret);
    }

    @Test
    void refusesToStartWithoutARefreshSecret() {
        assertThrows(IllegalStateException.class, () -> service(""));
        assertThrows(IllegalStateException.class, () -> service("   "));
        assertThrows(IllegalStateException.class, () -> service(null));
    }

    @Test
    void refusesToStartWithARefreshSecretShorterThan256Bits() {
        assertThrows(IllegalStateException.class, () -> service("only-16-bytes--"));
    }

    @Test
    void refreshTokenHashIsADeterministicKeyedHash() {
        AuthService auth = service(VALID_SECRET);
        String raw = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String hash = auth.hashRefreshToken(raw);
        assertTrue(hash.matches("[0-9a-f]{64}"), "64 lowercase hex characters");
        assertEquals(hash, auth.hashRefreshToken(raw), "hashing is deterministic");
        assertNotEquals(hash, auth.hashRefreshToken(raw.substring(0, 63) + "0"),
                "different input, different hash");

        // Another key must not reproduce the hash (the stored form is keyed).
        assertNotEquals(hash, service("another-secret-0123456789abcdef0123456789abcdef")
                .hashRefreshToken(raw));
    }

    @Test
    void timingEqualizerHashIsAWellFormedBcryptHash() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        // Never matches, but never throws either — login always pays one full
        // BCrypt verification, keeping unknown accounts timing-indistinguishable.
        assertFalse(encoder.matches("any-password-at-all", AuthService.TIMING_EQUALIZER_HASH));
        assertTrue(AuthService.TIMING_EQUALIZER_HASH.startsWith("$2"));
    }
}
