package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Explicit scope-authorization matrix (Phase 4 module 7): the same caller,
 * the same permissions — allowed versus denied purely by <b>scope</b>
 * (organization ownership of the resource). Each denied case carries a valid
 * UUID belonging to the other tenant and must be indistinguishable from a
 * nonexistent resource (404, same message).
 */
class ScopeAuthorizationTest extends PostgresBackedIntegrationTest {

    private static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode create(String token, String uri, String body) throws Exception {
        Api api = postJson(uri, token, body);
        assertEquals(201, api.status(), "fixture create must succeed: " + api.body());
        return api.body().get("data");
    }

    /** Allowed/denied pairs must be indistinguishable in the denied case. */
    private void assertDeniedWithNotIndistinguishableMessage(Api denied, String code,
                                                             String message) {
        assertEquals(404, denied.status());
        assertEquals(code, denied.errorCode());
        assertEquals(message, denied.errorMessage());
    }

    // ------------------------------------------------------------------
    // Facility scope
    // ------------------------------------------------------------------

    @Test
    void scopeAuthorization_matrix_facility() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode own = create(acme, "/api/v1/facilities",
                "{\"name\":\"Allowed Facility " + sfx + "\",\"facilityCode\":\"AF-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");
        JsonNode foreign = create(apex, "/api/v1/facilities",
                "{\"name\":\"Denied Facility " + sfx + "\",\"facilityCode\":\"DF-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");

        // ALLOWED facility access — same permission, owned resource.
        Api allowed = getJson("/api/v1/facilities/" + own.get("id").asText(), acme);
        assertEquals(200, allowed.status());
        assertEquals(own.get("id").asText(), allowed.body().get("data").get("id").asText());

        // DENIED facility access — other tenant's valid UUID.
        Api denied = getJson("/api/v1/facilities/" + foreign.get("id").asText(), acme);
        assertDeniedWithNotIndistinguishableMessage(denied, "FACILITY_NOT_FOUND",
                "Facility does not exist or access denied.");

