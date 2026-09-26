package com.carbonflow.controller;

import com.carbonflow.config.JwtTokenProvider;
import com.carbonflow.model.enums.Role;
import com.carbonflow.repository.IdentityRepository;
import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login / {@code /auth/me} verification against PostgreSQL (Phase 3): issued
 * claims match the database identity, unknown accounts and wrong passwords are
 * indistinguishable (timing equalizer + identical envelope), deactivated users
 * and unassigned organizations are refused with the Node reference backend's
 * codes, and seeded credentials exist only as BCrypt hashes.
 */
class AuthControllerTest extends PostgresBackedIntegrationTest {

    private static final String DEMO_PASSWORD = SeedIds.DEMO_PASSWORD;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void issuedClaimsReflectTheDatabaseIdentity() throws Exception {
        JsonNode session = login("admin@acmeglobal.com", DEMO_PASSWORD);
        String accessToken = session.get("data").get("accessToken").asText();

        Claims claims = tokenProvider.parseToken(accessToken);
        assertEquals(SeedIds.USER_ACME_ADMIN, claims.getSubject());
        assertEquals("admin@acmeglobal.com", claims.get("email", String.class));
        assertEquals(SeedIds.ORG_ACME, claims.get("orgId", String.class));
        assertEquals("COMPANY_ADMIN", claims.get("role", String.class));
        assertEquals("Elena Rostova", claims.get("fullName", String.class));
    }

    @Test
    void unknownAccountAndWrongPasswordAreIndistinguishable() throws Exception {
        Api unknown = postJson("/api/v1/auth/login", null,
                "{\"email\":\"ghost@example.test\",\"password\":\"whatever-123\"}");
        Api wrongPassword = postJson("/api/v1/auth/login", null,
                "{\"email\":\"admin@acmeglobal.com\",\"password\":\"definitely-wrong\"}");

        assertEquals(401, unknown.status());
        assertEquals(401, wrongPassword.status());
        assertEquals("INVALID_CREDENTIALS", unknown.errorCode());
        assertEquals("INVALID_CREDENTIALS", wrongPassword.errorCode());
        assertEquals(unknown.errorMessage(), wrongPassword.errorMessage());
        assertEquals("Invalid email or password.", unknown.errorMessage());
    }

    @Test
    void missingCredentialsAnswerValidationEnvelope400() throws Exception {
        // Documented deviation: Node answers 401 INVALID_CREDENTIALS for blank
        // fields; Java's bean validation fires first (400 VALIDATION_ERROR).
        Api api = postJson("/api/v1/auth/login", null, "{\"password\":\"Password123!\"}");
        assertEquals(400, api.status());
        assertEquals("VALIDATION_ERROR", api.errorCode());
    }

    @Test
    void deactivatedUserIsRejectedWithUserDeactivated() throws Exception {
        String email = "disabled-" + UUID.randomUUID() + "@example.test";
        String userId = UUID.randomUUID().toString();
        identityRepository.insertUser(userId, email, passwordEncoder.encode(DEMO_PASSWORD), "Disabled Probe");
        identityRepository.insertMembership(UUID.randomUUID().toString(),
                SeedIds.ORG_ACME, userId, Role.REVIEWER);
        identityRepository.setUserActive(userId, false);

        Api api = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + DEMO_PASSWORD + "\"}");
        assertEquals(401, api.status());
        assertEquals("USER_DEACTIVATED", api.errorCode());
        assertEquals("The user account is inactive or deleted.", api.errorMessage());
    }

    @Test
    void unassignedOrganizationSelectorIsForbidden() throws Exception {
        Api api = postJson("/api/v1/auth/login", null,
                "{\"email\":\"admin@acmeglobal.com\",\"password\":\"" + DEMO_PASSWORD
                        + "\",\"organizationId\":\"" + SeedIds.ORG_APEX + "\"}");
        assertEquals(403, api.status());
        assertEquals("NO_ORGANIZATION_ACCESS", api.errorCode());
        assertEquals("The requested organization is not assigned to this user.", api.errorMessage());
    }

    @Test
    void organizationSelectorMatchingTheMembershipSucceeds() throws Exception {
        Api api = postJson("/api/v1/auth/login", null,
                "{\"email\":\"admin@acmeglobal.com\",\"password\":\"" + DEMO_PASSWORD
                        + "\",\"organizationId\":\"" + SeedIds.ORG_ACME + "\"}");
        assertEquals(200, api.status());
        assertEquals(SeedIds.ORG_ACME, api.body().path("data").path("organization").path("id").asText());
        assertNotEquals("", api.body().path("data").path("accessToken").asText());
    }

    @Test
    void meReturnsFreshSessionContextWithoutTokens() throws Exception {
        String token = loginToken("admin@acmeglobal.com", DEMO_PASSWORD);

        Api api = getJson("/api/v1/auth/me", token);
        assertEquals(200, api.status());
        JsonNode data = api.body().get("data");
        assertEquals("admin@acmeglobal.com", data.get("user").get("email").asText());
        assertEquals(SeedIds.USER_ACME_ADMIN, data.get("user").get("id").asText());
        assertEquals("COMPANY_ADMIN", data.get("role").asText());
        assertEquals(SeedIds.ORG_ACME, data.get("organization").get("id").asText());
        assertTrue(data.get("permissions").isArray());
        assertFalse(data.get("permissions").isEmpty());
        assertTrue(data.has("memberships"));
        assertFalse(data.has("accessToken"), "/auth/me must not leak tokens");
        assertFalse(data.has("refreshToken"), "/auth/me must not leak tokens");
    }

    @Test
    void seedPasswordsAreStoredOnlyAsBcryptHashes() {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT email, password_hash FROM users");
        assertTrue(rows.size() >= 5, "expected at least the five seeded users");

        for (Map<String, Object> row : rows) {
            String hash = String.valueOf(row.get("password_hash"));
            assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"),
                    "non-BCrypt hash for " + row.get("email"));
            assertFalse(hash.contains(DEMO_PASSWORD),
                    "plaintext demo password stored for " + row.get("email"));
        }
    }
}
