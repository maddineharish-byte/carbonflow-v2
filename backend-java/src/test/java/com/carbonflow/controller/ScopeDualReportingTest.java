package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Phase 6 — Scope 2 dual reporting (ADR-002): LOCATION_BASED and
 * MARKET_BASED are classified per activity, reported side by side, never
 * summed, never collapsed into one Scope 2 figure — from the calculation
 * response through the ledger summary.
 *
 * <p>Seeded expectations for 1000 kWh (AR6, gas-split factors):
 * <ul>
 *   <li>Scope 1 NATURAL_GAS → 0.216536 t;</li>
 *   <li>location GRID_ELECTRICITY_US → 399.987 kg = 0.399987 t;</li>
 *   <li>market RESIDUAL_MIX_US → 459.496 kg = 0.459496 t;</li>
 *   <li>market GREEN_POWER_TARIFF → 0 t (composite zero).</li>
 * </ul>
 */
class ScopeDualReportingTest extends AccountingTestBase {

    @Test
    void locationAndMarketStaySeparateFromCalculationToSummary() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Dual");

        JsonNode scope1 = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        JsonNode location = createActivity(token, scope[0], scope[1],
                "SCOPE_2", "ELECTRICITY_LOCATION", "GRID_ELECTRICITY_US", "1000", "kWh");
        JsonNode market = createActivity(token, scope[0], scope[1],
                "SCOPE_2", "ELECTRICITY_MARKET", "RESIDUAL_MIX_US", "1000", "kWh");
        JsonNode green = createActivity(token, scope[0], scope[1],
                "SCOPE_2", "ELECTRICITY_MARKET", "GREEN_POWER_TARIFF", "1000", "kWh");

        JsonNode scope1Record = runRecord(token, scope1);
        JsonNode locationRecord = runRecord(token, location);
        JsonNode marketRecord = runRecord(token, market);
        JsonNode greenRecord = runRecord(token, green);

        // classification: Scope 1 unclassified, electricity split by contract type
        assertEquals("SCOPE_1", scope1Record.path("scope").asText());
        assertFalse(scope1Record.has("scope2Type"),
                "Scope 1 must never carry a perspective: " + scope1Record);
        assertEquals("LOCATION_BASED", locationRecord.path("scope2Type").asText());
        assertEquals("MARKET_BASED", marketRecord.path("scope2Type").asText());
        assertEquals("MARKET_BASED", greenRecord.path("scope2Type").asText());

        // distinct perspective figures — location ≠ market by construction
        assertWire("0.216536", scope1Record, "co2eTonnes");
        assertWire("0.399987", locationRecord, "co2eTonnes");
        assertWire("0.459496", marketRecord, "co2eTonnes");
        assertWire("0", greenRecord, "co2eTonnes");
        assertNotEquals(0, decimal(locationRecord, "co2eTonnes")
                        .compareTo(decimal(marketRecord, "co2eTonnes")),
                "LOCATION and MARKET must be different figures");

        Api ledger = getJson("/api/v1/emissions?periodId=" + scope[0], token);
        assertEquals(200, ledger.status(), ledger.body().toString());
        JsonNode records = ledger.body().path("data").path("records");
        assertEquals(4, records.size());

        JsonNode summary = ledger.body().path("data").path("summary");
        assertWire("0.2165", summary, "scope1Tonnes");
        assertWire("0.4", summary, "scope2LocationTonnes");
        assertWire("0.4595", summary, "scope2MarketTonnes");
        assertWire("0.6165", summary, "totalLocationBasedTonnes");
        assertWire("0.676", summary, "totalMarketBasedTonnes");

        // each total is exactly its own perspective sum ...
        BigDecimal scope1T = decimal(summary, "scope1Tonnes");
        BigDecimal locationT = decimal(summary, "scope2LocationTonnes");
        BigDecimal marketT = decimal(summary, "scope2MarketTonnes");
        assertEquals(0, decimal(summary, "totalLocationBasedTonnes")
                .compareTo(scope1T.add(locationT)));
        assertEquals(0, decimal(summary, "totalMarketBasedTonnes")
                .compareTo(scope1T.add(marketT)));
        // ... and the two perspectives are never added together
        assertNotEquals(0, decimal(summary, "totalLocationBasedTonnes")
                        .compareTo(scope1T.add(locationT).add(marketT)),
                "location + market must never be summed into one total");
        assertNotEquals(0, decimal(summary, "totalLocationBasedTonnes")
                .compareTo(decimal(summary, "totalMarketBasedTonnes")));
    }

    @Test
    void greenTariffContributesZeroWithoutDistortingPerspectives() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Green");
        JsonNode green = createActivity(token, scope[0], scope[1],
                "SCOPE_2", "ELECTRICITY_MARKET", "GREEN_POWER_TARIFF", "1000", "kWh");

        JsonNode record = runRecord(token, green);
        assertWire("0", record, "co2eTonnes");
        assertEquals("MARKET_BASED", record.path("scope2Type").asText());

        // composite path: one CO2e row with GWP 1 instead of a gas split
        JsonNode calc = getJson("/api/v1/calculations/"
                + record.path("calculationId").asText(), token)
                .body().path("data");
        assertEquals(1, calc.path("gasResults").size());
        assertEquals("CO2e_COMPOSITE", calc.path("gasResults").get(0)
                .path("gas").asText());
        assertWire("1", calc.path("gasResults").get(0), "gwpApplied");

        Api ledger = getJson("/api/v1/emissions?periodId=" + scope[0], token);
        JsonNode summary = ledger.body().path("data").path("summary");
        assertWire("0", summary, "scope1Tonnes");
        assertWire("0", summary, "scope2LocationTonnes");
        assertWire("0", summary, "scope2MarketTonnes");
        assertWire("0", summary, "totalLocationBasedTonnes");
        assertWire("0", summary, "totalMarketBasedTonnes");
    }

    @Test
    void everyScope2RowIsClassifiedAndNothingElseCarriesAPerspective() {
        Integer violations = jdbc.queryForObject(
                "SELECT count(*) FROM emission_records "
                        + "WHERE (scope = 'SCOPE_2' AND scope2_type IS NULL) "
                        + "   OR (scope <> 'SCOPE_2' AND scope2_type IS NOT NULL)",
                Integer.class);
        assertEquals(0, violations == null ? 1 : violations,
                "every SCOPE_2 emission record must be classified and only those");
    }

    private JsonNode runRecord(String token, JsonNode activity) throws Exception {
        Api run = runCalculation(token, activity.path("id").asText());
        assertEquals(200, run.status(), run.body().toString());
        JsonNode record = run.body().path("data").path("emissionRecord");
        assertNotNull(record.path("id"));
        return record;
    }
}
