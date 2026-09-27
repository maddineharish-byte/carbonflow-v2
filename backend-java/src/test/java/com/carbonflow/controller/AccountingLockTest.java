package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 6 — the Phase 5 audit lock carries into accounting (ADR-017): once an
 * audit drives its reporting period to LOCKED, every accounting mutation is
 * rejected with {@code 409 AUDIT_LOCKED} and batch runs skip the frozen period
 * instead of mutating audited history.
 */
class AccountingLockTest extends AccountingTestBase {

    private static final String LOCK_MESSAGE =
            "Reporting period is locked and cannot be modified.";

    @Test
    void lockedPeriodFreezesEveryAccountingMutation() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Lock");
        JsonNode activity = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        String activityId = activity.path("id").asText();

        // Phase 5: drive a fresh audit to LOCKED (freezes its reporting period)
        String auditor = loginToken(AUDITOR_EMAIL, PASSWORD);
        JsonNode audit = createAudit(token, scope[0]);
        String auditId = audit.path("id").asText();
        driveToReview(token, auditId);
        verifyAllChecklist(auditor, auditId);
        assertEquals(200, transition(token, auditId, "APPROVED", null).status());
        assertEquals(200, transition(token, auditId, "AUDIT_READY", null).status());
        assertEquals(200, transition(token, auditId, "LOCKED", null).status());
        assertEquals("LOCKED", jdbc.queryForObject(
                "SELECT status FROM reporting_periods WHERE id = ?::uuid",
                String.class, scope[0]));

        // create → 409
        Api create = postJson("/api/v1/activity-data", token,
                activityBody(scope[0], scope[1], "SCOPE_1", "Stationary Combustion",
                        "NATURAL_GAS", "500", "kWh"));
        assertEnvelope(409, "AUDIT_LOCKED", LOCK_MESSAGE, create);

        // run → 409 (checked after the tenant-scoped 404, before resolution)
        assertEnvelope(409, "AUDIT_LOCKED", LOCK_MESSAGE,
                runCalculation(token, activityId));

        // greenfield PUT + submit → 409
        assertEnvelope(409, "AUDIT_LOCKED", LOCK_MESSAGE,
                putJson("/api/v1/activity-data/" + activityId, token,
                        "{\"notes\":\"late edit\"}"));
        assertEnvelope(409, "AUDIT_LOCKED", LOCK_MESSAGE,
                postJson("/api/v1/activity-data/" + activityId + "/submit", token, "{}"));

        // batch skips the frozen period instead of failing the request
        Api batch = batchCalculation(token, scope[0]);
        assertEquals(200, batch.status(), batch.body().toString());
        assertEquals("Batch calculation completed for 0 items.",
                batch.body().path("message").asText());
        assertEquals(0, batch.body().path("data").path("processed").asInt());
        assertEquals(1, batch.body().path("data").path("total").asInt());

        // nothing was written — history stays exactly as the lock found it
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT status FROM activity_data WHERE id = ?::uuid",
                String.class, activityId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM calculations WHERE activity_data_id = ?::uuid",
                Integer.class, activityId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM emission_records WHERE reporting_period_id = ?::uuid",
                Integer.class, scope[0]));
    }

    @Test
    void readsRemainAvailableOnLockedPeriods() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "LockRead");
        JsonNode activity = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        Api run = runCalculation(token, activity.path("id").asText());
        assertEquals(200, run.status(), run.body().toString());

        JsonNode audit = createAudit(token, scope[0]);
        String auditId = audit.path("id").asText();
        driveToReview(token, auditId);
        verifyAllChecklist(loginToken(AUDITOR_EMAIL, PASSWORD), auditId);
        assertEquals(200, transition(token, auditId, "APPROVED", null).status());
        assertEquals(200, transition(token, auditId, "AUDIT_READY", null).status());
        assertEquals(200, transition(token, auditId, "LOCKED", null).status());

        // the frozen calculation stays readable with its full snapshot
        String calculationId = run.body().path("data").path("calculation")
                .path("id").asText();
        Api read = getJson("/api/v1/calculations/" + calculationId, token);
        assertEquals(200, read.status(), read.body().toString());
        assertWire("0.216536", read.body().path("data"), "totalCo2eTonnes");

        // and the ledger still lists the active record
        Api ledger = getJson("/api/v1/emissions?periodId=" + scope[0], token);
        assertEquals(200, ledger.status(), ledger.body().toString());
        assertEquals(1, ledger.body().path("data").path("records").size());

        // list filters are read-only too
        assertEquals(200, getJson(
                "/api/v1/activity-data?periodId=" + scope[0], token).status());
    }
}