        // A nonexistent id answers exactly the same → no existence oracle.
        Api missing = getJson("/api/v1/facilities/" + UUID.randomUUID(), acme);
        assertEquals(denied.errorMessage(), missing.errorMessage());
        assertEquals(denied.errorCode(), missing.errorCode());
    }

    // ------------------------------------------------------------------
    // Department scope
    // ------------------------------------------------------------------

    @Test
    void scopeAuthorization_matrix_department() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode ownFacility = create(acme, "/api/v1/facilities",
                "{\"name\":\"Allowed Dept Plant " + sfx + "\",\"facilityCode\":\"AD-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");
        JsonNode foreignFacility = create(apex, "/api/v1/facilities",
                "{\"name\":\"Denied Dept Plant " + sfx + "\",\"facilityCode\":\"DD-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");

        JsonNode ownDepartment = create(acme, "/api/v1/departments",
                "{\"facilityId\":\"" + ownFacility.get("id").asText() + "\","
                        + "\"name\":\"Allowed Team " + sfx + "\"}");
        JsonNode foreignDepartment = create(apex, "/api/v1/departments",
                "{\"facilityId\":\"" + foreignFacility.get("id").asText() + "\","
                        + "\"name\":\"Denied Team " + sfx + "\"}");

        // ALLOWED department access.
        Api allowed = getJson("/api/v1/departments/" + ownDepartment.get("id").asText(), acme);
        assertEquals(200, allowed.status());
        assertEquals(ownDepartment.get("id").asText(),
                allowed.body().get("data").get("id").asText());

        // DENIED department access — other tenant's valid UUID.
        Api denied = getJson("/api/v1/departments/"
                + foreignDepartment.get("id").asText(), acme);
        assertDeniedWithNotIndistinguishableMessage(denied, "DEPARTMENT_NOT_FOUND",
                "Department does not exist or access denied.");

        Api missing = getJson("/api/v1/departments/" + UUID.randomUUID(), acme);
        assertEquals(denied.errorMessage(), missing.errorMessage());

        // Creating a department under a foreign facility is equally denied.
        Api deniedParent = postJson("/api/v1/departments", acme,
                "{\"facilityId\":\"" + foreignFacility.get("id").asText()
                        + "\",\"name\":\"Illegitimate Team\"}");
        assertEquals(404, deniedParent.status());
        assertEquals("FACILITY_NOT_FOUND", deniedParent.errorCode());
    }

    // ------------------------------------------------------------------
    // Reporting period scope
    // ------------------------------------------------------------------

    @Test
    void scopeAuthorization_matrix_reportingPeriod() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode own = create(acme, "/api/v1/reporting-periods",
                "{\"name\":\"Allowed Period " + sfx + "\",\"startDate\":\"2051-01-01\","
                        + "\"endDate\":\"2051-12-31\"}");
        JsonNode foreign = create(apex, "/api/v1/reporting-periods",
                "{\"name\":\"Denied Period " + sfx + "\",\"startDate\":\"2051-01-01\","
                        + "\"endDate\":\"2051-12-31\"}");

        assertEquals(200, getJson("/api/v1/reporting-periods/"
                + own.get("id").asText(), acme).status());

        Api denied = getJson("/api/v1/reporting-periods/" + foreign.get("id").asText(), acme);
        assertDeniedWithNotIndistinguishableMessage(denied, "REPORTING_PERIOD_NOT_FOUND",
                "Reporting period does not exist or access denied.");
    }

    // ------------------------------------------------------------------
    // Legal entity scope
    // ------------------------------------------------------------------

    @Test
    void scopeAuthorization_matrix_legalEntity() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode own = create(acme, "/api/v1/legal-entities",
                "{\"name\":\"Allowed Entity " + sfx + "\",\"jurisdiction\":\"US-DE\"}");
        JsonNode foreign = create(apex, "/api/v1/legal-entities",
                "{\"name\":\"Denied Entity " + sfx + "\",\"jurisdiction\":\"US-NV\"}");

        assertEquals(200, getJson("/api/v1/legal-entities/"
                + own.get("id").asText(), acme).status());

        Api denied = getJson("/api/v1/legal-entities/" + foreign.get("id").asText(), acme);
        assertDeniedWithNotIndistinguishableMessage(denied, "LEGAL_ENTITY_NOT_FOUND",
                "Legal entity does not exist or access denied.");
    }

    // ------------------------------------------------------------------
    // Boundary scope
    // ------------------------------------------------------------------

    @Test
    void scopeAuthorization_matrix_boundary() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode ownPeriod = create(acme, "/api/v1/reporting-periods",
                "{\"name\":\"Allowed Boundary Period " + sfx + "\",\"startDate\":\"2052-01-01\","
                        + "\"endDate\":\"2052-12-31\"}");
        JsonNode foreignPeriod = create(apex, "/api/v1/reporting-periods",
                "{\"name\":\"Denied Boundary Period " + sfx + "\",\"startDate\":\"2052-01-01\","
                        + "\"endDate\":\"2052-12-31\"}");

        JsonNode own = create(acme, "/api/v1/boundaries",
                "{\"reportingPeriodId\":\"" + ownPeriod.get("id").asText() + "\"}");
        JsonNode foreign = create(apex, "/api/v1/boundaries",
                "{\"reportingPeriodId\":\"" + foreignPeriod.get("id").asText() + "\"}");

        assertEquals(200, getJson("/api/v1/boundaries/"
                + own.get("id").asText(), acme).status());

        Api denied = getJson("/api/v1/boundaries/" + foreign.get("id").asText(), acme);
        assertDeniedWithNotIndistinguishableMessage(denied, "BOUNDARY_NOT_FOUND",
                "Boundary does not exist or access denied.");
    }

    // ------------------------------------------------------------------
    // Tenant context itself
    // ------------------------------------------------------------------

    @Test
    void scopeAuthorization_requiresAnAuthenticatedTenantScope() throws Exception {
        // No token → no scope at all → 401 before any repository is touched.
        assertEquals(401, getJson("/api/v1/facilities", null).status());
        assertEquals(401, getJson("/api/v1/legal-entities", null).status());
        assertEquals(401, getJson("/api/v1/departments", null).status());
        assertEquals(401, getJson("/api/v1/reporting-periods", null).status());
        assertEquals(401, getJson("/api/v1/boundaries", null).status());
    }
}
