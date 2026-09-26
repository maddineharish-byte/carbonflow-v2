package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Legal-entity management: contract semantics (docs/API.md §2.2 — list
 * {@code organization.read}, write {@code organization.update}), tenant
 * isolation, V4 ownership-range validation and FK-inspected deletion
 * (facilities are unlinked, never removed).
 */
class LegalEntityTest extends PostgresBackedIntegrationTest {

    private static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode createEntity(String token, String name, String jurisdiction) throws Exception {
        Api api = postJson("/api/v1/legal-entities", token,
                "{\"name\":\"" + name + "\",\"jurisdiction\":\"" + jurisdiction + "\"}");
        assertEquals(201, api.status(), "legal entity create must succeed: " + api.body());
        return api.body().get("data");
    }

    private boolean listContains(JsonNode list, String id) {
        for (JsonNode item : list) {
            if (id.equals(item.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void createReturns201WithDefaultsAndTenantStamp() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        Api api = postJson("/api/v1/legal-entities", token,
                "{\"name\":\"Holdings " + sfx + "\",\"jurisdiction\":\"US-DE\","
                        + "\"registrationNumber\":\"REG-" + sfx + "\","
                        + "\"ownershipPercentage\":75.5}");
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Legal entity created.", api.body().path("message").asText());

        JsonNode data = api.body().get("data");
        assertEquals(SeedIds.ORG_ACME, data.get("organizationId").asText());
        assertEquals("US-DE", data.get("jurisdiction").asText());
        assertEquals(75.5, data.get("ownershipPercentage").asDouble());
        assertTrue(data.get("createdAt").asText().length() > 0);

        // Omitted ownership defaults to the schema default of 100.
        JsonNode defaulted = createEntity(token, "Sole Prop " + sfx, "US-CA");
        assertEquals(100.0, defaulted.get("ownershipPercentage").asDouble());
    }

    @Test
    void listIsTenantScopedAndOrderedByName() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode zeta = createEntity(acme, "Zulu Entity " + sfx, "US-NY");
        JsonNode alpha = createEntity(acme, "Alpha Entity " + sfx, "US-TX");
        JsonNode foreign = createEntity(apex, "Apex Entity " + sfx, "US-NV");

        JsonNode list = getJson("/api/v1/legal-entities", acme).body().get("data");
        assertTrue(listContains(list, zeta.get("id").asText()));
        assertTrue(listContains(list, alpha.get("id").asText()));
        assertFalse(listContains(list, foreign.get("id").asText()),
                "another tenant's legal entity must never be listed");

        int alphaIndex = -1;
        int zetaIndex = -1;
        int index = 0;
        for (JsonNode item : list) {
            if (item.get("id").asText().equals(alpha.get("id").asText())) alphaIndex = index;
            if (item.get("id").asText().equals(zeta.get("id").asText())) zetaIndex = index;
            index++;
        }
        assertTrue(alphaIndex < zetaIndex, "Node parity: ordered by name");
    }

    @Test
    void crossTenantReadUpdateDeleteAreRejected() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode foreign = createEntity(apex, "Apex Vault " + sfx, "US-NV");
        String foreignId = foreign.get("id").asText();

        Api get = getJson("/api/v1/legal-entities/" + foreignId, acme);
        assertEquals(404, get.status());
        assertEquals("LEGAL_ENTITY_NOT_FOUND", get.errorCode());
        assertEquals("Legal entity does not exist or access denied.", get.errorMessage());

        Api put = putJson("/api/v1/legal-entities/" + foreignId, acme,
                "{\"name\":\"Hijacked Entity\"}");
        assertEquals(404, put.status());

        Api del = deleteJson("/api/v1/legal-entities/" + foreignId, acme);
        assertEquals(404, del.status());

        JsonNode intact = getJson("/api/v1/legal-entities/" + foreignId, apex).body().get("data");
        assertEquals("Apex Vault " + sfx, intact.get("name").asText(),
                "cross-tenant writes must not mutate the row");
    }

    @Test
    void partialUpdatePersistsOnlyProvidedFields() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode created = createEntity(token, "Update Target " + sfx, "US-WA");
        String id = created.get("id").asText();

        Api put = putJson("/api/v1/legal-entities/" + id, token,
                "{\"ownershipPercentage\":42.25}");
        assertEquals(200, put.status(), put.body().toString());
        JsonNode updated = put.body().get("data");
        assertEquals(42.25, updated.get("ownershipPercentage").asDouble());
        assertEquals("Update Target " + sfx, updated.get("name").asText(),
                "unspecified fields must remain untouched");
        assertEquals("US-WA", updated.get("jurisdiction").asText());
    }

