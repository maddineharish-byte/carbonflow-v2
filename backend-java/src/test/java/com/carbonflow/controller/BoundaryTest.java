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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Organizational-boundary management — the CRITICAL Phase 4 tenant rule:
 * boundary membership ({@code boundary_facilities}) must never accept a
 * cross-tenant pairing, <b>even when both UUIDs are valid</b>. Every attach
 * path (bulk {@code facilityIds} on create, single {@code POST
 * /boundaries/{id}/facilities}) is proven to reject the other tenant's
 * facility with 404 and to persist nothing, verified directly against
 * {@code boundary_facilities}.
 */
class BoundaryTest extends PostgresBackedIntegrationTest {

    private static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    @Autowired
    private JdbcTemplate jdbc;

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

    private JsonNode createPeriod(String token, String name, String start, String end)
            throws Exception {
        Api api = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"" + name + "\",\"startDate\":\"" + start + "\","
                        + "\"endDate\":\"" + end + "\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }

    private int linkCount(String boundaryId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM boundary_facilities WHERE boundary_id = ?",
                Integer.class, boundaryId);
        return count == null ? 0 : count;
    }

    private int linksForFacility(String facilityId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM boundary_facilities WHERE facility_id = ?",
                Integer.class, facilityId);
        return count == null ? 0 : count;
    }

    private boolean listContains(JsonNode list, String id) {
        for (JsonNode item : list) {
            if (id.equals(item.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Create / read
    // ------------------------------------------------------------------

    @Test
    void createPersistsBoundaryAndMembershipServerSide() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(token, "Boundary Period " + sfx,
                "2043-01-01", "2043-12-31");
        JsonNode facilityA = createFacility(token, "Boundary Plant A " + sfx, "BA-" + sfx);
        JsonNode facilityB = createFacility(token, "Boundary Plant B " + sfx, "BB-" + sfx);

        Api api = postJson("/api/v1/boundaries", token,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\","
                        + "\"notes\":\"GHG Protocol Chapter 3 consolidation " + sfx + "\","
                        + "\"facilityIds\":[\"" + facilityA.get("id").asText() + "\",\""
                        + facilityB.get("id").asText() + "\"]}");
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Organizational boundary created.", api.body().path("message").asText());

        JsonNode data = api.body().get("data");
        String boundaryId = data.get("id").asText();
        assertEquals(SeedIds.ORG_ACME, data.get("organizationId").asText());
        assertEquals(period.get("id").asText(), data.get("reportingPeriodId").asText());
        assertEquals("OPERATIONAL_CONTROL", data.get("consolidationApproach").asText(),
                "consolidation approach defaults to the V1 column default");
        assertTrue(data.get("facilityIds").size() == 2,
                "membership must be echoed from the database, not the request");

        // Membership is really persisted (server-side, not client-inferred).
        assertEquals(2, linkCount(boundaryId));

        // The boundary surfaces in list + get with its membership.
        JsonNode fetched = getJson("/api/v1/boundaries/" + boundaryId, token).body().get("data");
        assertEquals(2, fetched.get("facilityIds").size());
        assertTrue(listContains(
                getJson("/api/v1/boundaries", token).body().get("data"), boundaryId));
    }

    @Test
    void createValidatesPeriodApproachAndPayload() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode ownPeriod = createPeriod(acme, "Own Period " + sfx,
                "2044-01-01", "2044-12-31");
        JsonNode foreignPeriod = createPeriod(apex, "Apex Period " + sfx,
                "2044-01-01", "2044-12-31");

        Api missingPeriod = postJson("/api/v1/boundaries", acme,
                "{\"consolidationApproach\":\"EQUITY_SHARE\"}");
        assertEquals(400, missingPeriod.status());
        assertEquals("VALIDATION_ERROR", missingPeriod.errorCode());
        assertEquals("reportingPeriodId is required.", missingPeriod.errorMessage());

        // CRITICAL: valid UUID, but the period belongs to another tenant.
        Api crossTenantPeriod = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + foreignPeriod.get("id").asText() + "\"}");
        assertEquals(404, crossTenantPeriod.status());
        assertEquals("REPORTING_PERIOD_NOT_FOUND", crossTenantPeriod.errorCode());

        Api badApproach = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + ownPeriod.get("id").asText() + "\","
                        + "\"consolidationApproach\":\"VIBES_BASED\"}");
        assertEquals(400, badApproach.status());
        assertEquals("VALIDATION_ERROR", badApproach.errorCode());
        assertTrue(badApproach.errorMessage().contains("consolidationApproach"));

        Api blankFacility = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + ownPeriod.get("id").asText() + "\","
                        + "\"facilityIds\":[\"  \"]}");
        assertEquals(400, blankFacility.status());
        assertEquals("VALIDATION_ERROR", blankFacility.errorCode());
        assertTrue(blankFacility.errorMessage().contains("facilityIds"));
    }

    // ------------------------------------------------------------------
    // CRITICAL: cross-tenant membership rejection
    // ------------------------------------------------------------------

    @Test
    void crITICAL_createWithCrossTenantFacilityIsRejectedAndPersistsNothing()
            throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(acme, "Critical Period " + sfx,
                "2045-01-01", "2045-12-31");
        JsonNode acmeFacility = createFacility(acme, "Critical Acme " + sfx, "CA-" + sfx);
        JsonNode apexFacility = createFacility(apex, "Critical Apex " + sfx, "CX-" + sfx);

        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM boundary_facilities WHERE facility_id = ?",
                Integer.class, apexFacility.get("id").asText());
        assertEquals(0, before);

        // Both IDs are valid UUIDs; the pairing is cross-tenant → must fail.
        Api api = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\","
                        + "\"facilityIds\":[\"" + acmeFacility.get("id").asText() + "\",\""
                        + apexFacility.get("id").asText() + "\"]}");
        assertEquals(404, api.status(),
                "a cross-tenant facility inside facilityIds must be rejected");
        assertEquals("FACILITY_NOT_FOUND", api.errorCode());
        assertEquals("Facility does not exist or access denied.", api.errorMessage());

        // Nothing persisted: no membership row for the foreign facility …
        assertEquals(0, linksForFacility(apexFacility.get("id").asText()),
                "the foreign facility must never appear in boundary_facilities");
        // … and the transactional write rolled the whole boundary back.
        JsonNode list = getJson("/api/v1/boundaries", acme).body().get("data");
        for (JsonNode item : list) {
            if (period.get("id").asText().equals(item.path("reportingPeriodId").asText())) {
                assertFalse(item.path("notes").asText().contains(sfx),
                        "no boundary row may survive a rejected membership");
            }
        }

        // The same payload minus the foreign facility succeeds (tenant-scoped).
        Api clean = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\","
                        + "\"facilityIds\":[\"" + acmeFacility.get("id").asText() + "\"]}");
        assertEquals(201, clean.status(), clean.body().toString());
        assertEquals(1, linkCount(clean.body().get("data").get("id").asText()));
    }

    @Test
    void crITICAL_attachVerbRejectsCrossTenantFacilityAndPersistsNothing()
            throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(acme, "Attach Period " + sfx,
                "2046-01-01", "2046-12-31");
        Api boundary = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\"}");
        assertEquals(201, boundary.status(), boundary.body().toString());
        String boundaryId = boundary.body().get("data").get("id").asText();

        JsonNode apexFacility = createFacility(apex, "Attach Apex " + sfx, "AX-" + sfx);
        JsonNode acmeFacility = createFacility(acme, "Attach Acme " + sfx, "AY-" + sfx);

        // CRITICAL: Organization A boundary + Organization B facility (valid UUIDs).
        Api crossTenant = postJson("/api/v1/boundaries/" + boundaryId + "/facilities", acme,
                "{\"facilityId\":\"" + apexFacility.get("id").asText() + "\"}");
        assertEquals(404, crossTenant.status(),
                "cross-tenant attach must be rejected even with valid UUIDs");
        assertEquals("FACILITY_NOT_FOUND", crossTenant.errorCode());
        assertEquals(0, linkCount(boundaryId), "no membership row may be written");
        assertEquals(0, linksForFacility(apexFacility.get("id").asText()));

        // Same-tenant attach succeeds and persists.
        Api ok = postJson("/api/v1/boundaries/" + boundaryId + "/facilities", acme,
                "{\"facilityId\":\"" + acmeFacility.get("id").asText() + "\"}");
        assertEquals(201, ok.status(), ok.body().toString());
        assertEquals("Facility attached to boundary.", ok.body().path("message").asText());
        assertEquals(1, linkCount(boundaryId));
        assertTrue(ok.body().get("data").get("facilityIds").toString()
                .contains(acmeFacility.get("id").asText()));

        // Duplicate attach → 409 (V1 primary key).
        Api duplicate = postJson("/api/v1/boundaries/" + boundaryId + "/facilities", acme,
                "{\"facilityId\":\"" + acmeFacility.get("id").asText() + "\"}");
        assertEquals(409, duplicate.status());
        assertEquals("DUPLICATE_BOUNDARY_FACILITY", duplicate.errorCode());
        assertEquals("Facility is already attached to this boundary.", duplicate.errorMessage());
        assertEquals(1, linkCount(boundaryId), "duplicate rows are impossible (composite PK)");

        // Detach succeeds, then a second detach answers 404.
        assertEquals(200, deleteJson("/api/v1/boundaries/" + boundaryId + "/facilities/"
                + acmeFacility.get("id").asText(), acme).status());
        assertEquals(0, linkCount(boundaryId));
        Api again = deleteJson("/api/v1/boundaries/" + boundaryId + "/facilities/"
                + acmeFacility.get("id").asText(), acme);
        assertEquals(404, again.status());
        assertEquals("BOUNDARY_FACILITY_NOT_FOUND", again.errorCode());
        assertEquals("Facility is not attached to this boundary.", again.errorMessage());

        // Cross-tenant detach of a foreign facility is equally rejected.
        Api crossDetach = deleteJson("/api/v1/boundaries/" + boundaryId + "/facilities/"
                + apexFacility.get("id").asText(), acme);
        assertEquals(404, crossDetach.status());
        assertEquals("FACILITY_NOT_FOUND", crossDetach.errorCode());
    }

    // ------------------------------------------------------------------
    // Update / delete / list isolation
    // ------------------------------------------------------------------

    @Test
    void updatePersistsApproachAndNotesButNotAcrossTenants() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(acme, "Update Period " + sfx,
                "2047-01-01", "2047-12-31");
        Api boundary = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\"}");
        String boundaryId = boundary.body().get("data").get("id").asText();

        Api put = putJson("/api/v1/boundaries/" + boundaryId, acme,
                "{\"consolidationApproach\":\"EQUITY_SHARE\",\"notes\":\"updated " + sfx + "\"}");
        assertEquals(200, put.status(), put.body().toString());
        assertEquals("Organizational boundary updated.", put.body().path("message").asText());
        assertEquals("EQUITY_SHARE", put.body().get("data").get("consolidationApproach").asText());
        assertEquals("updated " + sfx, put.body().get("data").get("notes").asText());

        Api badApproach = putJson("/api/v1/boundaries/" + boundaryId, acme,
                "{\"consolidationApproach\":\"NOPE\"}");
        assertEquals(400, badApproach.status());

        // Cross-tenant: apex's admin cannot read/update/delete Acme's boundary.
        assertEquals(404, getJson("/api/v1/boundaries/" + boundaryId, apex).status());
        assertEquals(404, putJson("/api/v1/boundaries/" + boundaryId, apex,
                "{\"notes\":\"hijack\"}").status());
        assertEquals(404, deleteJson("/api/v1/boundaries/" + boundaryId, apex).status());

        JsonNode intact = getJson("/api/v1/boundaries/" + boundaryId, acme).body().get("data");
        assertEquals("EQUITY_SHARE", intact.get("consolidationApproach").asText(),
                "cross-tenant writes must not mutate the row");
    }

    @Test
    void deleteRemovesBoundaryAndCascadesMembership() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(token, "Delete Period " + sfx,
                "2048-01-01", "2048-12-31");
        JsonNode facility = createFacility(token, "Delete Boundary Plant " + sfx, "DB-" + sfx);
        Api boundary = postJson("/api/v1/boundaries", token,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\","
                        + "\"facilityIds\":[\"" + facility.get("id").asText() + "\"]}");
        String boundaryId = boundary.body().get("data").get("id").asText();
        assertEquals(1, linkCount(boundaryId));

        assertEquals(200, deleteJson("/api/v1/boundaries/" + boundaryId, token).status());
        assertEquals(404, getJson("/api/v1/boundaries/" + boundaryId, token).status());
        assertEquals(0, linkCount(boundaryId),
                "V1: boundary_facilities cascades from organizational_boundaries");

        // The facility itself survives — only the boundary row disappears.
        assertEquals(200, getJson("/api/v1/facilities/" + facility.get("id").asText(),
                token).status());
    }

    @Test
    void listIsTenantScoped() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode acmePeriod = createPeriod(acme, "List Period " + sfx,
                "2049-01-01", "2049-12-31");
        JsonNode apexPeriod = createPeriod(apex, "Apex List Period " + sfx,
                "2049-01-01", "2049-12-31");

        Api acmeBoundary = postJson("/api/v1/boundaries", acme,
                "{\"reportingPeriodId\":\"" + acmePeriod.get("id").asText() + "\"}");
        Api apexBoundary = postJson("/api/v1/boundaries", apex,
                "{\"reportingPeriodId\":\"" + apexPeriod.get("id").asText() + "\"}");

        JsonNode acmeList = getJson("/api/v1/boundaries", acme).body().get("data");
        assertTrue(listContains(acmeList, acmeBoundary.body().get("data").get("id").asText()));
        assertFalse(listContains(acmeList, apexBoundary.body().get("data").get("id").asText()),
                "another tenant's boundary must never be listed");
    }

    @Test
    void permissionGatesFollowTheFrozenRoleMatrix() throws Exception {
        String admin = loginToken("admin@acmeglobal.com", PASSWORD);
        String manager = loginToken("manager@acmeglobal.com", PASSWORD);
        String auditor = loginToken("auditor@ey-assurance.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(admin, "RBAC Period " + sfx,
                "2050-01-01", "2050-12-31");

        // ASSURANCE_PROVIDER has reporting_periods.read but not .update → read ok, write 403.
        assertEquals(200, getJson("/api/v1/boundaries", auditor).status());
        Api auditorCreate = postJson("/api/v1/boundaries", auditor,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\"}");
        assertEquals(403, auditorCreate.status());
        assertEquals("FORBIDDEN", auditorCreate.errorCode());

        // SUSTAINABILITY_MANAGER holds reporting_periods.update → may configure boundaries.
        Api managerCreate = postJson("/api/v1/boundaries", manager,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\","
                        + "\"notes\":\"manager configured " + sfx + "\"}");
        assertEquals(201, managerCreate.status(), managerCreate.body().toString());
        String managerBoundaryId = managerCreate.body().get("data").get("id").asText();
        assertEquals(200, putJson("/api/v1/boundaries/" + managerBoundaryId, manager,
                "{\"consolidationApproach\":\"FINANCIAL_CONTROL\"}").status());
        assertEquals(200, deleteJson("/api/v1/boundaries/" + managerBoundaryId, manager).status());
    }
}
