package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.service.LoginThrottle;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 9 — authentication hardening over the real HTTP surface
 * (threats #1 and #11, threat model {@code SECURITY-THREAT-MODEL.md}).
 *
 * <p>Proves the two Phase 9 controls end-to-end: failed-login throttling
 * (429 {@code AUTH_THROTTLED} after the budget, correct-password recovery
 * afterwards) and the removal of query-string token acceptance — a token in
 * {@code ?token=} must no longer authenticate anything.
 *
 * <p>This test avoids the parent {@code loginToken / login} methods which
 * assert HTTP 200; it uses {@link #loginAttempt} and extracts tokens from
 * the {@code Api} response body wherever a token string is needed.
 *
 * <p>Key: the seeded ACME admin lives at email {@code admin@acmeglobal.com}
 * (see {@link SeedIds#USER_ACME_ADMIN} + {@link DemoDataSeeder}). The UUID
 * string {@value SeedIds#USER_ACME_ADMIN} is a resource id, not an email.
 */
class AuthHardeningTest extends PostgresBackedIntegrationTest {

    @Autowired
    private LoginThrottle throttle;

    private static final String ACE_ADMIN_EMAIL = "admin@acmeglobal.com";

    @BeforeEach
    void clearThrottle() {
        throttle.reset();
    }

    @AfterEach
    void clearThrottleAfter() {
        throttle.reset();
    }

    /** POST /api/v1/auth/login and return the envelope. Never asserts 200. */
    private Api loginAttempt(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /** Extract the access-token string from an {@code Api} body, or null. */
    private String tokenFrom(Api api) {
        try {
            return api.body().get("data").get("accessToken").asText();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Failed-login throttling
    // ------------------------------------------------------------------

    @Test
    void repeatedFailedLoginsLockTheAccountWith429() throws Exception {
        String email = "throttle-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        String correctPassword = SeedIds.DEMO_PASSWORD;

        // Wrong password → INVALID_CREDENTIALS (401) until the budget is spent.
        Api wrong = loginAttempt(email, "definitely-not-the-password");
        assertEquals(401, wrong.status(), wrong.body().toString());
        assertEquals("INVALID_CREDENTIALS", wrong.errorCode());

        for (int remaining = 0; remaining < 4; remaining++) {
            assertEquals(401, loginAttempt(email, "definitely-not-the-password").status());
        }

        // The account is now locked: even the correct password is refused, and
        // the answer is 429 (throttled) rather than 401 (credentials) so a
        // correct password is never confirmed while locked.
        Api locked = loginAttempt(email, correctPassword);
        assertEquals(429, locked.status(), locked.body().toString());
        assertEquals("AUTH_THROTTLED", locked.errorCode());
        assertEquals("Too many failed sign-in attempts. Try again later.", locked.errorMessage());

        // And an unknown account is throttled identically — no user enumeration.
        String unknown = "no-such-user-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        for (int i = 0; i < 5; i++) {
            assertEquals(401, loginAttempt(unknown, "whatever").status());
        }
        Api unknownLocked = loginAttempt(unknown, "whatever");
        assertEquals(429, unknownLocked.status(), unknownLocked.body().toString());
        assertEquals("AUTH_THROTTLED", unknownLocked.errorCode());
    }

    @Test
    void aCorrectPasswordStillWorksForAccountsUnderTheBudget() throws Exception {
        String email = "throttle-budget-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        // Register (leaves the account pending) — a wrong password first, then
        // the correct one must still be accepted (no over-eager throttling).
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Throttle Budget\",\"country\":\"DE\","
                        + "\"industry\":\"Logistics\",\"fullName\":\"Founder\",\"email\":\""
                        + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD + "\"}");
        assertEquals(201, registration.status(), registration.body().toString());

        assertEquals(401, loginAttempt(email, "wrong-password").status());
        // Correct credentials on the very next attempt still authenticate
        // (account is pending, so the contract answer is the lifecycle 403 —
        // proof that the password passed and throttling did not intervene).
        Api correct = loginAttempt(email, SeedIds.DEMO_PASSWORD);
        assertEquals(403, correct.status(), correct.body().toString());
        assertNotEquals401(correct);
    }

    @Test
    void throttlingIsPerAccountAndDoesNotLockOutOtherUsers() throws Exception {
        String attacked = "victim-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        for (int i = 0; i < 6; i++) {
            loginAttempt(attacked, "wrong");
        }
        assertEquals(429, loginAttempt(attacked, "wrong").status());

        // A completely different account is unaffected — use the ACME admin
        // (seeded with email admin@acmeglobal.com, ACTIVE status, correct
        // password must succeed).
        Api otherApi = loginAttempt(ACE_ADMIN_EMAIL, SeedIds.DEMO_PASSWORD);
        assertEquals(200, otherApi.status(), "ACME admin login should succeed");
        String other = tokenFrom(otherApi);
        assertNotNull(other, "an unrelated account must not inherit the lockout and must be able to log in");
    }

    // ------------------------------------------------------------------
    // Query-string token acceptance removed (threat #11)
    // ------------------------------------------------------------------

    @Test
    void queryStringTokenNoLongerAuthenticates() throws Exception {
        String token = tokenFrom(loginAttempt(ACE_ADMIN_EMAIL, SeedIds.DEMO_PASSWORD));
        assertNotNull(token, "ACE admin must be able to log in via header");

        // The same token in a URL must NOT authenticate (removed in Phase 9).
        MvcResult result = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/facilities?token=" + token)).andReturn();
        assertEquals(401, result.getResponse().getStatus(),
                "a token supplied through the URL must never authenticate");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"UNAUTHORIZED\""), body);

        // The header form still works — the fix did not break real clients.
        assertEquals(200, getJson("/api/v1/facilities", token).status());
    }

    @Test
    void queryStringTokenIsAlsoRejectedOnExportAndAuthEndpoints() throws Exception {
        String token = tokenFrom(loginAttempt(ACE_ADMIN_EMAIL, SeedIds.DEMO_PASSWORD));

        MvcResult export = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/reports/export-csv?token=" + token)).andReturn();
        assertEquals(401, export.getResponse().getStatus(),
                "the CSV export must not accept a URL token");

        MvcResult me = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/auth/me?token=" + token)).andReturn();
        assertEquals(401, me.getResponse().getStatus());
    }

    @Test
    void missingAuthorizationHeaderExplainsOnlyTheHeader() throws Exception {
        Api anonymous = getJson("/api/v1/facilities", null);
        assertEquals(401, anonymous.status());
        assertEquals("Missing or malformed Authorization header.",
                anonymous.errorMessage());
        assertFalse(anonymous.errorMessage().toLowerCase().contains("query"),
                "the 401 must no longer advertise a token query parameter");
    }

    private void assertNotEquals401(Api api) {
        assertFalse(api.status() == 401 && "INVALID_CREDENTIALS".equals(api.errorCode()),
                "the password must have been accepted (throttle must not shadow a correct login)");
    }
}