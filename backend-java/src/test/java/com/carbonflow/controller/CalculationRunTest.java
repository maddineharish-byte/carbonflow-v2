package com.carbonflow.controller;

import com.carbonflow.model.ActivityData;
import com.carbonflow.model.GwpSet;
import com.carbonflow.repository.ActivityDataRepository;
import com.carbonflow.repository.EmissionFactorRepository;
import com.carbonflow.repository.GwpSetRepository;
import com.carbonflow.repository.SeedIds;
import com.carbonflow.service.AuthException;
import com.carbonflow.service.CalculationPersistence;
import com.carbonflow.service.GhgCalculationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 — {@code POST /calculations/run} and the greenfield
 * {@code GET /calculations/:id}: the canonical flow with full provenance
 * (unit normalization → gas-level results → totals → snapshot hash → emission
 * record), determinism with supersession, the Node error contract, and the
 * transactional rollback guarantee of the persistence layer.
 *
 * <p>Expected values for the seeded NATURAL_GAS factor (AR6):
 * 1000 kWh → CO2 182.54 kg, CH4 0.24 kg × 27.9, N2O 0.1 kg × 273 =
 * 216.536 kg = 0.216536 t.
 */
class CalculationRunTest extends AccountingTestBase {

    @Autowired
    private CalculationPersistence persistence;
    @Autowired
    private EmissionFactorRepository factors;
    @Autowired
    private GwpSetRepository gwpSets;
    @Autowired
    private GhgCalculationEngine engine;
    @Autowired
    private ActivityDataRepository activities;

    // ==========================================
    // Happy path
    // ==========================================

    @Test
    void runExecutesCanonicalFlowWithFullProvenance() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Run");
        JsonNode activity = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        String activityId = activity.path("id").asText();

        Api run = runCalculation(token, activityId);
        assertEquals(200, run.status(), run.body().toString());
        assertEquals("Calculation executed deterministically.",
                run.body().path("message").asText());

        JsonNode calc = run.body().path("data").path("calculation");
        assertEquals(activityId, calc.path("activityDataId").asText());
        assertEquals(scope[0], calc.path("reportingPeriodId").asText());

        // original + normalized quantity, conversion and factor provenance
        assertWire("1000", calc, "originalQuantity");
        assertEquals("kWh", calc.path("originalUnit").asText());
        assertWire("1000", calc, "normalizedQuantity");
        assertEquals("KWH", calc.path("normalizedUnit").asText());
        assertWire("1", calc, "conversionFactor");
        assertWire("0.18288", calc, "factorValue");
        assertEquals("kgCO2e/kWh", calc.path("factorUnit").asText());
        assertEquals("UK DEFRA / BEIS (2024)", calc.path("factorSource").asText());
        assertEquals(1, calc.path("factorVersion").asInt());
        assertEquals("IPCC Sixth Assessment Report (AR6)", calc.path("gwpName").asText());
        assertTrue(calc.path("gwpSetId").asText().length() == 36,
                "gwp set id expected: " + calc.path("gwpSetId"));
        assertTrue(calc.path("factorVersionId").asText().length() == 36);

        // totals accumulated unrounded, rounded only for display
        assertWire("216.536", calc, "totalCo2eKg");
        assertWire("0.216536", calc, "totalCo2eTonnes");
        assertTrue(calc.path("calculationHash").asText().matches("[0-9a-f]{64}"),
                "sha-256 hex expected: " + calc.path("calculationHash").asText());
        assertEquals(SeedIds.USER_ACME_ADMIN, calc.path("calculatedBy").asText());
        assertFalse0(calc.path("calculatedAt"));

        // gas-level results, engine order CO2 → CH4 → N2O, AR6 values applied
        JsonNode gas = calc.path("gasResults");
        assertEquals(3, gas.size());
        assertEquals("CO2", gas.get(0).path("gas").asText());
        assertWire("182.54", gas.get(0), "rawGasEmissionKg");
        assertWire("1", gas.get(0), "gwpApplied");
        assertWire("182.54", gas.get(0), "co2eKg");
        assertEquals("CH4", gas.get(1).path("gas").asText());
        assertWire("0.24", gas.get(1), "rawGasEmissionKg");
        assertWire("27.9", gas.get(1), "gwpApplied");
        assertWire("6.696", gas.get(1), "co2eKg");
        assertEquals("N2O", gas.get(2).path("gas").asText());
        assertWire("0.1", gas.get(2), "rawGasEmissionKg");
        assertWire("273", gas.get(2), "gwpApplied");
        assertWire("27.3", gas.get(2), "co2eKg");

