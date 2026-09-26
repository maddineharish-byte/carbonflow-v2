package com.carbonflow.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security-chain verification of the Phase 2 foundation:
 * public/protected split, envelope-shaped 401/403, BCrypt login through the
 * real filter chain, permission-based method security, and tenant isolation of
 * a read endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class SecurityChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("data").get("accessToken").asText();
    }

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
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void loginWithValidCredentialsReturnsTokenAndCanonicalRole() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acmeglobal.com\",\"password\":\"Password123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.role").value("COMPANY_ADMIN"))
                .andExpect(jsonPath("$.data.user.organizationId").value("org-acme-corp"))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(node.get("data").get("accessToken").asText().isBlank());
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
        String adminToken = login("admin@acmeglobal.com", "Password123!");

        mockMvc.perform(post("/api/v1/facilities")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phase 2 Probe Facility\",\"facilityCode\":\"FAC-PROBE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void roleWithoutPermissionIsDeniedWithEnvelope403() throws Exception {
        // ASSURANCE_PROVIDER holds audits.review but not facilities.create.
        String auditorToken = login("auditor@ey-assurance.com", "Password123!");

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
        // Not public anymore: anonymous callers get 401, tenant admins get 403
        // because COMPANY_ADMIN does not hold platform.tenants.manage.
        mockMvc.perform(get("/api/v1/test-suite/run"))
                .andExpect(status().isUnauthorized());

        String adminToken = login("admin@acmeglobal.com", "Password123!");
        mockMvc.perform(get("/api/v1/test-suite/run")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void invalidBearerTokenIsRejectedAsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/facilities")
                        .header("Authorization", "Bearer completely.bogus.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void facilityReadsAreScopedToTheCallersTenant() throws Exception {
        String acmeToken = login("admin@acmeglobal.com", "Password123!");
        String apexToken = login("admin@apexcorp.com", "Password123!");

        JsonNode acmeFacilities = readFacilities(acmeToken);
        JsonNode apexFacilities = readFacilities(apexToken);

        assertFalse(acmeFacilities.isEmpty(), "expected seeded facilities for Acme");
        assertFalse(apexFacilities.isEmpty(), "expected a seeded facility for Apex");

        acmeFacilities.forEach(facility ->
                assertEquals("org-acme-corp", facility.get("organizationId").asText()));
        apexFacilities.forEach(facility ->
                assertEquals("org-apex-cleantech", facility.get("organizationId").asText()));
    }

    private JsonNode readFacilities(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/facilities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }
}
