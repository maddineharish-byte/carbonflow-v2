package com.carbonflow.controller;

import com.carbonflow.model.enums.Role;
import com.carbonflow.repository.IdentityRepository;
import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refresh-token lifecycle against PostgreSQL — the Java port of
 * {@code rotateRefreshToken} from {@code server/postgres-refresh-repository.ts}
 * plus the deliberate ADR-014 hardening: replay of a rotated token revokes the
 * whole family (Node leaves the replacement usable).
 *
 * <p>Every rejection that carries a revocation must leave that revocation
 * <em>persisted</em> even though the HTTP response is an error — this is
 * exactly what {@code @Transactional(noRollbackFor = AuthException.class)} in
 * AuthService guarantees, and each such test asserts the row in the database.
 */
class RefreshRotationTest extends PostgresBackedIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------
    // Rotation
    // ------------------------------------------------------------------

    @Test
    void rotationIssuesANewPairAndMarksTheOldRowRotated() throws Exception {
        JsonNode session = login("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String oldRefresh = session.get("data").get("refreshToken").asText();

        Api rotated = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + oldRefresh + "\"}");
        assertEquals(200, rotated.status());
        String newRefresh = rotated.body().path("data").path("refreshToken").asText();
        String newAccess = rotated.body().path("data").path("accessToken").asText();
        assertNotEquals(oldRefresh, newRefresh, "refresh token must rotate");
        assertFalse(newAccess.isBlank(), "an access token must be re-issued");
        // The re-issued access token carries a fresh 15-minute expiry, but with
        // identical claims a same-second re-issue can be byte-identical — HS256
        // is deterministic and there is no jti/nonce, exactly like Node.

        Map<String, Object> oldRow = tokenRow(oldRefresh);
        assertNotNull(oldRow.get("revoked_at"), "rotated token must be revoked");
        assertNotNull(oldRow.get("replaced_by_token_id"), "rotated token must link its replacement");
        assertNull(tokenRow(newRefresh).get("revoked_at"), "replacement starts active");
        assertEquals(oldRow.get("family_id"), tokenRow(newRefresh).get("family_id"),
                "replacement stays in the same family");
    }

    @Test
    void replayOfARotatedTokenRevokesTheEntireFamily() throws Exception {
        // ADR-014 deviation from Node: Node answers REFRESH_TOKEN_REVOKED for the
        // replayed copy but leaves the legitimate replacement usable; Java kills
        // the whole family so a thief and the victim both lose the session.
        JsonNode session = login("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String t1 = session.get("data").get("refreshToken").asText();

        Api first = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + t1 + "\"}");
        assertEquals(200, first.status());
        String t2 = first.body().path("data").path("refreshToken").asText();

        Api replay = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + t1 + "\"}");
        assertEquals(401, replay.status());
        assertEquals("REFRESH_TOKEN_REVOKED", replay.errorCode());
        assertEquals("The refresh token has already been revoked.", replay.errorMessage());

        // The legitimate replacement is dead as well (family revocation) …
        assertNotNull(tokenRow(t2).get("revoked_at"), "family revocation must reach the replacement");
        Api after = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + t2 + "\"}");
        assertEquals(401, after.status());
        assertEquals("REFRESH_TOKEN_REVOKED", after.errorCode());
    }

    @Test
    void logoutRevokesTheSessionAndFurtherRefreshFails() throws Exception {
        JsonNode session = login("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String refreshToken = session.get("data").get("refreshToken").asText();

        Api logout = postJson("/api/v1/auth/logout", null, "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(200, logout.status());
        assertEquals("Refresh session revoked successfully.", logout.body().path("message").asText());
        assertTrue(logout.body().path("data").path("revoked").asBoolean());
        assertNotNull(tokenRow(refreshToken).get("revoked_at"));

        Api after = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(401, after.status());
        assertEquals("REFRESH_TOKEN_REVOKED", after.errorCode());
    }

    @Test
    void rotatedAccessTokensRemainValidUntilExpiry() throws Exception {
        // Stateless 15-minute access tokens cannot be revoked early — identical
        // to the Node backend (documented in ADR-014).
        JsonNode session = login("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String oldAccess = session.get("data").get("accessToken").asText();

        Api rotated = postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"" + session.get("data").get("refreshToken").asText() + "\"}");
        assertEquals(200, rotated.status());
        String newAccess = rotated.body().path("data").path("accessToken").asText();

        assertEquals(200, getJson("/api/v1/auth/me", newAccess).status());
        assertEquals(200, getJson("/api/v1/auth/me", oldAccess).status(),
                "pre-rotation access token survives until its own expiry (Node parity)");
    }

    // ------------------------------------------------------------------
    // Request-shape validation (Node contract)
    // ------------------------------------------------------------------

    @Test
    void refreshRejectsContextOverrideFields() throws Exception {
        String token = loginRefreshToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api api = postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"" + token + "\",\"userId\":\"someone-else\"}");
        assertEquals(400, api.status());
        assertEquals("VALIDATION_ERROR", api.errorCode());
        assertEquals("Refresh requests cannot change the authorization context.", api.errorMessage());
    }

    @Test
    void refreshRequiresAWellFormedToken() throws Exception {
        Api missing = postJson("/api/v1/auth/refresh", null, "{}");
        assertEquals(400, missing.status());
        assertEquals("REFRESH_TOKEN_REQUIRED", missing.errorCode());
        assertEquals("A valid refresh token is required.", missing.errorMessage());

        Api malformed = postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"not-a-hex-token\"}");
        assertEquals(400, malformed.status());
        assertEquals("REFRESH_TOKEN_REQUIRED", malformed.errorCode());

        Api brokenJson = postJson("/api/v1/auth/refresh", null, "this is { not json");
        assertEquals(400, brokenJson.status());
        assertEquals("INVALID_JSON", brokenJson.errorCode());
    }

    // ------------------------------------------------------------------
    // Rejections persist their revocation (noRollbackFor proof)
    // ------------------------------------------------------------------

    @Test
    void expiredRefreshTokenIsRejectedAndItsRevocationPersists() throws Exception {
        String email = createMember("REVIEWER");
        String refreshToken = loginRefreshToken(email, SeedIds.DEMO_PASSWORD);
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 hour' "
                + "WHERE token_hash = ?", hashRefreshToken(refreshToken));

        Api api = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(401, api.status());
        assertEquals("REFRESH_TOKEN_EXPIRED", api.errorCode());
        assertEquals("The refresh token has expired.", api.errorMessage());
        assertNotNull(tokenRow(refreshToken).get("revoked_at"),
                "expiry rejection must persist the revocation");
    }

    @Test
    void deactivatedUserRefreshIsRejectedAndRevoked() throws Exception {
        String email = createMember("REVIEWER");
        String refreshToken = loginRefreshToken(email, SeedIds.DEMO_PASSWORD);
        String userId = String.valueOf(
                jdbc.queryForObject("SELECT id::text FROM users WHERE lower(email) = lower(?)",
                        String.class, email));
        identityRepository.setUserActive(userId, false);

        Api api = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(401, api.status());
        assertEquals("USER_DEACTIVATED", api.errorCode());
        assertNotNull(tokenRow(refreshToken).get("revoked_at"));
    }

    @Test
    void removedMembershipRefreshIsRejectedAndRevoked() throws Exception {
        String email = createMember("REVIEWER");
        String refreshToken = loginRefreshToken(email, SeedIds.DEMO_PASSWORD);
        jdbc.update("DELETE FROM organization_memberships WHERE user_id = "
                        + "(SELECT id FROM users WHERE lower(email) = lower(?))",
                email);

        Api api = postJson("/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(401, api.status());
        assertEquals("REFRESH_MEMBERSHIP_INVALID", api.errorCode());
        assertEquals("The associated organization membership is no longer active.", api.errorMessage());
        assertNotNull(tokenRow(refreshToken).get("revoked_at"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Creates an idempotent-unique member in the Acme tenant and returns the email. */
    private String createMember(String role) {
        String userId = UUID.randomUUID().toString();
        String email = "refresh-" + userId.substring(0, 8) + "@example.test";
        identityRepository.insertUser(userId, email, passwordEncoder.encode(SeedIds.DEMO_PASSWORD),
                "Refresh Probe");
        identityRepository.insertMembership(UUID.randomUUID().toString(),
                SeedIds.ORG_ACME, userId, Role.valueOf(role));
        return email;
    }

    private Map<String, Object> tokenRow(String rawRefreshToken) {
        return jdbc.queryForMap(
                "SELECT family_id, revoked_at, replaced_by_token_id FROM refresh_tokens "
                        + "WHERE token_hash = ?",
                hashRefreshToken(rawRefreshToken));
    }
}
