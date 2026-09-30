package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.service.UuidContract;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
    // Tenant detail: GET /platform/tenants/{id}
    // Phase 10.4.1 finding 2 (demo seed ids were not RFC-4122 conformant and
    // therefore answered 404 for tenants that plainly existed).
    // ------------------------------------------------------------------

    @Test
    void everyDemoSeedIdSatisfiesTheUuidContract() {
        // The root cause, asserted directly: the detail endpoint gates on
        // UuidContract.isNodeUuid, and the old seed fixture failed it.
        assertTrue(UuidContract.isNodeUuid(SeedIds.ORG_ACME), SeedIds.ORG_ACME);
        assertTrue(UuidContract.isNodeUuid(SeedIds.ORG_APEX), SeedIds.ORG_APEX);
        assertTrue(UuidContract.isNodeUuid(SeedIds.ORG_PLATFORM), SeedIds.ORG_PLATFORM);
        assertTrue(UuidContract.isNodeUuid(SeedIds.USER_ACME_ADMIN), SeedIds.USER_ACME_ADMIN);
        assertTrue(UuidContract.isNodeUuid(SeedIds.USER_PLATFORM_ADMIN), SeedIds.USER_PLATFORM_ADMIN);
    }

    @Test
    void platformAdminCanReadEveryDemoTenantById() throws Exception {
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        for (String organizationId : List.of(SeedIds.ORG_ACME, SeedIds.ORG_APEX, SeedIds.ORG_PLATFORM)) {
            Api detail = getJson("/api/v1/platform/tenants/" + organizationId, platformToken);
            assertEquals(200, detail.status(), organizationId + " -> " + detail.body());
            assertEquals(organizationId, detail.body().path("data").path("id").asText());
            assertEquals("ACTIVE", detail.body().path("data").path("status").asText());
        }
    }

    @Test
    void platformAdminCanReadATenantRegisteredOutsideItsOwnContext() throws Exception {
        // Cross-tenant by construction: the org belongs to a signup that has
        // nothing to do with the platform admin's tenant.
        String organizationId = organizationId(register(uniqueEmail()));
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        Api detail = getJson("/api/v1/platform/tenants/" + organizationId, platformToken);
        assertEquals(200, detail.status());
        assertEquals("PENDING_ACTIVATION", detail.body().path("data").path("status").asText());
    }

    @Test
    void tenantDetailStillCollapsesMalformedAndUnknownIdsIntoTheSame404() throws Exception {
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        List<String> indistinguishable = List.of(
                "not-a-uuid",
                "22222222-2222-2222-2222-222222222201",   // old, non-RFC-4122 fixture shape
                "00000000-0000-0000-0000-000000000000",   // version 0
                "22222222-2222-4222-c222-222222222201",   // invalid variant nibble
                UUID.randomUUID().toString());            // well-formed but unknown

        for (String organizationId : indistinguishable) {
            Api detail = getJson("/api/v1/platform/tenants/" + organizationId, platformToken);
            assertEquals(404, detail.status(), organizationId + " -> " + detail.body());
            assertEquals("ORGANIZATION_NOT_FOUND", detail.errorCode());
            assertEquals("Organization does not exist or access denied.", detail.errorMessage());
        }
    }

    @Test
    void companyAdminCannotReadTenantDetails() throws Exception {
        String adminToken = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api detail = getJson("/api/v1/platform/tenants/" + SeedIds.ORG_ACME, adminToken);
        assertEquals(403, detail.status());
        assertEquals("FORBIDDEN", detail.errorCode());

        Api unknown = getJson("/api/v1/platform/tenants/" + UUID.randomUUID(), adminToken);
        assertEquals(403, unknown.status(), "a denied caller must not learn whether an id exists");
    }

    @Test
    void anonymousTenantDetailIsUnauthorized() throws Exception {
        Api detail = getJson("/api/v1/platform/tenants/" + SeedIds.ORG_ACME, null);
        assertEquals(401, detail.status());
        assertEquals("UNAUTHORIZED", detail.errorCode());
    }

    // ------------------------------------------------------------------
    // Phase 10.4.1 finding 3: an unsupported body media type must be 415.
    // ------------------------------------------------------------------

    @Test
    void formEncodedBodyOnAJsonEndpointIs415Not500() throws Exception {
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);

        MvcResult result = mockMvc.perform(
                        post("/api/v1/platform/tenants/" + SeedIds.ORG_APEX + "/suspend")
                                .header("Authorization", "Bearer " + platformToken)
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .content("note=quarterly+review"))
                .andReturn();

        assertEquals(415, result.getResponse().getStatus());
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(body.path("success").asBoolean(true));
        assertEquals("UNSUPPORTED_MEDIA_TYPE", body.path("error").path("code").asText());

        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains("org.springframework"), raw);
        assertFalse(raw.contains("com.carbonflow"), raw);

        // The rejected request changed nothing.
        assertEquals("ACTIVE", getJson("/api/v1/platform/tenants/" + SeedIds.ORG_APEX,
                platformToken).body().path("data").path("status").asText());
    }

    @Test
    void jsonBodyOnTheSameEndpointStillWorks() throws Exception {
        String platformToken = loginToken("platform.admin@carbonflow.test", SeedIds.DEMO_PASSWORD);
        String organizationId = organizationId(register(uniqueEmail()));
        postJson("/api/v1/platform/tenants/" + organizationId + "/approve", platformToken, null);

        Api suspended = postJson("/api/v1/platform/tenants/" + organizationId + "/suspend",
                platformToken, "{\"note\":\"415 regression control\"}");
        assertEquals(200, suspended.status());
        assertEquals("SUSPENDED", suspended.body().path("data").path("status").asText());
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