        // emission record — Scope 1 never carries a perspective
        JsonNode record = run.body().path("data").path("emissionRecord");
        assertEquals("ACTIVE", record.path("status").asText());
        assertEquals("SCOPE_1", record.path("scope").asText());
        assertEquals("Stationary Combustion", record.path("category").asText());
        assertTrue(record.path("scope2Type").isMissingNode()
                        || record.path("scope2Type").isNull(),
                "Scope 1 must not carry scope2Type: " + record);
        assertWire("0.216536", record, "co2eTonnes");
        assertEquals(calc.path("id").asText(), record.path("calculationId").asText());

        // the activity transitioned into the calculated state
        assertEquals("CALCULATED", jdbc.queryForObject(
                "SELECT status FROM activity_data WHERE id = ?::uuid",
                String.class, activityId));

        // the audit-snapshot read endpoint returns the same provenance
        Api fetched = getJson("/api/v1/calculations/" + calc.path("id").asText(), token);
        assertEquals(200, fetched.status(), fetched.body().toString());
        JsonNode read = fetched.body().path("data");
        assertWire("0.216536", read, "totalCo2eTonnes");
        assertWire("1000", read, "originalQuantity");
        assertEquals(calc.path("calculationHash").asText(),
                read.path("calculationHash").asText());
        assertEquals("UK DEFRA / BEIS (2024)", read.path("factorSource").asText());
        assertEquals("IPCC Sixth Assessment Report (AR6)", read.path("gwpName").asText());
        assertEquals(3, read.path("gasResults").size());
    }

    private static void assertFalse0(JsonNode node) {
        assertTrue(node != null && !node.isMissingNode() && !node.isNull(),
                "value expected: " + node);
    }

    // ==========================================
    // Determinism, supersession, no historical drift
    // ==========================================

    @Test
    void runIsDeterministicAndSupersedesThePriorRecord() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Determinism");
        JsonNode activity = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        String activityId = activity.path("id").asText();

        Api first = runCalculation(token, activityId);
        Api second = runCalculation(token, activityId);
        assertEquals(200, first.status(), first.body().toString());
        assertEquals(200, second.status(), second.body().toString());

        JsonNode calc1 = first.body().path("data").path("calculation");
        JsonNode calc2 = second.body().path("data").path("calculation");
        assertEquals(calc1.path("calculationHash").asText(),
                calc2.path("calculationHash").asText(),
                "identical inputs must produce the identical snapshot hash");
        assertNotEquals(calc1.path("id").asText(), calc2.path("id").asText());
        assertWire("0.216536", calc2, "totalCo2eTonnes");

        // supersession: exactly one ACTIVE record, the old one audited away
        JsonNode record1 = first.body().path("data").path("emissionRecord");
        JsonNode record2 = second.body().path("data").path("emissionRecord");
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM emission_records WHERE id = ?::uuid",
                String.class, record1.path("id").asText()));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM emission_records WHERE id = ?::uuid",
                String.class, record2.path("id").asText()));

        Api ledger = getJson("/api/v1/emissions?periodId=" + scope[0], token);
        assertEquals(200, ledger.status(), ledger.body().toString());
        JsonNode records = ledger.body().path("data").path("records");
        assertEquals(1, records.size(), "superseded rows never list");
        assertEquals(record2.path("id").asText(), records.get(0).path("id").asText());

        // historical calculations keep their original snapshot — no drift
        Api old = getJson("/api/v1/calculations/" + calc1.path("id").asText(), token);
        assertEquals(200, old.status(), old.body().toString());
        assertWire("0.216536", old.body().path("data"), "totalCo2eTonnes");
        assertWire("1000", old.body().path("data"), "originalQuantity");
        assertEquals("UK DEFRA / BEIS (2024)",
                old.body().path("data").path("factorSource").asText());
    }

    // ==========================================
    // Transactional rollback (service-level)
    // ==========================================

    @Test
    void writeFailureRollsBackTheSupersedeStep() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Rollback");
        JsonNode activity = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        String activityId = activity.path("id").asText();

        Api first = runCalculation(token, activityId);
        assertEquals(200, first.status(), first.body().toString());
        JsonNode calc1 = first.body().path("data").path("calculation");
        String recordId = first.body().path("data").path("emissionRecord")
                .path("id").asText();

        // Re-execute through the engine (proving determinism), then tamper with
        // the calculation id so the INSERT collides AFTER supersedeActive ran —
        // the transaction must roll the flip back.
        ActivityData model = activities
                .findById(SeedIds.ORG_ACME, activityId).orElseThrow();
        EmissionFactorRepository.ResolvedVersion resolved =
                factors.resolveActiveVersion("NATURAL_GAS", null).orElseThrow();
        GwpSet set = gwpSets.resolve(null).orElseThrow();
        GhgCalculationEngine.ResolvedReferences refs =
                new GhgCalculationEngine.ResolvedReferences(
                        resolved.version(), resolved.inputUnit(), set,
                        gwpSets.values(set.getId()));
        GhgCalculationEngine.CalculationOutput output =
                engine.executeCalculation(model, refs, SeedIds.USER_ACME_ADMIN);
        assertEquals(calc1.path("calculationHash").asText(),
                output.calculation().getCalculationHash(),
                "engine re-execution must reproduce the exact snapshot hash");

        output.calculation().setId(calc1.path("id").asText());
        output.emissionRecord().setCalculationId(calc1.path("id").asText());

        AuthException failure = assertThrows(AuthException.class, () ->
                persistence.persist(SeedIds.ORG_ACME, SeedIds.USER_ACME_ADMIN,
                        model, output));
        assertEquals("INVALID_CALCULATION_RELATIONSHIP", failure.getCode());

        // rollback proof: the ACTIVE record the supersede step flipped inside
        // the aborted transaction is ACTIVE again, and nothing new was written
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM emission_records WHERE id = ?::uuid",
                String.class, recordId));
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM emission_records "
                        + "WHERE organization_id = ?::uuid AND reporting_period_id = ?::uuid",
                Integer.class, SeedIds.ORG_ACME, scope[0]);
        assertEquals(1, rows, "failed persist must not leave partial rows");
    }

    // ==========================================
    // Error contract (Node run handler, exact)
    // ==========================================

    @Test
    void runErrorContractMatchesNode() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String apex = loginToken(APEX_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "RunErr");
        String[] foreign = freshScope(apex, "RunErr");
        JsonNode mine = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        JsonNode theirs = createActivity(apex, foreign[0], foreign[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        JsonNode noFactor = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "UNICORN_FUEL", "100", "kWh");
        JsonNode oddUnit = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "100", "Furlongs");

        // missing body / missing / non-string / empty → required-field error
        assertRunError(token, "", 400, "VALIDATION_ERROR", "activityDataId is required.");
        assertRunError(token, "{}", 400, "VALIDATION_ERROR", "activityDataId is required.");
        assertRunError(token, "{\"activityDataId\":123}", 400, "VALIDATION_ERROR",
                "activityDataId is required.");
        assertRunError(token, "{\"activityDataId\":\"\"}", 400, "VALIDATION_ERROR",
                "activityDataId is required.");

        // malformed uuids → Node's assertUuid wording
        assertRunError(token, "{\"activityDataId\":\"not-a-uuid\"}", 400,
                "VALIDATION_ERROR", "Calculation input is invalid.");
        assertRunError(token,
                "{\"activityDataId\":\"00000000-0000-0000-0000-000000000000\"}", 400,
                "VALIDATION_ERROR", "Calculation input is invalid.");

        // unknown / cross-tenant → tenant-scoped 404
        assertRunError(token, "{\"activityDataId\":\"" + UUID.randomUUID() + "\"}", 404,
                "ACTIVITY_NOT_FOUND", "Activity data not found.");
        assertRunError(token, "{\"activityDataId\":\""
                + theirs.path("id").asText() + "\"}", 404,
                "ACTIVITY_NOT_FOUND", "Activity data not found.");

        // reference resolution failures
        assertRunError(token, "{\"activityDataId\":\""
                + noFactor.path("id").asText() + "\"}", 400,
                "FACTOR_NOT_FOUND", "No active emission factor found for activity.");
        assertRunError(token, "{\"activityDataId\":\""
                + mine.path("id").asText() + "\",\"gwpSetId\":\"nope\"}", 400,
                "VALIDATION_ERROR", "Calculation input is invalid.");
        assertRunError(token, "{\"activityDataId\":\""
                + mine.path("id").asText() + "\",\"gwpSetId\":\""
                + UUID.randomUUID() + "\"}", 400,
                "GWP_SET_NOT_FOUND", "The selected GWP set is unavailable.");
        assertRunError(token, "{\"activityDataId\":\""
                + mine.path("id").asText() + "\",\"factorVersionId\":\"nope\"}", 400,
                "VALIDATION_ERROR", "Calculation input is invalid.");
        assertRunError(token, "{\"activityDataId\":\""
                + mine.path("id").asText() + "\",\"factorVersionId\":\""
                + UUID.randomUUID() + "\"}", 400,
                "FACTOR_NOT_FOUND", "No active emission factor found for activity.");

        // documented deviation (ADR-017): an uncovered unit pair reports itself
        // instead of Node's silent factor-of-1 fallback
        assertRunError(token, "{\"activityDataId\":\""
                + oddUnit.path("id").asText() + "\"}", 400, "VALIDATION_ERROR",
                "Unit conversion from Furlongs to kWh is not supported.");
    }

    private void assertRunError(String token, String body, int status, String code,
                                String message) throws Exception {
        Api api = postJson("/api/v1/calculations/run", token, body);
        assertEnvelope(status, code, message, api);
    }

    // ==========================================
    // GET /calculations/:id (greenfield) + permissions
    // ==========================================

    @Test
    void getCalculationIsTenantScopedAndPermissionChecked() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String apex = loginToken(APEX_EMAIL, PASSWORD);
        String auditor = loginToken(AUDITOR_EMAIL, PASSWORD);
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Get");
        JsonNode activity = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");

        Api run = runCalculation(token, activity.path("id").asText());
        assertEquals(200, run.status(), run.body().toString());
        String calculationId = run.body().path("data").path("calculation")
                .path("id").asText();
        String exact404 = "Calculation does not exist or access denied.";

        // ASSURANCE_PROVIDER holds calculations.read
        assertEquals(200, getJson("/api/v1/calculations/" + calculationId,
                auditor).status());
        // PLATFORM_ADMIN does not
        assertEnvelope(403, "FORBIDDEN", "Insufficient permissions for this operation.",
                getJson("/api/v1/calculations/" + calculationId, platform));
        // cross-tenant collapses into the anti-enumeration 404
        assertEnvelope(404, "CALCULATION_NOT_FOUND", exact404,
                getJson("/api/v1/calculations/" + calculationId, apex));
        // malformed + unknown ids collapse the same way
        assertEnvelope(404, "CALCULATION_NOT_FOUND", exact404,
                getJson("/api/v1/calculations/not-a-uuid", token));
        assertEnvelope(404, "CALCULATION_NOT_FOUND", exact404,
                getJson("/api/v1/calculations/" + UUID.randomUUID(), token));
        assertEnvelope(404, "CALCULATION_NOT_FOUND", exact404,
                getJson("/api/v1/calculations/00000000-0000-0000-0000-000000000000",
                        token));

        // run requires calculations.create — the assurance provider lacks it
        assertEnvelope(403, "FORBIDDEN", "Insufficient permissions for this operation.",
                runCalculation(auditor, activity.path("id").asText()));
        // unauthenticated
        assertEnvelope(401, "UNAUTHORIZED",
                "Missing or malformed Authorization header.",
                getJson("/api/v1/calculations/" + calculationId, null));
        assertEnvelope(401, "UNAUTHORIZED",
                "Missing or malformed Authorization header.",
                runCalculation(null, activity.path("id").asText()));
    }
}
