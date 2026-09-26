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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Facility management against PostgreSQL: Node parity (list/create and the
 * {@code DUPLICATE_FACILITY_CODE} matrix), tenant isolation (IDOR attempts
 * that carry valid UUIDs from the other tenant), FK-safe deletion and the
 * frozen RBAC matrix.
 */
class FacilityTest extends PostgresBackedIntegrationTest {

    private static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode createFacility(String token, String name, String code) throws Exception {
        Api api = postJson("/api/v1/facilities", token,
                "{\"name\":\"" + name + "\",\"facilityCode\":\"" + code + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");
        assertEquals(201, api.status(), "facility create must succeed: " + api.body());
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

    private int indexOf(JsonNode list, String id) {
        int index = 0;
        for (JsonNode item : list) {
            if (id.equals(item.path("id").asText())) {
                return index;
            }
            index++;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Create / read
    // ------------------------------------------------------------------

    @Test
    void createReturns201WithSchemaShapeAndTenantStamp() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        Api api = postJson("/api/v1/facilities", token,
                "{\"name\":\"Assembly Plant " + sfx + "\",\"facilityCode\":\"FC-" + sfx + "\","
                        + "\"facilityType\":\"OFFICE\",\"country\":\"DE\","
                        + "\"stateProvince\":\"BW\",\"gridRegion\":\"EU-DE-GRID\","
                        + "\"floorAreaM2\":12000.5}");
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Facility registered successfully.", api.body().path("message").asText());

        JsonNode data = api.body().get("data");
        assertEquals(SeedIds.ORG_ACME, data.get("organizationId").asText());
        assertEquals("OFFICE", data.get("facilityType").asText());
        assertEquals("DE", data.get("country").asText());
        assertEquals("EU-DE-GRID", data.get("gridRegion").asText());
        assertEquals(12000.5, data.get("floorAreaM2").asDouble());
        assertTrue(data.get("createdAt").asText().length() > 0, "createdAt must be present");
        assertTrue(data.get("updatedAt").asText().length() > 0, "updatedAt must be present");
        assertFalse(data.has("legalEntityId"), "absent legal entity must not be serialized");
    }

    @Test
    void createDefaultsFacilityTypeAndAllowsMultinationalCountries() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        Api brazilApi = postJson("/api/v1/facilities", token,
                "{\"name\":\"Paraná Site " + sfx + "\",\"facilityCode\":\"BR-" + sfx + "\","
                        + "\"country\":\"BR\",\"gridRegion\":\"BR-SUL\"}");
        assertEquals(201, brazilApi.status(), brazilApi.body().toString());
        JsonNode brazil = brazilApi.body().get("data");
        assertEquals("MANUFACTURING", brazil.get("facilityType").asText(),
                "missing facilityType falls back to the schema default");
        assertEquals("BR", brazil.get("country").asText());

        // Same code shape in the other tenant is a different uniqueness scope.
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        JsonNode apexFacility = createFacility(apex, "Apex Site " + sfx, "BR-" + sfx);
        assertNotEquals(brazil.get("id").asText(), apexFacility.get("id").asText());
        assertEquals(SeedIds.ORG_APEX, apexFacility.get("organizationId").asText());
    }

    @Test
    void listIsTenantScopedAndAlphabeticallyOrdered() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode zeta = createFacility(acme, "Zulu Works " + sfx, "ZL-" + sfx);
        JsonNode alpha = createFacility(acme, "Alpha Works " + sfx, "AL-" + sfx);
        JsonNode foreign = createFacility(apex, "Apex Zulu " + sfx, "AZ-" + sfx);

        JsonNode list = getJson("/api/v1/facilities", acme).body().get("data");
        assertTrue(listContains(list, zeta.get("id").asText()), "own facility must be listed");
        assertTrue(listContains(list, alpha.get("id").asText()), "own facility must be listed");
        assertFalse(listContains(list, foreign.get("id").asText()),
                "another tenant's facility must never be listed");
        assertTrue(indexOf(list, alpha.get("id").asText())
                        < indexOf(list, zeta.get("id").asText()),
                "Node parity: facilities ordered by name");
    }

    // ------------------------------------------------------------------
    // Tenant isolation (IDOR)
    // ------------------------------------------------------------------

    @Test
    void crossTenantReadsAreIndistinguishableFromMissingRows() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode own = createFacility(acme, "Acme Secure " + sfx, "AS-" + sfx);
        JsonNode foreign = createFacility(apex, "Apex Secure " + sfx, "AX-" + sfx);

        assertEquals(200, getJson("/api/v1/facilities/" + own.get("id").asText(), acme).status());

        Api crossTenant = getJson("/api/v1/facilities/" + foreign.get("id").asText(), acme);
        assertEquals(404, crossTenant.status());
        assertEquals("FACILITY_NOT_FOUND", crossTenant.errorCode());
        assertEquals("Facility does not exist or access denied.", crossTenant.errorMessage());

        Api randomUuid = getJson("/api/v1/facilities/" + UUID.randomUUID(), acme);
        assertEquals(404, randomUuid.status());
        assertEquals(crossTenant.errorMessage(), randomUuid.errorMessage(),
                "cross-tenant and nonexistent ids must be indistinguishable");

        Api malformed = getJson("/api/v1/facilities/not-a-uuid", acme);
        assertEquals(404, malformed.status());
        assertEquals("FACILITY_NOT_FOUND", malformed.errorCode(),
                "malformed ids never reach SQL; they answer like missing rows");
    }

