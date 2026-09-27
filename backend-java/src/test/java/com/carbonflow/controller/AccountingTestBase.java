package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared fixtures for the Phase 6 accounting tests: activity-data builders,
 * calculation runners and exact wire-decimal assertions.
 *
 * <p>Every test creates its own throwaway period/facility pair (per-test UUID
 * suffix) and scopes ledger assertions to that period — the Spring context and
 * the database are shared by the whole suite, so no test may depend on global
 * row counts or totals.
 */
abstract class AccountingTestBase extends AuditTestBase {

    protected static final String ADMIN_EMAIL = "admin@acmeglobal.com";
    protected static final String AUDITOR_EMAIL = "auditor@ey-assurance.com";
    protected static final String PLATFORM_EMAIL = "platform.admin@carbonflow.test";
    protected static final String APEX_EMAIL = "admin@apexcorp.com";

    /** One throwaway (period id, facility id) pair owned by the token's tenant. */
    protected String[] freshScope(String token, String label) throws Exception {
        JsonNode period = createPeriod(token, label + " " + suffix());
        JsonNode facility = createFacility(token, label + " Plant " + suffix(),
                "P6-" + suffix());
        return new String[] {period.get("id").asText(), facility.get("id").asText()};
    }

    /** Registers a SUBMITTED activity (201 asserted) and returns its data node. */
    protected JsonNode createActivity(String token, String periodId, String facilityId,
                                      String scope, String category, String activityType,
                                      String quantity, String unit) throws Exception {
        Api api = postJson("/api/v1/activity-data", token,
                activityBody(periodId, facilityId, scope, category, activityType,
                        quantity, unit));
        assertEquals(201, api.status(), api.body().toString());
        return api.body().path("data");
    }

    /** The exact create payload contract (quantity interpolated for mutations). */
    protected static String activityBody(String periodId, String facilityId, String scope,
                                         String category, String activityType,
                                         String quantity, String unit) {
        return "{\"reportingPeriodId\":\"" + periodId + "\",\"facilityId\":\"" + facilityId
                + "\",\"scope\":\"" + scope + "\",\"category\":\"" + category
                + "\",\"activityType\":\"" + activityType + "\",\"quantity\":" + quantity
                + ",\"unit\":\"" + unit + "\",\"startDate\":\"2043-01-01\","
                + "\"endDate\":\"2043-12-31\",\"source\":\"Phase 6 test fixture\"}";
    }

    protected Api runCalculation(String token, String activityDataId) throws Exception {
        return postJson("/api/v1/calculations/run", token,
                "{\"activityDataId\":\"" + activityDataId + "\"}");
    }

    protected Api batchCalculation(String token, String periodId) throws Exception {
        return postJson("/api/v1/calculations/batch-run", token,
                "{\"reportingPeriodId\":\"" + periodId + "\"}");
    }

    /** Scale-insensitive numeric view of a wire field (JSON number or integral). */
    protected static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertTrue(value != null && !value.isNull(), "missing numeric field: " + field);
        return value.decimalValue();
    }

    /**
     * Asserts a wire number equals an exact literal regardless of trailing
     * zeros or parse scale — {@code 0.4000} == {@code 0.4}, {@code 1} == {@code 1.00}.
     */
    protected static void assertWire(String expected, JsonNode node, String field) {
        assertEquals(0, decimal(node, field).compareTo(new BigDecimal(expected)),
                field + ": expected " + expected + ", wire was " + node.get(field));
    }

    protected static void assertEnvelope(int expectedStatus, String code, String message,
                                         Api api) {
        assertEquals(expectedStatus, api.status(), api.body().toString());
        assertEquals(code, api.errorCode(), api.body().toString());
        assertEquals(message, api.errorMessage(), api.body().toString());
    }

    /** First record of a JSON array whose {@code id} matches, or null. */
    protected static JsonNode findById(JsonNode array, String id) {
        for (JsonNode item : array) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }
}