    @Test
    void validationEnforcesRequiredFieldsLengthsAndOwnershipRange() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        Api missingJurisdiction = postJson("/api/v1/legal-entities", token,
                "{\"name\":\"No Jurisdiction " + sfx + "\"}");
        assertEquals(400, missingJurisdiction.status());
        assertEquals("VALIDATION_ERROR", missingJurisdiction.errorCode());
        assertEquals("Legal entity name and jurisdiction are required.",
                missingJurisdiction.errorMessage());

        Api ownershipTooHigh = postJson("/api/v1/legal-entities", token,
                "{\"name\":\"Over Owned " + sfx + "\",\"jurisdiction\":\"US-DE\","
                        + "\"ownershipPercentage\":150}");
        assertEquals(400, ownershipTooHigh.status());
        assertEquals("VALIDATION_ERROR", ownershipTooHigh.errorCode());
        assertTrue(ownershipTooHigh.errorMessage().contains("ownershipPercentage"));

        Api ownershipNegative = postJson("/api/v1/legal-entities", token,
                "{\"name\":\"Under Owned " + sfx + "\",\"jurisdiction\":\"US-DE\","
                        + "\"ownershipPercentage\":-1}");
        assertEquals(400, ownershipNegative.status());

        String overlong = "X".repeat(256);
        Api nameTooLong = postJson("/api/v1/legal-entities", token,
                "{\"name\":\"" + overlong + "\",\"jurisdiction\":\"US-DE\"}");
        assertEquals(400, nameTooLong.status(),
                "schema VARCHAR(255) must never surface as a raw database error");
        assertEquals("VALIDATION_ERROR", nameTooLong.errorCode());
    }

    @Test
    void deletionUnlinksFacilitiesInsteadOfRemovingThem() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode entity = createEntity(token, "Unlink Target " + sfx, "US-IL");
        String entityId = entity.get("id").asText();

        Api facility = postJson("/api/v1/facilities", token,
                "{\"name\":\"Linked Facility " + sfx + "\",\"facilityCode\":\"UL-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-MRO\","
                        + "\"legalEntityId\":\"" + entityId + "\"}");
        assertEquals(201, facility.status(), facility.body().toString());
        String facilityId = facility.body().get("data").get("id").asText();
        assertEquals(entityId, facility.body().get("data").get("legalEntityId").asText());

        assertEquals(200, deleteJson("/api/v1/legal-entities/" + entityId, token).status());

        Api gone = getJson("/api/v1/legal-entities/" + entityId, token);
        assertEquals(404, gone.status());
        assertEquals("LEGAL_ENTITY_NOT_FOUND", gone.errorCode());

        JsonNode survivor = getJson("/api/v1/facilities/" + facilityId, token).body().get("data");
        assertFalse(survivor.has("legalEntityId"),
                "ON DELETE SET NULL must unlink the facility, never delete it");

        // Clean up so later suites see an empty slot for their own fixtures.
        assertEquals(200, deleteJson("/api/v1/facilities/" + facilityId, token).status());
    }

    @Test
    void permissionGatesFollowTheFrozenRoleMatrix() throws Exception {
        String admin = loginToken("admin@acmeglobal.com", PASSWORD);
        String manager = loginToken("manager@acmeglobal.com", PASSWORD);
        String auditor = loginToken("auditor@ey-assurance.com", PASSWORD);
        String sfx = suffix();

        // SUSTAINABILITY_MANAGER has organization.read but not organization.update.
        assertEquals(200, getJson("/api/v1/legal-entities", manager).status());
        Api managerCreate = postJson("/api/v1/legal-entities", manager,
                "{\"name\":\"Manager Entity " + sfx + "\",\"jurisdiction\":\"US-DE\"}");
        assertEquals(403, managerCreate.status());
        assertEquals("FORBIDDEN", managerCreate.errorCode());

        // ASSURANCE_PROVIDER: read-only.
        assertEquals(200, getJson("/api/v1/legal-entities", auditor).status());
        assertEquals(403, postJson("/api/v1/legal-entities", auditor,
                "{\"name\":\"Auditor Entity\",\"jurisdiction\":\"US-DE\"}").status());

        // COMPANY_ADMIN can create and delete.
        JsonNode created = createEntity(admin, "Admin Entity " + sfx, "US-DE");
        assertEquals(200, deleteJson("/api/v1/legal-entities/" + created.get("id").asText(),
                admin).status());
    }
}