    @Test
    void crossTenantUpdateAndDeleteAreRejectedAndLeaveRowsIntact() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode foreign = createFacility(apex, "Apex Vault " + sfx, "AV-" + sfx);
        String foreignId = foreign.get("id").asText();

        Api put = putJson("/api/v1/facilities/" + foreignId, acme,
                "{\"name\":\"Hijacked\",\"facilityCode\":\"HV-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\"}");
        assertEquals(404, put.status());
        assertEquals("FACILITY_NOT_FOUND", put.errorCode());

        Api del = deleteJson("/api/v1/facilities/" + foreignId, acme);
        assertEquals(404, del.status());
        assertEquals("FACILITY_NOT_FOUND", del.errorCode());

        JsonNode stillThere = getJson("/api/v1/facilities/" + foreignId, apex).body().get("data");
        assertEquals("Apex Vault " + sfx, stillThere.get("name").asText(),
                "cross-tenant writes must not mutate the row");
        assertEquals("AV-" + sfx, stillThere.get("facilityCode").asText());
    }

    // ------------------------------------------------------------------
    // Validation & duplicates
    // ------------------------------------------------------------------

    @Test
    void duplicateCodeRuleIsTenantScoped() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        createFacility(acme, "Acme Dup " + sfx, "DUP-" + sfx);

        Api sameTenant = postJson("/api/v1/facilities", acme,
                "{\"name\":\"Acme Dup Again\",\"facilityCode\":\"DUP-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\"}");
        assertEquals(409, sameTenant.status());
        assertEquals("DUPLICATE_FACILITY_CODE", sameTenant.errorCode());
        assertEquals("Facility code already exists.", sameTenant.errorMessage());

        Api otherTenant = postJson("/api/v1/facilities", apex,
                "{\"name\":\"Apex Dup\",\"facilityCode\":\"DUP-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\"}");
        assertEquals(201, otherTenant.status(),
                "the V4 unique index is (organization_id, facility_code) — other tenants may reuse the code");
    }

    @Test
    void validationRejectsMissingAndInvalidFields() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        Api missingGrid = postJson("/api/v1/facilities", token,
                "{\"name\":\"No Grid " + sfx + "\",\"facilityCode\":\"NG-" + sfx + "\","
                        + "\"country\":\"US\"}");
        assertEquals(400, missingGrid.status());
        assertEquals("VALIDATION_ERROR", missingGrid.errorCode());
        assertEquals("Facility name, code, country, and grid region are required.",
                missingGrid.errorMessage());

        Api badType = postJson("/api/v1/facilities", token,
                "{\"name\":\"Bad Type\",\"facilityCode\":\"BT-" + sfx + "\","
                        + "\"facilityType\":\"CASTLE\",\"country\":\"US\",\"gridRegion\":\"US-X\"}");
        assertEquals(400, badType.status());
        assertEquals("VALIDATION_ERROR", badType.errorCode());
        assertTrue(badType.errorMessage().contains("facilityType"));

        Api negativeArea = postJson("/api/v1/facilities", token,
                "{\"name\":\"Negative Area\",\"facilityCode\":\"NA-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\",\"floorAreaM2\":-1}");
        assertEquals(400, negativeArea.status());
        assertEquals("VALIDATION_ERROR", negativeArea.errorCode());
        assertTrue(negativeArea.errorMessage().contains("floorAreaM2"));

        Api longCountry = postJson("/api/v1/facilities", token,
                "{\"name\":\"Long Country\",\"facilityCode\":\"LC-" + sfx + "\","
                        + "\"country\":\"TOOLONGCOUNTRY\",\"gridRegion\":\"US-X\"}");
        assertEquals(400, longCountry.status());
        assertEquals("VALIDATION_ERROR", longCountry.errorCode());
        assertTrue(longCountry.errorMessage().contains("Country"));
    }

    @Test
    void legalEntityReferenceIsTenantValidated() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode ownEntity = null;
        Api entity = postJson("/api/v1/legal-entities", acme,
                "{\"name\":\"Acme Entity " + sfx + "\",\"jurisdiction\":\"US-DE\"}");
        assertEquals(201, entity.status(), entity.body().toString());
        ownEntity = entity.body().get("data");

        JsonNode linked = null;
        Api linkedApi = postJson("/api/v1/facilities", acme,
                "{\"name\":\"Linked Plant " + sfx + "\",\"facilityCode\":\"LP-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\","
                        + "\"legalEntityId\":\"" + ownEntity.get("id").asText() + "\"}");
        assertEquals(201, linkedApi.status(), linkedApi.body().toString());
        linked = linkedApi.body().get("data");
        assertEquals(ownEntity.get("id").asText(), linked.get("legalEntityId").asText());

        JsonNode foreignEntity = null;
        Api foreign = postJson("/api/v1/legal-entities", apex,
                "{\"name\":\"Apex Entity " + sfx + "\",\"jurisdiction\":\"US-NV\"}");
        assertEquals(201, foreign.status(), foreign.body().toString());
        foreignEntity = foreign.body().get("data");

        Api crossTenantLink = postJson("/api/v1/facilities", acme,
                "{\"name\":\"Cross Link " + sfx + "\",\"facilityCode\":\"CL-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\","
                        + "\"legalEntityId\":\"" + foreignEntity.get("id").asText() + "\"}");
        assertEquals(404, crossTenantLink.status(),
                "a valid UUID from another tenant must not be linkable");
        assertEquals("LEGAL_ENTITY_NOT_FOUND", crossTenantLink.errorCode());
    }

    // ------------------------------------------------------------------
    // Update / delete
    // ------------------------------------------------------------------

    @Test
    void updateFullReplacePersistsForOwnTenant() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode created = createFacility(token, "Original " + sfx, "OR-" + sfx);
        String id = created.get("id").asText();

        Api put = putJson("/api/v1/facilities/" + id, token,
                "{\"name\":\"Renamed " + sfx + "\",\"facilityCode\":\"RN-" + sfx + "\","
                        + "\"facilityType\":\"DATA_CENTER\",\"country\":\"SG\","
                        + "\"gridRegion\":\"SG-GRID\",\"floorAreaM2\":800}");
        assertEquals(200, put.status(), put.body().toString());
        assertEquals("Facility updated.", put.body().path("message").asText());

        JsonNode updated = put.body().get("data");
        assertEquals("Renamed " + sfx, updated.get("name").asText());
        assertEquals("DATA_CENTER", updated.get("facilityType").asText());
        assertEquals("SG", updated.get("country").asText());

        // Code change to an existing own-tenant code → 409.
        JsonNode other = createFacility(token, "Other " + sfx, "OT-" + sfx);
        Api collide = putJson("/api/v1/facilities/" + id, token,
                "{\"name\":\"Renamed " + sfx + "\",\"facilityCode\":\"" + other.get("facilityCode").asText() + "\","
                        + "\"country\":\"SG\",\"gridRegion\":\"SG-GRID\"}");
        assertEquals(409, collide.status());
        assertEquals("DUPLICATE_FACILITY_CODE", collide.errorCode());
    }

    @Test
    void deleteIsBlockedWhileActivityDataReferencesTheFacility() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode facility = createFacility(token, "Locked Plant " + sfx, "LK-" + sfx);
        String facilityId = facility.get("id").asText();
        String periodId = UUID.randomUUID().toString();
        String activityId = UUID.randomUUID().toString();

        jdbc.update("INSERT INTO reporting_periods (id, organization_id, name, start_date, end_date, status) "
                        + "VALUES (?, ?, ?, '2031-01-01', '2031-12-31', 'OPEN')",
                periodId, SeedIds.ORG_ACME, "FK Period " + sfx);
        jdbc.update("INSERT INTO activity_data (id, organization_id, reporting_period_id, facility_id, "
                        + "scope, category, activity_type, quantity, unit, start_date, end_date, source) "
                        + "VALUES (?, ?, ?, ?, 'SCOPE_1', 'Test', 'FK_TEST', 1, 'kWh', "
                        + "'2031-01-01', '2031-01-02', 'integration test')",
                activityId, SeedIds.ORG_ACME, periodId, facilityId);
        try {
            Api blocked = deleteJson("/api/v1/facilities/" + facilityId, token);
            assertEquals(409, blocked.status(),
                    "ON DELETE RESTRICT from activity_data must block hard deletion");
            assertEquals("RESOURCE_IN_USE", blocked.errorCode());
            assertEquals(200, getJson("/api/v1/facilities/" + facilityId, token).status(),
                    "facility must survive a blocked delete");
        } finally {
            jdbc.update("DELETE FROM activity_data WHERE id = ?", activityId);
            jdbc.update("DELETE FROM reporting_periods WHERE id = ?", periodId);
        }

        // With the reference gone the delete succeeds and the row disappears.
        assertEquals(200, deleteJson("/api/v1/facilities/" + facilityId, token).status());
        Api gone = getJson("/api/v1/facilities/" + facilityId, token);
        assertEquals(404, gone.status());
        assertEquals("FACILITY_NOT_FOUND", gone.errorCode());
    }

    @Test
    void deleteWithoutReferencesSucceedsAndCrossTenantDeleteIsRejected() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode own = createFacility(acme, "Disposable " + sfx, "DP-" + sfx);
        JsonNode foreign = createFacility(apex, "Apex Keep " + sfx, "KP-" + sfx);

        Api rejected = deleteJson("/api/v1/facilities/" + foreign.get("id").asText(), acme);
        assertEquals(404, rejected.status());
        assertEquals("FACILITY_NOT_FOUND", rejected.errorCode());

        assertEquals(200, deleteJson("/api/v1/facilities/" + own.get("id").asText(), acme).status());
        assertEquals(200, getJson("/api/v1/facilities/" + foreign.get("id").asText(), apex).status());
    }

    // ------------------------------------------------------------------
    // RBAC
    // ------------------------------------------------------------------

    @Test
    void permissionGatesFollowTheFrozenRoleMatrix() throws Exception {
        String admin = loginToken("admin@acmeglobal.com", PASSWORD);
        String manager = loginToken("manager@acmeglobal.com", PASSWORD);
        String auditor = loginToken("auditor@ey-assurance.com", PASSWORD);
        String sfx = suffix();

        // SUSTAINABILITY_MANAGER: facilities.create ✓ facilities.read ✓ facilities.delete ✗
        Api managerCreate = postJson("/api/v1/facilities", manager,
                "{\"name\":\"Managed " + sfx + "\",\"facilityCode\":\"MG-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\"}");
        assertEquals(201, managerCreate.status());
        assertEquals(200, getJson("/api/v1/facilities", manager).status());
        String managedId = managerCreate.body().get("data").get("id").asText();
        assertEquals(403, deleteJson("/api/v1/facilities/" + managedId, manager).status(),
                "facilities.delete belongs to COMPANY_ADMIN only");
        assertEquals(200, deleteJson("/api/v1/facilities/" + managedId, admin).status());

        // ASSURANCE_PROVIDER: facilities.read ✓ create ✗
        assertEquals(200, getJson("/api/v1/facilities", auditor).status());
        Api auditorCreate = postJson("/api/v1/facilities", auditor,
                "{\"name\":\"Auditor Plant\",\"facilityCode\":\"AU-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-X\"}");
        assertEquals(403, auditorCreate.status());
        assertEquals("FORBIDDEN", auditorCreate.errorCode());

        // Unauthenticated → 401 envelope.
        Api anonymous = getJson("/api/v1/facilities", null);
        assertEquals(401, anonymous.status());
        assertEquals("UNAUTHORIZED", anonymous.errorCode());
    }
}
