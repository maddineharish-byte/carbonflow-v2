package com.carbonflow.config;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security-chain verification against PostgreSQL (Phase 3):
 * public/protected split, envelope-shaped 401/403 with the Node reference
 * backend's exact codes and messages, login through the real filter chain
 * (token issuance + per-request database re-validation), permission-based
 * method security, and tenant isolation of a read endpoint.
 */
class SecurityChainIntegrationTest extends PostgresBackedIntegrationTest {

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void apiRequiresAuthenticationAndAnswersWithEnvelope401() throws Exception {
        mockMvc.perform(get("/api/v1/facilities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message")
                        .value("Missing or malformed Authorization header or token query parameter."));
    }

    @Test
    void loginWithValidCredentialsReturnsTokenAndCanonicalRole() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acmeglobal.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data.user.id").value(SeedIds.USER_ACME_ADMIN))
                .andExpect(jsonPath("$.data.user.email").value("admin@acmeglobal.com"))
                .andExpect(jsonPath("$.data.user.fullName").value("Elena Rostova"))
                .andExpect(jsonPath("$.data.user.organizationId").doesNotExist())
                .andExpect(jsonPath("$.data.organization.id").value(SeedIds.ORG_ACME))
                .andExpect(jsonPath("$.data.organization.name").value("Acme Global Manufacturing"))
                .andExpect(jsonPath("$.data.role").value("COMPANY_ADMIN"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions").isNotEmpty())
                .andExpect(jsonPath("$.data.memberships[0].organizationId").value(SeedIds.ORG_ACME))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(node.get("data").get("accessToken").asText().isBlank());
        assertFalse(node.get("data").get("refreshToken").asText().isBlank());
    }

    @Test
    void loginRejectsWrongPasswordWithEnvelope401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acmeglobal.com\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginValidatesPayloadWithEnvelope400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Password123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void holderOfPermissionPassesMethodSecurity() throws Exception {
        String adminToken = loginToken("admin@acmeglobal.com", "Password123!");

        // Phase 4: the endpoint is PostgreSQL-backed with Node's payload
        // contract (country + gridRegion required) and answers 201 on create.
        mockMvc.perform(post("/api/v1/facilities")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phase 3 Probe Facility\",\"facilityCode\":\"FAC-PROBE3\","
                                + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void roleWithoutPermissionIsDeniedWithEnvelope403() throws Exception {
        // ASSURANCE_PROVIDER holds audits.review but not facilities.create.
        String auditorToken = loginToken("auditor@ey-assurance.com", "Password123!");

        mockMvc.perform(post("/api/v1/facilities")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Should Not Be Created\",\"facilityCode\":\"FAC-DENY\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void testSuiteEndpointIsRestrictedToPlatformAdministrators() throws Exception {
        // Not public: anonymous callers get 401, tenant admins get 403 because
        // COMPANY_ADMIN does not hold platform.tenants.manage — and now, with the
        // platform seed in place, a real PLATFORM_ADMIN can finally reach it.
        mockMvc.perform(get("/api/v1/test-suite/run"))
                .andExpect(status().isUnauthorized());

        String adminToken = loginToken("admin@acmeglobal.com", "Password123!");
        mockMvc.perform(get("/api/v1/test-suite/run")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        String platformToken = loginToken("platform.admin@carbonflow.test", "Password123!");
        mockMvc.perform(get("/api/v1/test-suite/run")
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // Phase 6: the self-test suite must actually PASS against the
                // live database — every re-pointed check (tenant isolation,
                // decimal arithmetic, unit normalization, Scope 2 segregation,
                // audit guard, evidence seal) reports green.
                .andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.failed").value(0));
    }

    @Test
    void invalidBearerTokenIsRejectedAsInvalidToken() throws Exception {
        // An explicit, parseable 401 (Node parity): not a silent pass-through.
        mockMvc.perform(get("/api/v1/facilities")
                        .header("Authorization", "Bearer completely.bogus.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.error.message")
                        .value("Access token expired or signature invalid."));
    }

    @Test
    void facilityReadsAreScopedToTheCallersTenant() throws Exception {
        String acmeToken = loginToken("admin@acmeglobal.com", "Password123!");
        String apexToken = loginToken("admin@apexcorp.com", "Password123!");

        // Phase 4: facilities live in PostgreSQL (the prototype DataStore no
        // longer feeds this endpoint), so each tenant creates its own probe.
        mockMvc.perform(post("/api/v1/facilities")
                        .header("Authorization", "Bearer " + acmeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Scope Probe\",\"facilityCode\":\"SCOPE-ACME\","
                                + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/facilities")
                        .header("Authorization", "Bearer " + apexToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Apex Scope Probe\",\"facilityCode\":\"SCOPE-APEX\","
                                + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}"))
                .andExpect(status().isCreated());

        JsonNode acmeFacilities = readFacilities(acmeToken);
        JsonNode apexFacilities = readFacilities(apexToken);

        assertFalse(acmeFacilities.isEmpty(), "expected facilities for Acme");
        assertFalse(apexFacilities.isEmpty(), "expected a facility for Apex");

        acmeFacilities.forEach(facility ->
                assertEquals(SeedIds.ORG_ACME, facility.get("organizationId").asText()));
        apexFacilities.forEach(facility ->
                assertEquals(SeedIds.ORG_APEX, facility.get("organizationId").asText()));
    }

    @Test
    void tamperedSignatureAndExpiredStyleFailuresSurfaceAsInvalidToken() throws Exception {
        // Flip a character of a real token so the signature no longer verifies.
        String token = loginToken("admin@acmeglobal.com", "Password123!");
        int midpoint = token.length() / 2;
        char original = token.charAt(midpoint);
        char flipped = original == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, midpoint) + flipped + token.substring(midpoint + 1);

        mockMvc.perform(get("/api/v1/facilities")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    private JsonNode readFacilities(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/facilities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
