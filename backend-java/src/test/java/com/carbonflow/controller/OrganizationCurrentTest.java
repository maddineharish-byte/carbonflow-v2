package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET/PUT /organizations/current} — contract port of the Node reference
 * backend: tenant-scoped by the authenticated context, partial updates only,
 * 404 {@code ORG_NOT_FOUND} when the row is missing, message
 * {@code "Organization updated successfully."}. Consolidation approach and base
 * year are validated before hitting the V1 CHECK constraints (documented
 * deviation: invalid values answer 400 instead of surfacing as a 500).
 */
class OrganizationCurrentTest extends PostgresBackedIntegrationTest {

    @Test
    void companyAdminReadsOwnTenantProfile() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api api = getJson("/api/v1/organizations/current", token);
        assertEquals(200, api.status());
        JsonNode org = api.body().get("data");
        assertEquals(SeedIds.ORG_ACME, org.get("id").asText());
        assertEquals("Acme Global Manufacturing", org.get("name").asText());
        assertEquals("US", org.get("country").asText());
        assertEquals("ACTIVE", org.get("status").asText());
        // Defaults come from the V1 schema (COALESCE in the repository).
        assertEquals("OPERATIONAL_CONTROL", org.get("consolidationApproach").asText());
        assertEquals(2023, org.get("baseYear").asInt());
    }

    @Test
    void updateAppliesPartialChangesAndPersistsThem() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);
        String newName = "Acme Global Manufacturing Ltd " + UUID.randomUUID().toString().substring(0, 8);

        Api put = putJson("/api/v1/organizations/current", token,
                "{\"name\":\"" + newName + "\",\"baseYear\":2024}");
        assertEquals(200, put.status());
        assertEquals("Organization updated successfully.", put.body().path("message").asText());
        assertEquals(newName, put.body().path("data").path("name").asText());
        assertEquals(2024, put.body().path("data").path("baseYear").asInt());

        // Persisted: a fresh read (and a fresh login session) sees the change.
        Api get = getJson("/api/v1/organizations/current", token);
        assertEquals(200, get.status());
        assertEquals(newName, get.body().path("data").path("name").asText());
        assertEquals(2024, get.body().path("data").path("baseYear").asInt());
    }

    @Test
    void updateRejectsInvalidConsolidationApproach() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api api = putJson("/api/v1/organizations/current", token,
                "{\"consolidationApproach\":\"SOMETHING_NEW\"}");
        assertEquals(400, api.status());
        assertEquals("VALIDATION_ERROR", api.errorCode());
    }

    @Test
    void updateRejectsInvalidBaseYear() throws Exception {
        String token = loginToken("admin@acmeglobal.com", SeedIds.DEMO_PASSWORD);

        Api api = putJson("/api/v1/organizations/current", token, "{\"baseYear\":1750}");
        assertEquals(400, api.status());
        assertEquals("VALIDATION_ERROR", api.errorCode());
    }

    @Test
    void assuranceProviderCanReadButNotUpdate() throws Exception {
        String token = loginToken("auditor@ey-assurance.com", SeedIds.DEMO_PASSWORD);

        assertEquals(200, getJson("/api/v1/organizations/current", token).status());

        Api put = putJson("/api/v1/organizations/current", token,
                "{\"name\":\"Should Not Apply\"}");
        assertEquals(403, put.status());
        assertEquals("FORBIDDEN", put.errorCode());
    }

    @Test
    void profilesAreTenantScopedToTheCaller() throws Exception {
        // Another tenant sees its own profile, never the mutated Acme one.
        String apexToken = loginToken("admin@apexcorp.com", SeedIds.DEMO_PASSWORD);

        Api api = getJson("/api/v1/organizations/current", apexToken);
        assertEquals(200, api.status());
        assertEquals(SeedIds.ORG_APEX, api.body().path("data").path("id").asText());
        assertEquals("Apex CleanTech Logistics", api.body().path("data").path("name").asText());
    }

    private Api putJson(String uri, String bearerToken, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(uri)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", "Bearer " + bearerToken);
        var result = mockMvc.perform(request).andReturn();
        return new Api(result.getResponse().getStatus(),
                objectMapper.readTree(result.getResponse().getContentAsString()));
    }
}
