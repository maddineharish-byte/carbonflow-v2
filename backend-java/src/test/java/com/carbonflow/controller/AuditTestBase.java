package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared fixtures for the Phase 5 governance tests: throwaway periods,
 * facilities, audits, the drive-to-REVIEW shortcut and direct JDBC activity
 * fixtures (the activity-data module itself is Phase 6).
 *
 * <p>All names/codes carry a per-test UUID suffix — the Spring context and
 * database are shared across the whole suite, so assertions must be
 * contains-style against self-created rows, never global counts.
 */
abstract class AuditTestBase extends PostgresBackedIntegrationTest {

    protected static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    @Autowired
    protected JdbcTemplate jdbc;

    protected String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected JsonNode createPeriod(String token, String name) throws Exception {
        Api api = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"" + name + "\",\"startDate\":\"2043-01-01\","
                        + "\"endDate\":\"2043-12-31\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }

    protected JsonNode createFacility(String token, String name, String code)
            throws Exception {
        Api api = postJson("/api/v1/facilities", token,
                "{\"name\":\"" + name + "\",\"facilityCode\":\"" + code + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }

    protected JsonNode createAudit(String token, String periodId) throws Exception {
        Api api = postJson("/api/v1/audits", token,
                "{\"reportingPeriodId\":\"" + periodId + "\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }

    /** Fresh period + audit for a test that starts from DRAFT. */
    protected JsonNode freshAudit(String token, String label) throws Exception {
        JsonNode period = createPeriod(token, label + " " + suffix());
        return createAudit(token, period.get("id").asText());
    }

    protected Api transition(String token, String auditId, String target, String reason)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"targetState\":\"" + target + "\"");
        if (reason != null) {
            body.append(",\"reason\":\"").append(reason).append("\"");
        }
        body.append("}");
        return postJson("/api/v1/audits/" + auditId + "/transition", token,
                body.toString());
    }

    /**
     * DRAFT → SUBMITTED → DATA_COLLECTION → VALIDATION → REVIEW as the
     * COMPANY_ADMIN (holds {@code audits.submit} for all four steps). No
     * gates apply before APPROVED, so the checklist need not be verified.
     */
    protected void driveToReview(String adminToken, String auditId) throws Exception {
        for (String target : new String[] {"SUBMITTED", "DATA_COLLECTION",
                "VALIDATION", "REVIEW"}) {
            Api api = transition(adminToken, auditId, target, null);
            assertEquals(200, api.status(), target + ": " + api.body().toString());
        }
    }

    /** Verifies every mandatory checklist item as the ASSURANCE_PROVIDER. */
    protected void verifyAllChecklist(String auditorToken, String auditId)
            throws Exception {
        Api detail = getJson("/api/v1/audits/" + auditId, auditorToken);
        assertEquals(200, detail.status(), detail.body().toString());
        for (JsonNode item : detail.body().path("data").path("checklist")) {
            Api verify = postJson(
                    "/api/v1/audits/" + auditId + "/checklist/"
                            + item.get("id").asText() + "/verify",
                    auditorToken, "{\"isSatisfied\":true}");
            assertEquals(200, verify.status(), verify.body().toString());
        }
    }

    /**
     * Direct {@code activity_data} fixture (module belongs to Phase 6 — no
     * API exists yet, so tests write the row the same way Phase 4 wrote
     * RESTRICT-proof fixtures). V5 tenant FKs require the (org, period) and
     * (org, facility) pairs to exist for the same organization.
     */
    protected String insertActivityData(String organizationId, String periodId,
                                        String facilityId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO activity_data "
                        + "(id, organization_id, reporting_period_id, facility_id, scope, "
                        + "category, activity_type, quantity, unit, start_date, end_date, "
                        + "source, status) "
                        + "VALUES (?, ?, ?, ?, 'SCOPE_1', 'Stationary Combustion', "
                        + "'Natural Gas', 100, 'kWh', '2043-01-01', '2043-12-31', "
                        + "'Phase 5 test fixture', 'DRAFT')",
                id, organizationId, periodId, facilityId);
        return id;
    }

    protected boolean listContains(JsonNode list, String id) {
        for (JsonNode item : list) {
            if (id.equals(item.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs in and switches the session into another tenant the user already
     * belongs to. RBAC runs before tenancy, so a foreign write probe must
     * clear {@code @PreAuthorize} with a reviewer-class role to actually
     * exercise the cross-tenant 404 (a role-less foreign caller would stop
     * at 403 and prove nothing about scoping).
     */
    protected String loginSwitchedToken(String email, String targetOrgId,
                                        String targetRole) throws Exception {
        String current = loginToken(email, PASSWORD);
        Api switched = postJson("/api/v1/auth/switch-tenant-or-role", current,
                "{\"targetOrgId\":\"" + targetOrgId + "\",\"targetRole\":\""
                        + targetRole + "\"}");
        assertEquals(200, switched.status(), switched.body().toString());
        return switched.body().path("data").path("accessToken").asText();
    }
}
