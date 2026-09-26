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
 * Department management (greenfield domain): facility-parent validation,
 * tenant isolation of departments and their parent facilities, and the RBAC
 * mapping onto the {@code facilities.*} codes (ADR-015).
 */
class DepartmentTest extends PostgresBackedIntegrationTest {

    private static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode createFacility(String token, String name, String code) throws Exception {
        Api api = postJson("/api/v1/facilities", token,
                "{\"name\":\"" + name + "\",\"facilityCode\":\"" + code + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }

    private JsonNode createDepartment(String token, String facilityId, String name) throws Exception {
        Api api = postJson("/api/v1/departments", token,
                "{\"facilityId\":\"" + facilityId + "\",\"name\":\"" + name + "\"}");
        assertEquals(201, api.status(), "department create must succeed: " + api.body());
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
    void createReturns201AndBelongsToFacilityPlusTenant() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode facility = createFacility(token, "Dept Plant " + sfx, "DP-" + sfx);
        String facilityId = facility.get("id").asText();

        Api api = postJson("/api/v1/departments", token,
                "{\"facilityId\":\"" + facilityId + "\",\"name\":\"Assembly Line " + sfx + "\"}");
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Department created.", api.body().path("message").asText());

        JsonNode data = api.body().get("data");
        assertEquals(SeedIds.ORG_ACME, data.get("organizationId").asText());
        assertEquals(facilityId, data.get("facilityId").asText());
        assertTrue(data.get("createdAt").asText().length() > 0);
    }

    @Test
    void createValidatesParentFacilityAndRequiredName() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode foreignFacility = createFacility(apex, "Apex Dept Plant " + sfx, "AD-" + sfx);

        // CRITICAL: a valid UUID of the other tenant must not become a parent.
        Api crossTenantParent = postJson("/api/v1/departments", acme,
                "{\"facilityId\":\"" + foreignFacility.get("id").asText()
                        + "\",\"name\":\"Hijacked Dept\"}");
        assertEquals(404, crossTenantParent.status());
        assertEquals("FACILITY_NOT_FOUND", crossTenantParent.errorCode());
        assertEquals("Facility does not exist or access denied.", crossTenantParent.errorMessage());

        Api missingParent = postJson("/api/v1/departments", acme,
                "{\"name\":\"Orphan Dept\"}");
        assertEquals(400, missingParent.status());
        assertEquals("VALIDATION_ERROR", missingParent.errorCode());
        assertEquals("facilityId is required.", missingParent.errorMessage());

        Api missingName = postJson("/api/v1/departments", acme,
                "{\"facilityId\":\"" + foreignFacility.get("id").asText() + "\"}");
        assertEquals(400, missingName.status());
        assertEquals("VALIDATION_ERROR", missingName.errorCode());

        String overlong = "D".repeat(151);
        JsonNode ownFacility = createFacility(acme, "Name Check " + sfx, "NC-" + sfx);
        Api nameTooLong = postJson("/api/v1/departments", acme,
                "{\"facilityId\":\"" + ownFacility.get("id").asText() + "\",\"name\":\"" + overlong + "\"}");
        assertEquals(400, nameTooLong.status());
        assertEquals("VALIDATION_ERROR", nameTooLong.errorCode());
        assertTrue(nameTooLong.errorMessage().contains("150"));
    }

    @Test
    void listSupportsTenantScopedFacilityFilter() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode facilityA = createFacility(acme, "Filter A " + sfx, "FA-" + sfx);
        JsonNode facilityB = createFacility(acme, "Filter B " + sfx, "FB-" + sfx);
        JsonNode foreignFacility = createFacility(apex, "Apex Filter " + sfx, "FF-" + sfx);

        JsonNode deptA = createDepartment(acme, facilityA.get("id").asText(), "Dept A " + sfx);
        JsonNode deptB = createDepartment(acme, facilityB.get("id").asText(), "Dept B " + sfx);
        createDepartment(apex, foreignFacility.get("id").asText(), "Apex Dept " + sfx);

        JsonNode all = getJson("/api/v1/departments", acme).body().get("data");
        assertTrue(listContains(all, deptA.get("id").asText()));
        assertTrue(listContains(all, deptB.get("id").asText()));

        JsonNode filtered = getJson("/api/v1/departments?facilityId="
                + facilityA.get("id").asText(), acme).body().get("data");
        assertTrue(listContains(filtered, deptA.get("id").asText()));
        assertFalse(listContains(filtered, deptB.get("id").asText()),
                "the facility filter must narrow the list");

        // A foreign-but-valid facility filter silently yields nothing (no information leak).
        JsonNode foreignFilter = getJson("/api/v1/departments?facilityId="
                + foreignFacility.get("id").asText(), acme).body().get("data");
        assertEquals(0, foreignFilter.size(),
                "filtering by another tenant's facility must return an empty list, not their data");

