package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 — {@code GET /emissions}: active-only ledger with Node's filter
 * contract (malformed → 400, empty → unfiltered), tenant scoping, the
 * {@code reports.read} permission matrix and four-decimal HALF_UP summary
 * rounding applied after summing.
 */
class EmissionLedgerTest extends AccountingTestBase {

    @Test
    void listRejectsMalformedFilterTreatsEmptyAsUnfilteredAndScopesToTenant()
            throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String apex = loginToken(APEX_EMAIL, PASSWORD);
        String[] foreign = freshScope(apex, "Ledger");

        String exact = "Emission filter is invalid.";
        assertEnvelope(400, "VALIDATION_ERROR", exact,
                getJson("/api/v1/emissions?periodId=not-a-uuid", token));
        assertEnvelope(400, "VALIDATION_ERROR", exact,
                getJson("/api/v1/emissions?periodId=00000000-0000-0000-0000-000000000000",
                        token));

        // empty filter = no filter (Node truthiness), always a well-formed page
        Api empty = getJson("/api/v1/emissions?periodId=", token);
        assertEquals(200, empty.status(), empty.body().toString());
        assertTrue(empty.body().path("data").path("records").isArray());
        assertTrue(empty.body().path("data").path("summary").isObject());

        // a syntactically valid foreign period yields an empty tenant-scoped page
        Api foreignPage = getJson("/api/v1/emissions?periodId=" + foreign[0], token);
        assertEquals(200, foreignPage.status(), foreignPage.body().toString());
        assertEquals(0, foreignPage.body().path("data").path("records").size());
        JsonNode summary = foreignPage.body().path("data").path("summary");
        assertWire("0", summary, "scope1Tonnes");
        assertWire("0", summary, "scope2LocationTonnes");
        assertWire("0", summary, "scope2MarketTonnes");
        assertWire("0", summary, "totalLocationBasedTonnes");
        assertWire("0", summary, "totalMarketBasedTonnes");
    }

    @Test
    void summaryRoundsFourDecimalsAfterSummingActiveRecordsOnly() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Summary");
        JsonNode first = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        JsonNode second = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "500", "kWh");

        Api firstRun = runCalculation(token, first.path("id").asText());
        assertEquals(200, firstRun.status(), firstRun.body().toString());
        String firstRecordId = firstRun.body().path("data").path("emissionRecord")
                .path("id").asText();

        Api secondRun = runCalculation(token, second.path("id").asText());
        assertEquals(200, secondRun.status(), secondRun.body().toString());
        String secondRecordId = secondRun.body().path("data").path("emissionRecord")
                .path("id").asText();

        // re-run the second activity: its first record supersedes
        Api supersede = runCalculation(token, second.path("id").asText());
        assertEquals(200, supersede.status(), supersede.body().toString());
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM emission_records WHERE id = ?::uuid",
                String.class, secondRecordId));

        Api ledger = getJson("/api/v1/emissions?periodId=" + scope[0], token);
        assertEquals(200, ledger.status(), ledger.body().toString());
        JsonNode records = ledger.body().path("data").path("records");
        assertEquals(2, records.size(), "only ACTIVE records list");
        assertNotNull(findById(records, firstRecordId));
        assertNotNull(findById(records,
                supersede.body().path("data").path("emissionRecord").path("id").asText()));

        // 0.216536 + 0.108268 = 0.324804 → 0.3248 at four decimals (HALF_UP)
        JsonNode summary = ledger.body().path("data").path("summary");
        assertWire("0.3248", summary, "scope1Tonnes");
        assertWire("0", summary, "scope2LocationTonnes");
        assertWire("0", summary, "scope2MarketTonnes");
        assertWire("0.3248", summary, "totalLocationBasedTonnes");
        assertWire("0.3248", summary, "totalMarketBasedTonnes");
    }

    @Test
    void emissionReadsArePermissionChecked() throws Exception {
        String auditor = loginToken(AUDITOR_EMAIL, PASSWORD);
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);

        // ASSURANCE_PROVIDER holds reports.read
        assertEquals(200, getJson("/api/v1/emissions", auditor).status());
        // PLATFORM_ADMIN does not
        assertEnvelope(403, "FORBIDDEN", "Insufficient permissions for this operation.",
                getJson("/api/v1/emissions", platform));
        // unauthenticated
        assertEnvelope(401, "UNAUTHORIZED",
                "Missing or malformed Authorization header or token query parameter.",
                getJson("/api/v1/emissions", null));
    }
}
