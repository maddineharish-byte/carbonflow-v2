package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registration → approval → activation lifecycle (greenfield, ADR-014):
 * {@code POST /auth/register} creates a PENDING_ACTIVATION organization without
 * issuing tokens; only a platform administrator can approve / reject / suspend
 * tenants ({@code platform.tenants.*}); and a non-ACTIVE organization can
 * neither log in nor refresh.
 */
class PlatformTenantTest extends PostgresBackedIntegrationTest {

    private static String uniqueEmail() {
        return "signup-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private JsonNode register(String email) throws Exception {
        String orgName = "Registered Tenant " + UUID.randomUUID().toString().substring(0, 8);
        Api api = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"" + orgName + "\",\"country\":\"DE\","
                        + "\"industry\":\"Logistics\","
                        + "\"fullName\":\"Founder Person\",\"email\":\"" + email + "\","
                        + "\"password\":\"" + SeedIds.DEMO_PASSWORD + "\"}");
        if (api.status() != 201) {
            throw new AssertionError("registration failed: " + api.status() + " " + api.body());
        }
        return api.body();
    }

    private String organizationId(JsonNode registered) {
        return registered.get("data").get("organization").get("id").asText();
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    @Test
    void registrationCreatesAPendingTenantWithoutTokens() throws Exception {
        String email = uniqueEmail();
        JsonNode registered = register(email);
        JsonNode data = registered.get("data");

        assertEquals("PENDING_ACTIVATION",
                data.get("organization").get("status").asText());
        assertEquals(email, data.get("user").get("email").asText());
        assertFalse(data.has("accessToken"), "registration must not issue tokens");
        assertFalse(data.has("refreshToken"), "registration must not issue tokens");
    }

    @Test
    void pendingTenantCannotSignIn() throws Exception {
        String email = uniqueEmail();
        register(email);

        Api login = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD + "\"}");
        assertEquals(403, login.status());
        assertEquals("ORGANIZATION_NOT_ACTIVE", login.errorCode());
        assertEquals("The organization is pending platform approval.", login.errorMessage());
    }

    @Test
    void duplicateRegistrationIsRejected() throws Exception {
        String email = uniqueEmail();
        register(email);

        Api again = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Duplicate Tenant\",\"fullName\":\"Someone Else\","
                        + "\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD + "\"}");
        assertEquals(409, again.status());
        assertEquals("EMAIL_ALREADY_REGISTERED", again.errorCode());
    }

    // ------------------------------------------------------------------
    // Platform administration
    // ------------------------------------------------------------------

    @Test
    void platformAdminApprovesAndTheTenantCanSignIn() throws Exception {
        String email = uniqueEmail();
        String organizationId = organizationId(register(email));
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        // Listed among the pending tenants.
        JsonNode pending = getJson("/api/v1/platform/tenants?status=PENDING_ACTIVATION",
                platformToken).body().get("data");
        assertTrue(containsOrganization(pending, organizationId),
                "registered tenant must appear as PENDING_ACTIVATION");

        // Approve → ACTIVE with the contract message, then sign-in works.
        Api approved = postJson("/api/v1/platform/tenants/" + organizationId + "/approve",
                platformToken, null);
        assertEquals(200, approved.status());
        assertEquals("Organization approved.", approved.body().path("message").asText());
        assertEquals("ACTIVE", approved.body().path("data").path("status").asText());

        JsonNode session = login(email, SeedIds.DEMO_PASSWORD);
        assertEquals("COMPANY_ADMIN", session.get("data").get("role").asText());
        assertEquals(organizationId, session.get("data").get("organization").get("id").asText());
    }

    @Test
    void tenantAdministratorsCannotUsePlatformEndpoints() throws Exception {
        String adminToken = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        assertEquals(403, getJson("/api/v1/platform/tenants", adminToken).status());
        Api approve = postJson("/api/v1/platform/tenants/" + SeedIds.ORG_APEX + "/approve",
                adminToken, null);
        assertEquals(403, approve.status());
        assertEquals("FORBIDDEN", approve.errorCode());
    }

    @Test
    void platformEndpointsValidateInput() throws Exception {
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        Api badStatus = getJson("/api/v1/platform/tenants?status=FROZEN", platformToken);
        assertEquals(400, badStatus.status());
        assertEquals("VALIDATION_ERROR", badStatus.errorCode());

        Api unknownOrg = postJson("/api/v1/platform/tenants/"
                + UUID.randomUUID() + "/approve", platformToken, null);
        assertEquals(404, unknownOrg.status());
        assertEquals("ORGANIZATION_NOT_FOUND", unknownOrg.errorCode());
    }

    @Test
    void platformAdminSessionHasThePlatformOrgAndNoSwitchOptions() throws Exception {
        JsonNode session = login("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);
        JsonNode data = session.get("data");

        assertEquals(SeedIds.ORG_PLATFORM, data.get("organization").get("id").asText());
        assertEquals("PLATFORM_ADMIN", data.get("role").asText());
        assertTrue(data.get("memberships").isEmpty(),
                "PLATFORM_ADMIN memberships are excluded from the switch options (ADR-014)");
        assertTrue(data.get("permissions").toString().contains("platform.tenants.manage"));
    }

    // ------------------------------------------------------------------
    // Suspension / rejection block authentication
    // ------------------------------------------------------------------

    @Test
    void suspensionBlocksBothNewLoginsAndRefreshes() throws Exception {
        String email = uniqueEmail();
        String organizationId = organizationId(register(email));
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        assertEquals(200, postJson("/api/v1/platform/tenants/" + organizationId + "/approve",
                platformToken, null).status());
        JsonNode session = login(email, SeedIds.DEMO_PASSWORD);
        String refreshToken = session.get("data").get("refreshToken").asText();

        Api suspended = postJson("/api/v1/platform/tenants/" + organizationId + "/suspend",
                platformToken, "{\"note\":\"quarterly review\"}");
        assertEquals(200, suspended.status());
        assertEquals("Organization suspended.", suspended.body().path("message").asText());
        assertEquals("SUSPENDED", suspended.body().path("data").path("status").asText());

        Api login = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD + "\"}");
        assertEquals(403, login.status());
        assertEquals("ORGANIZATION_NOT_ACTIVE", login.errorCode());
        assertEquals("The organization is suspended.", login.errorMessage());

        Api refresh = postJson("/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"" + refreshToken + "\"}");
        assertEquals(401, refresh.status());
        assertEquals("REFRESH_MEMBERSHIP_INVALID", refresh.errorCode());
        assertNotNull(revokedAtOf(refreshToken), "suspension must persist token revocation");
    }

    @Test
    void rejectionBlocksSignInWithTheContractMessage() throws Exception {
        String email = uniqueEmail();
        String organizationId = organizationId(register(email));
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        Api rejected = postJson("/api/v1/platform/tenants/" + organizationId + "/reject",
                platformToken, "{\"note\":\"missing assurance credentials\"}");
        assertEquals(200, rejected.status());
        assertEquals("Organization rejected.", rejected.body().path("message").asText());
        assertEquals("REJECTED", rejected.body().path("data").path("status").asText());

        Api login = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + SeedIds.DEMO_PASSWORD + "\"}");
        assertEquals(403, login.status());
        assertEquals("ORGANIZATION_NOT_ACTIVE", login.errorCode());
        assertEquals("The organization registration was not approved.", login.errorMessage());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean containsOrganization(JsonNode list, String organizationId) {
        for (JsonNode org : list) {
            if (organizationId.equals(org.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private Object revokedAtOf(String rawRefreshToken) {
        return jdbc.queryForObject(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                Object.class, hashRefreshToken(rawRefreshToken));
    }

    @Autowired
    private JdbcTemplate jdbc;
}