        Api badFilter = getJson("/api/v1/departments?facilityId=not-a-uuid", acme);
        assertEquals(400, badFilter.status());
        assertEquals("INVALID_ARGUMENT", badFilter.errorCode());
    }

    @Test
    void crossTenantReadUpdateDeleteAndReparentAreRejected() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode apexFacility = createFacility(apex, "Apex Vault Dept " + sfx, "VD-" + sfx);
        JsonNode foreignDept = createDepartment(apex, apexFacility.get("id").asText(),
                "Apex Vault Team " + sfx);
        String foreignDeptId = foreignDept.get("id").asText();

        Api get = getJson("/api/v1/departments/" + foreignDeptId, acme);
        assertEquals(404, get.status());
        assertEquals("DEPARTMENT_NOT_FOUND", get.errorCode());
        assertEquals("Department does not exist or access denied.", get.errorMessage());

        Api put = putJson("/api/v1/departments/" + foreignDeptId, acme,
                "{\"name\":\"Hijacked Team\"}");
        assertEquals(404, put.status());

        Api del = deleteJson("/api/v1/departments/" + foreignDeptId, acme);
        assertEquals(404, del.status());

        JsonNode intact = getJson("/api/v1/departments/" + foreignDeptId, apex).body().get("data");
        assertEquals("Apex Vault Team " + sfx, intact.get("name").asText(),
                "cross-tenant writes must not mutate the row");
    }

    @Test
    void updateRenamesAndReparentsWithinTheTenant() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode facilityA = createFacility(token, "Parent A " + sfx, "PA-" + sfx);
        JsonNode facilityB = createFacility(token, "Parent B " + sfx, "PB-" + sfx);
        JsonNode dept = createDepartment(token, facilityA.get("id").asText(), "Old Name " + sfx);
        String deptId = dept.get("id").asText();

        Api rename = putJson("/api/v1/departments/" + deptId, token,
                "{\"name\":\"New Name " + sfx + "\"}");
        assertEquals(200, rename.status(), rename.body().toString());
        assertEquals("Department updated.", rename.body().path("message").asText());
        assertEquals("New Name " + sfx, rename.body().get("data").get("name").asText());
        assertEquals(facilityA.get("id").asText(),
                rename.body().get("data").get("facilityId").asText(),
                "unspecified parent must remain untouched");

        Api reparent = putJson("/api/v1/departments/" + deptId, token,
                "{\"facilityId\":\"" + facilityB.get("id").asText() + "\"}");
        assertEquals(200, reparent.status(), reparent.body().toString());
        assertEquals(facilityB.get("id").asText(),
                reparent.body().get("data").get("facilityId").asText());
        assertEquals("New Name " + sfx, reparent.body().get("data").get("name").asText());
    }

    @Test
    void deleteRemovesOwnDepartmentAndCascadeFollowsTheFacility() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode facility = createFacility(token, "Cascade Plant " + sfx, "CS-" + sfx);
        String facilityId = facility.get("id").asText();
        JsonNode dept = createDepartment(token, facilityId, "Cascade Team " + sfx);
        String deptId = dept.get("id").asText();

        assertEquals(200, deleteJson("/api/v1/departments/" + deptId, token).status());
        Api gone = getJson("/api/v1/departments/" + deptId, token);
        assertEquals(404, gone.status());
        assertEquals("DEPARTMENT_NOT_FOUND", gone.errorCode());

        // V1: departments.facility_id ON DELETE CASCADE — facility removal
        // removes its departments (they are not independently referenced).
        JsonNode second = createDepartment(token, facilityId, "Second Team " + sfx);
        assertEquals(200, deleteJson("/api/v1/facilities/" + facilityId, token).status());
        assertEquals(404, getJson("/api/v1/departments/" + second.get("id").asText(), token).status());
    }

    @Test
    void permissionGatesFollowTheFrozenRoleMatrix() throws Exception {
        String admin = loginToken("admin@acmeglobal.com", PASSWORD);
        String manager = loginToken("manager@acmeglobal.com", PASSWORD);
        String auditor = loginToken("auditor@ey-assurance.com", PASSWORD);
        String sfx = suffix();

        JsonNode facility = createFacility(admin, "RBAC Dept Plant " + sfx, "RD-" + sfx);
        String facilityId = facility.get("id").asText();

        // SUSTAINABILITY_MANAGER: facilities.create ✓ (department create) …
        Api managerCreate = postJson("/api/v1/departments", manager,
                "{\"facilityId\":\"" + facilityId + "\",\"name\":\"Manager Team " + sfx + "\"}");
        assertEquals(201, managerCreate.status());
        // … facilities.read ✓ …
        assertEquals(200, getJson("/api/v1/departments", manager).status());
        // … but facilities.delete ✗ → 403.
        assertEquals(403, deleteJson("/api/v1/departments/"
                + managerCreate.body().get("data").get("id").asText(), manager).status());

        // ASSURANCE_PROVIDER: read ✓, write ✗.
        assertEquals(200, getJson("/api/v1/departments", auditor).status());
        assertEquals(403, postJson("/api/v1/departments", auditor,
                "{\"facilityId\":\"" + facilityId + "\",\"name\":\"Auditor Team\"}").status());

        // COMPANY_ADMIN holds facilities.delete.
        assertEquals(200, deleteJson("/api/v1/departments/"
                + managerCreate.body().get("data").get("id").asText(), admin).status());
    }
}
