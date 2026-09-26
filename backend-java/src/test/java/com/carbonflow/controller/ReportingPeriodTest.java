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
 * Reporting-period management: Node's list/create semantics
 * ({@code ORDER BY start_date DESC}, {@code INVALID_DATE_RANGE} for inverted
 * ranges), tenant isolation of the greenfield get/update verbs, V1
 * {@code status} lifecycle validation, the documented non-restriction on
 * overlapping periods, and the absence of a delete permission (405).
 */
class ReportingPeriodTest extends PostgresBackedIntegrationTest {

    private static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode createPeriod(String token, String name, String start, String end)
            throws Exception {
        Api api = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"" + name + "\",\"startDate\":\"" + start + "\","
                        + "\"endDate\":\"" + end + "\"}");
        assertEquals(201, api.status(), "period create must succeed: " + api.body());
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
    void createReturns201WithDefaultsAndNodeMessage() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        Api api = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"FY2030 " + sfx + "\",\"startDate\":\"2030-01-01\","
                        + "\"endDate\":\"2030-12-31\",\"status\":\"OPEN\"}");
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Reporting period created.", api.body().path("message").asText());

        JsonNode data = api.body().get("data");
        assertEquals(SeedIds.ORG_ACME, data.get("organizationId").asText());
        assertEquals("2030-01-01", data.get("startDate").asText(),
                "calendar dates serialize as ISO YYYY-MM-DD, like the Node record");
        assertEquals("2030-12-31", data.get("endDate").asText());
        assertEquals("OPEN", data.get("status").asText());
        assertTrue(data.get("createdAt").asText().length() > 0);
        assertTrue(data.get("updatedAt").asText().length() > 0);

        // Status defaults to OPEN when omitted.
        JsonNode defaulted = createPeriod(token, "Default Status " + sfx,
                "2031-01-01", "2031-12-31");
        assertEquals("OPEN", defaulted.get("status").asText());
    }

    @Test
    void validationMatchesTheNodeMatrix() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        Api missing = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"No Dates " + sfx + "\"}");
        assertEquals(400, missing.status());
        assertEquals("VALIDATION_ERROR", missing.errorCode());
        assertEquals("Period name, startDate, and endDate are required.",
                missing.errorMessage());

        Api badDate = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"Bad Date " + sfx + "\",\"startDate\":\"last tuesday\","
                        + "\"endDate\":\"2030-12-31\"}");
        assertEquals(400, badDate.status());
        assertEquals("VALIDATION_ERROR", badDate.errorCode());
        assertEquals("Reporting period data is invalid.", badDate.errorMessage());

        Api inverted = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"Inverted " + sfx + "\",\"startDate\":\"2030-12-31\","
                        + "\"endDate\":\"2030-01-01\"}");
        assertEquals(400, inverted.status());
        assertEquals("INVALID_DATE_RANGE", inverted.errorCode());
        assertEquals("endDate must be on or after startDate.", inverted.errorMessage());

        Api badStatus = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"Bad Status " + sfx + "\",\"startDate\":\"2030-01-01\","
                        + "\"endDate\":\"2030-12-31\",\"status\":\"ARCHIVED\"}");
        assertEquals(400, badStatus.status());
        assertEquals("VALIDATION_ERROR", badStatus.errorCode());
        assertTrue(badStatus.errorMessage().contains("status"));

        String overlong = "P".repeat(101);
        Api nameTooLong = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"" + overlong + "\",\"startDate\":\"2030-01-01\","
                        + "\"endDate\":\"2030-12-31\"}");
        assertEquals(400, nameTooLong.status());
        assertEquals("VALIDATION_ERROR", nameTooLong.errorCode());
    }

    @Test
    void overlappingPeriodsArePermittedBecauseNoRuleExists() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        // Evidence-based: neither schema (only end_date >= start_date), Node,
        // nor the documentation restricts overlaps — assert they stay allowed.
        JsonNode first = createPeriod(token, "Overlap One " + sfx,
                "2032-01-01", "2032-06-30");
        JsonNode second = createPeriod(token, "Overlap Two " + sfx,
                "2032-03-01", "2032-12-31");
        assertTrue(listContains(
                getJson("/api/v1/reporting-periods", token).body().get("data"),
                first.get("id").asText()));
        assertTrue(listContains(
                getJson("/api/v1/reporting-periods", token).body().get("data"),
                second.get("id").asText()));

        // Equal start/end (a single-day period) satisfies end >= start too.
        createPeriod(token, "Single Day " + sfx, "2033-05-05", "2033-05-05");
    }

    @Test
    void listIsTenantScopedAndNewestPeriodFirst() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode older = createPeriod(acme, "Older " + sfx, "2020-01-01", "2020-12-31");
        JsonNode newer = createPeriod(acme, "Newer " + sfx, "2035-01-01", "2035-12-31");
        JsonNode foreign = createPeriod(apex, "Apex Period " + sfx, "2036-01-01", "2036-12-31");

        JsonNode list = getJson("/api/v1/reporting-periods", acme).body().get("data");
        assertTrue(listContains(list, older.get("id").asText()));
        assertTrue(listContains(list, newer.get("id").asText()));
        assertFalse(listContains(list, foreign.get("id").asText()),
                "another tenant's reporting period must never be listed");

        int olderIndex = -1;
        int newerIndex = -1;
        int index = 0;
        for (JsonNode item : list) {
            if (item.get("id").asText().equals(older.get("id").asText())) olderIndex = index;
            if (item.get("id").asText().equals(newer.get("id").asText())) newerIndex = index;
            index++;
        }
        assertTrue(newerIndex < olderIndex, "Node parity: ORDER BY start_date DESC");
    }

    @Test
    void crossTenantReadAndUpdateAreRejected() throws Exception {
        String acme = loginToken("admin@acmeglobal.com", PASSWORD);
        String apex = loginToken("admin@apexcorp.com", PASSWORD);
        String sfx = suffix();

        JsonNode foreign = createPeriod(apex, "Apex Vault " + sfx,
                "2037-01-01", "2037-12-31");
        String foreignId = foreign.get("id").asText();

        Api get = getJson("/api/v1/reporting-periods/" + foreignId, acme);
        assertEquals(404, get.status());
        assertEquals("REPORTING_PERIOD_NOT_FOUND", get.errorCode());
        assertEquals("Reporting period does not exist or access denied.", get.errorMessage());

        Api put = putJson("/api/v1/reporting-periods/" + foreignId, acme,
                "{\"status\":\"LOCKED\"}");
        assertEquals(404, put.status());

        JsonNode intact = getJson("/api/v1/reporting-periods/" + foreignId, apex)
                .body().get("data");
        assertEquals("OPEN", intact.get("status").asText(),
                "cross-tenant writes must not mutate the row");
        assertEquals("Apex Vault " + sfx, intact.get("name").asText());
    }

    @Test
    void updateTransitionsStatusAndRejectsHalfOpenDatePairs() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(token, "Lifecycle " + sfx, "2038-01-01", "2038-12-31");
        String id = period.get("id").asText();

        Api transition = putJson("/api/v1/reporting-periods/" + id, token,
                "{\"status\":\"LOCKED\"}");
        assertEquals(200, transition.status(), transition.body().toString());
        assertEquals("Reporting period updated.", transition.body().path("message").asText());
        assertEquals("LOCKED", transition.body().get("data").get("status").asText());
        assertEquals("Lifecycle " + sfx, transition.body().get("data").get("name").asText(),
                "unspecified fields stay untouched");

        // Any V1-valid status is accepted (no transition graph is evidenced yet).
        assertEquals(200, putJson("/api/v1/reporting-periods/" + id, token,
                "{\"status\":\"UNDER_AUDIT\"}").status());

        Api badStatus = putJson("/api/v1/reporting-periods/" + id, token,
                "{\"status\":\"ARCHIVED\"}");
        assertEquals(400, badStatus.status());
        assertEquals("VALIDATION_ERROR", badStatus.errorCode());

        Api halfPair = putJson("/api/v1/reporting-periods/" + id, token,
                "{\"startDate\":\"2038-02-01\"}");
        assertEquals(400, halfPair.status());
        assertEquals("VALIDATION_ERROR", halfPair.errorCode());
        assertEquals("startDate and endDate must be updated together.",
                halfPair.errorMessage());

        Api inverted = putJson("/api/v1/reporting-periods/" + id, token,
                "{\"startDate\":\"2038-12-31\",\"endDate\":\"2038-01-01\"}");
        assertEquals(400, inverted.status());
        assertEquals("INVALID_DATE_RANGE", inverted.errorCode());

        JsonNode persisted = getJson("/api/v1/reporting-periods/" + id, token)
                .body().get("data");
        assertEquals("UNDER_AUDIT", persisted.get("status").asText());
        assertEquals("2038-01-01", persisted.get("startDate").asText(),
                "failed updates must leave the stored dates intact");
    }

    @Test
    void deleteVerbIsNotRoutedBecauseNoDeletePermissionExists() throws Exception {
        String token = loginToken("admin@acmeglobal.com", PASSWORD);
        String sfx = suffix();

        JsonNode period = createPeriod(token, "No Delete " + sfx, "2039-01-01", "2039-12-31");
        String id = period.get("id").asText();

        Api del = deleteJson("/api/v1/reporting-periods/" + id, token);
        assertEquals(405, del.status(),
                "the frozen 44-code matrix has no reporting_periods.delete — the endpoint does not exist");

        assertEquals(200, getJson("/api/v1/reporting-periods/" + id, token).status(),
                "the period must survive the rejected verb");
    }

    @Test
    void permissionGatesFollowTheFrozenRoleMatrix() throws Exception {
        String admin = loginToken("admin@acmeglobal.com", PASSWORD);
        String manager = loginToken("manager@acmeglobal.com", PASSWORD);
        String auditor = loginToken("auditor@ey-assurance.com", PASSWORD);
        String sfx = suffix();

        // SUSTAINABILITY_MANAGER holds reporting_periods.create + update.
        JsonNode managerPeriod = createPeriod(manager, "Manager Period " + sfx,
                "2040-01-01", "2040-12-31");
        assertEquals(200, putJson("/api/v1/reporting-periods/"
                + managerPeriod.get("id").asText(), manager, "{\"status\":\"UNDER_AUDIT\"}").status());

        // ASSURANCE_PROVIDER: read-only …
        assertEquals(200, getJson("/api/v1/reporting-periods", auditor).status());
        Api auditorCreate = postJson("/api/v1/reporting-periods", auditor,
                "{\"name\":\"Auditor Period\",\"startDate\":\"2041-01-01\","
                        + "\"endDate\":\"2041-12-31\"}");
        assertEquals(403, auditorCreate.status());
        assertEquals("FORBIDDEN", auditorCreate.errorCode());

        // … and COMPANY_ADMIN remains able to create + delete-by-status.
        assertEquals(201, postJson("/api/v1/reporting-periods", admin,
                "{\"name\":\"Admin Period " + sfx + "\",\"startDate\":\"2042-01-01\","
                        + "\"endDate\":\"2042-12-31\"}").status());
        assertEquals(200, putJson("/api/v1/reporting-periods/"
                + managerPeriod.get("id").asText(), admin, "{\"status\":\"LOCKED\"}").status());
    }
}
