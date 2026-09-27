package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 6 — {@code POST /calculations/batch-run}: per-item independence with
 * skip semantics (unresolvable references and unsupported units are reported
 * as unprocessed), Node's batch error contract and the create permission.
 */
class CalculationBatchTest extends AccountingTestBase {

    @Test
    void batchProcessesResolvableAndSkipsUnresolvable() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Batch");
        JsonNode valid = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        JsonNode noFactor = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "UNICORN_FUEL", "100", "kWh");
        JsonNode oddUnit = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "100", "Furlongs");

        Api batch = batchCalculation(token, scope[0]);
        assertEquals(200, batch.status(), batch.body().toString());
        assertEquals("Batch calculation completed for 1 items.",
                batch.body().path("message").asText());
        assertEquals(1, batch.body().path("data").path("processed").asInt(),
                batch.body().toString());
        assertEquals(3, batch.body().path("data").path("total").asInt());

        // processed row calculated; skipped rows untouched with exactly one ledger row
        assertEquals("CALCULATED", statusOf(valid));
        assertEquals("SUBMITTED", statusOf(noFactor));
        assertEquals("SUBMITTED", statusOf(oddUnit));

        Api ledger = getJson("/api/v1/emissions?periodId=" + scope[0], token);
        assertEquals(200, ledger.status(), ledger.body().toString());
        assertEquals(1, ledger.body().path("data").path("records").size());
    }

    @Test
    void batchRepeatRunSupersedesInsteadOfDuplicating() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Batch2");
        createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");

        assertEquals(1, batchCalculation(token, scope[0]).body()
                .path("data").path("processed").asInt());
        Api second = batchCalculation(token, scope[0]);
        assertEquals(200, second.status(), second.body().toString());
        assertEquals(1, second.body().path("data").path("processed").asInt());
        assertEquals(1, second.body().path("data").path("total").asInt());

        // still exactly one ACTIVE record — the batch supersedes, not duplicates
        Api ledger = getJson("/api/v1/emissions?periodId=" + scope[0], token);
        assertEquals(1, ledger.body().path("data").path("records").size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM emission_records WHERE reporting_period_id = ?::uuid "
                        + "AND status = 'ACTIVE'",
                Integer.class, scope[0]),
                "second run must supersede, not insert a second ACTIVE row");
    }

    @Test
    void batchErrorContractMatchesNode() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);

        Api nonString = postJson("/api/v1/calculations/batch-run", token,
                "{\"reportingPeriodId\":123}");
        assertEnvelope(400, "VALIDATION_ERROR", "reportingPeriodId must be a string.",
                nonString);

        Api malformed = postJson("/api/v1/calculations/batch-run", token,
                "{\"reportingPeriodId\":\"not-a-uuid\"}");
        assertEnvelope(400, "VALIDATION_ERROR", "Batch calculation input is invalid.",
                malformed);

        Api nilUuid = postJson("/api/v1/calculations/batch-run", token,
                "{\"reportingPeriodId\":\"00000000-0000-0000-0000-000000000000\"}");
        assertEnvelope(400, "VALIDATION_ERROR", "Batch calculation input is invalid.",
                nilUuid);
    }

    @Test
    void batchRequiresTheCreatePermission() throws Exception {
        String auditor = loginToken(AUDITOR_EMAIL, PASSWORD);
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);

        assertEnvelope(403, "FORBIDDEN", "Insufficient permissions for this operation.",
                postJson("/api/v1/calculations/batch-run", auditor,
                        "{\"reportingPeriodId\":\""
                                + java.util.UUID.randomUUID() + "\"}"));
        assertEnvelope(403, "FORBIDDEN", "Insufficient permissions for this operation.",
                postJson("/api/v1/calculations/batch-run", platform,
                        "{\"reportingPeriodId\":\""
                                + java.util.UUID.randomUUID() + "\"}"));
        assertEnvelope(401, "UNAUTHORIZED",
                "Missing or malformed Authorization header or token query parameter.",
                postJson("/api/v1/calculations/batch-run", null, "{}"));
    }

    private String statusOf(JsonNode activity) {
        return jdbc.queryForObject(
                "SELECT status FROM activity_data WHERE id = ?::uuid",
                String.class, activity.path("id").asText());
    }
}
