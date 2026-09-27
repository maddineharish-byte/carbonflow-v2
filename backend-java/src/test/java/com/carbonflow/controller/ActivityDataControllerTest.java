package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 — activity data as the canonical entry point of the flow:
 * Node's production create/list contract plus the greenfield PUT/submit
 * (API.md §2.3), tenant scoping, the strict uuid contract and the §2.3
 * permission matrix.
 */
class ActivityDataControllerTest extends AccountingTestBase {

    // ==========================================
    // POST /activity-data
    // ==========================================

    @Test
    void createRegistersSubmittedRecord() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Create");

        Api api = postJson("/api/v1/activity-data", token,
                activityBody(scope[0], scope[1], "SCOPE_1", "Stationary Combustion",
                        "NATURAL_GAS", "1000", "kWh"));
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Activity data registered.", api.body().path("message").asText());

        JsonNode created = api.body().path("data");
        assertFalse(created.path("id").asText().isBlank());
        assertEquals("SUBMITTED", created.path("status").asText());
        assertEquals("SCOPE_1", created.path("scope").asText());
        assertEquals("NATURAL_GAS", created.path("activityType").asText());
        assertEquals("Stationary Combustion", created.path("category").asText());
        assertEquals("kWh", created.path("unit").asText());
        assertEquals("2043-01-01", created.path("startDate").asText());
        assertEquals("2043-12-31", created.path("endDate").asText());
        assertEquals("Phase 6 test fixture", created.path("source").asText());
        assertEquals(scope[0], created.path("reportingPeriodId").asText());
        assertEquals(scope[1], created.path("facilityId").asText());
        assertWire("1000", created, "quantity");
    }

    @Test
    void createRejectsInvalidPayloads() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Invalid");
        String valid = activityBody(scope[0], scope[1], "SCOPE_1",
                "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");

        assertCreateRejected(token, "{}");
        assertCreateRejected(token, valid.replace("\"quantity\":1000,", ""));
        assertCreateRejected(token, valid.replace("\"quantity\":1000", "\"quantity\":-5"));
        assertCreateRejected(token, valid.replace("\"quantity\":1000", "\"quantity\":1.23456"));
        assertCreateRejected(token, valid.replace("\"quantity\":1000", "\"quantity\":1234567890123"));
        // bounded deviations (ADR-017): no JS coercion of null/booleans
        assertCreateRejected(token, valid.replace("\"quantity\":1000", "\"quantity\":null"));
        assertCreateRejected(token, valid.replace("\"quantity\":1000", "\"quantity\":true"));
        assertCreateRejected(token,
                valid.replace("\"scope\":\"SCOPE_1\"", "\"scope\":\"SCOPE_4\""));
        assertCreateRejected(token, valid.replaceFirst("\\}$", ",\"status\":\"DRAFT\"}"));
        assertCreateRejected(token,
                valid.replace("\"endDate\":\"2043-12-31\"", "\"endDate\":\"2042-12-31\""));
        assertCreateRejected(token,
                valid.replace("\"category\":\"Stationary Combustion\"", "\"category\":42"));
        assertCreateRejected(token, valid.replace("\"activityType\":\"NATURAL_GAS\"", "\"activityType\":\"\""));
        assertCreateRejected(token, valid.replace(",\"source\":\"Phase 6 test fixture\"", ""));
        // strict uuid contract (Node's UUID_PATTERN): version-0 ids are invalid input
        assertCreateRejected(token,
                valid.replace(scope[0], "00000000-0000-0000-0000-000000000000"));
        assertCreateRejected(token, valid.replace(scope[0], "not-a-uuid"));
    }

    @Test
    void createRejectsCrossTenantAnchors() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String apex = loginToken(APEX_EMAIL, PASSWORD);
        String[] mine = freshScope(token, "Anchor");
        String[] theirs = freshScope(apex, "Anchor");

        String exact = "Facility and reporting period must belong to the "
                + "authenticated organization.";

        Api foreignPeriod = postJson("/api/v1/activity-data", token,
                activityBody(theirs[0], mine[1], "SCOPE_1", "Stationary Combustion",
                        "NATURAL_GAS", "1000", "kWh"));
        assertEnvelope(400, "INVALID_ACTIVITY_RELATIONSHIP", exact, foreignPeriod);

        Api foreignFacility = postJson("/api/v1/activity-data", token,
                activityBody(mine[0], theirs[1], "SCOPE_1", "Stationary Combustion",
                        "NATURAL_GAS", "1000", "kWh"));
        assertEnvelope(400, "INVALID_ACTIVITY_RELATIONSHIP", exact, foreignFacility);
    }

    private void assertCreateRejected(String token, String body) throws Exception {
        Api api = postJson("/api/v1/activity-data", token, body);
        assertEnvelope(400, "VALIDATION_ERROR", "Activity data is invalid.", api);
    }

    // ==========================================
    // GET /activity-data
    // ==========================================

    @Test
    void listFiltersByPeriodFacilityScopeAndCarriesLatestCalculation() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "List");
        JsonNode stationary = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        JsonNode electricity = createActivity(token, scope[0], scope[1],
                "SCOPE_2", "ELECTRICITY_LOCATION", "GRID_ELECTRICITY_US", "500", "kWh");

        Api byPeriod = getJson("/api/v1/activity-data?periodId=" + scope[0], token);
        assertEquals(200, byPeriod.status(), byPeriod.body().toString());
        JsonNode rows = byPeriod.body().path("data");
        assertEquals(2, rows.size());
        assertNotNull(findById(rows, stationary.path("id").asText()));
        assertNotNull(findById(rows, electricity.path("id").asText()));

        // calculating one row surfaces its latest calculation in the list
        Api run = runCalculation(token, stationary.path("id").asText());
        assertEquals(200, run.status(), run.body().toString());
        String calculationId = run.body().path("data").path("calculation").path("id").asText();

        Api scope1Only = getJson(
                "/api/v1/activity-data?periodId=" + scope[0] + "&scope=SCOPE_1", token);
        assertEquals(200, scope1Only.status());
        JsonNode only = scope1Only.body().path("data");
        assertEquals(1, only.size());
        assertEquals(calculationId, only.get(0).path("calculation").path("id").asText());

        Api byFacility = getJson("/api/v1/activity-data?facilityId=" + scope[1], token);
        assertEquals(200, byFacility.status());
        assertEquals(2, byFacility.body().path("data").size());

        // the untouched row has no calculation yet
        Api all = getJson("/api/v1/activity-data?periodId=" + scope[0], token);
        JsonNode untouched = findById(all.body().path("data"),
                electricity.path("id").asText());
        assertNotNull(untouched);
        assertTrue(untouched.path("calculation").path("id").asText().isBlank(),
                "no calculation expected yet: " + untouched.path("calculation"));
    }

    @Test
    void listRejectsMalformedFiltersAndTreatsEmptyAsUnfiltered() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String exact = "Activity persistence is temporarily unavailable.";

        assertEnvelope(400, "VALIDATION_ERROR", exact,
                getJson("/api/v1/activity-data?periodId=not-a-uuid", token));
        assertEnvelope(400, "VALIDATION_ERROR", exact,
                getJson("/api/v1/activity-data?periodId=00000000-0000-0000-0000-000000000000",
                        token));
        assertEnvelope(400, "VALIDATION_ERROR", exact,
                getJson("/api/v1/activity-data?facilityId=nil-uuid", token));
        assertEnvelope(400, "VALIDATION_ERROR", exact,
                getJson("/api/v1/activity-data?scope=SCOPE_9", token));
        // empty scope is not a supported scope (Node: SUPPORTED_SCOPES.has("") false)
        assertEnvelope(400, "VALIDATION_ERROR", exact,
                getJson("/api/v1/activity-data?scope=", token));
        // empty period/facility are falsy in Node → no filter, 200
        assertEquals(200, getJson("/api/v1/activity-data?periodId=", token).status());
        assertEquals(200, getJson("/api/v1/activity-data?facilityId=", token).status());
    }

    // ==========================================
    // PUT /activity-data/:id (greenfield)
    // ==========================================

    @Test
    void updateIsPartialImmutableAndValidated() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] mine = freshScope(token, "Update");
        JsonNode activity = createActivity(token, mine[0], mine[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        String id = activity.path("id").asText();

        // notes round-trip, empty string clears the nullable column
        Api notes = putJson("/api/v1/activity-data/" + id, token,
                "{\"notes\":\"meter read 42\"}");
        assertEquals(200, notes.status(), notes.body().toString());
        assertEquals("meter read 42", notes.body().path("data").path("notes").asText());

        Api cleared = putJson("/api/v1/activity-data/" + id, token, "{\"notes\":\"\"}");
        assertEquals(200, cleared.status());
        JsonNode clearedNotes = cleared.body().path("data").path("notes");
        assertTrue(clearedNotes.isNull() || clearedNotes.isMissingNode(),
                "empty notes must clear the field: " + clearedNotes);

        Api quantity = putJson("/api/v1/activity-data/" + id, token, "{\"quantity\":250}");
        assertEquals(200, quantity.status(), quantity.body().toString());
        assertWire("250", quantity.body().path("data"), "quantity");

        // tenant anchors are immutable — unknown payload fields are never applied
        String apex = loginToken(APEX_EMAIL, PASSWORD);
        String[] theirs = freshScope(apex, "Update");
        Api anchors = putJson("/api/v1/activity-data/" + id, token,
                "{\"facilityId\":\"" + theirs[1] + "\",\"reportingPeriodId\":\""
                        + theirs[0] + "\"}");
        assertEquals(200, anchors.status(), anchors.body().toString());
        assertEquals(mine[0], anchors.body().path("data").path("reportingPeriodId").asText());
        assertEquals(mine[1], anchors.body().path("data").path("facilityId").asText());

        // editable statuses pass; terminal ones are rejected
        assertEquals(200, putJson("/api/v1/activity-data/" + id, token,
                "{\"status\":\"DRAFT\"}").status());
        Api validated = putJson("/api/v1/activity-data/" + id, token,
                "{\"status\":\"VALIDATED\"}");
        assertEquals(200, validated.status());
        assertEquals("VALIDATED", validated.body().path("data").path("status").asText());
        assertEnvelope(400, "VALIDATION_ERROR", "Activity data is invalid.",
                putJson("/api/v1/activity-data/" + id, token, "{\"status\":\"CALCULATED\"}"));
        assertEnvelope(400, "VALIDATION_ERROR", "Activity data is invalid.",
                putJson("/api/v1/activity-data/" + id, token, "{\"status\":\"LOCKED\"}"));

        // date inversion and malformed department rejected
        assertEnvelope(400, "VALIDATION_ERROR", "Activity data is invalid.",
                putJson("/api/v1/activity-data/" + id, token,
                        "{\"endDate\":\"2042-12-31\"}"));
        assertEnvelope(400, "VALIDATION_ERROR", "Activity data is invalid.",
                putJson("/api/v1/activity-data/" + id, token,
                        "{\"departmentId\":\"not-a-uuid\"}"));
    }

    @Test
    void updateAndSubmitCollapseForeignAndMalformedIdsInto404() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String apex = loginToken(APEX_EMAIL, PASSWORD);
        String[] theirs = freshScope(apex, "Foreign");
        JsonNode foreign = createActivity(apex, theirs[0], theirs[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");

        String exact = "Activity data not found.";
        assertEnvelope(404, "ACTIVITY_NOT_FOUND", exact,
                putJson("/api/v1/activity-data/" + foreign.path("id").asText(), token,
                        "{\"notes\":\"x\"}"));
        assertEnvelope(404, "ACTIVITY_NOT_FOUND", exact,
                postJson("/api/v1/activity-data/" + foreign.path("id").asText() + "/submit",
                        token, "{}"));
        assertEnvelope(404, "ACTIVITY_NOT_FOUND", exact,
                putJson("/api/v1/activity-data/not-a-uuid", token, "{\"notes\":\"x\"}"));
        assertEnvelope(404, "ACTIVITY_NOT_FOUND", exact,
                postJson("/api/v1/activity-data/not-a-uuid/submit", token, "{}"));
        assertEnvelope(404, "ACTIVITY_NOT_FOUND", exact,
                putJson("/api/v1/activity-data/00000000-0000-0000-0000-000000000000", token,
                        "{\"notes\":\"x\"}"));
    }

    // ==========================================
    // POST /activity-data/:id/submit (greenfield)
    // ==========================================

    @Test
    void submitIsIdempotentAndStampsTheSubmitter() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Submit");
        JsonNode activity = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        String id = activity.path("id").asText();

        // rewind to DRAFT so the submit transition is observable
        assertEquals(200, putJson("/api/v1/activity-data/" + id, token,
                "{\"status\":\"DRAFT\"}").status());

        Api first = postJson("/api/v1/activity-data/" + id + "/submit", token, "{}");
        assertEquals(200, first.status(), first.body().toString());
        assertEquals("SUBMITTED", first.body().path("data").path("status").asText());

        // idempotent — a second submit is a 200, not a state-machine error
        Api second = postJson("/api/v1/activity-data/" + id + "/submit", token, "{}");
        assertEquals(200, second.status(), second.body().toString());
        assertEquals("SUBMITTED", second.body().path("data").path("status").asText());

        String submittedBy = jdbc.queryForObject(
                "SELECT submitted_by::text FROM activity_data WHERE id = ?::uuid",
                String.class, id);
        assertEquals(com.carbonflow.repository.SeedIds.USER_ACME_ADMIN, submittedBy);
    }

    // ==========================================
    // §2.3 permission matrix
    // ==========================================

    @Test
    void permissionMatrixIsEnforced() throws Exception {
        String admin = loginToken(ADMIN_EMAIL, PASSWORD);
        String auditor = loginToken(AUDITOR_EMAIL, PASSWORD);
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);
        String[] scope = freshScope(admin, "Rbac");
        JsonNode activity = createActivity(admin, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        String id = activity.path("id").asText();
        String payload = activityBody(scope[0], scope[1], "SCOPE_1",
                "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");

        // PLATFORM_ADMIN holds no activity_data.* permission at all
        assertEquals(403, getJson("/api/v1/activity-data", platform).status());
        assertEquals(403, postJson("/api/v1/activity-data", platform, payload).status());
        assertEquals(403, putJson("/api/v1/activity-data/" + id, platform,
                "{\"notes\":\"x\"}").status());
        assertEquals(403, postJson("/api/v1/activity-data/" + id + "/submit", platform,
                "{}").status());

        // ASSURANCE_PROVIDER may read but not write
        assertEquals(200, getJson("/api/v1/activity-data?periodId=" + scope[0],
                auditor).status());
        assertEquals(403, postJson("/api/v1/activity-data", auditor, payload).status());
        assertEquals(403, putJson("/api/v1/activity-data/" + id, auditor,
                "{\"notes\":\"x\"}").status());
        assertEquals(403, postJson("/api/v1/activity-data/" + id + "/submit", auditor,
                "{}").status());

        // unauthenticated
        assertEquals(401, getJson("/api/v1/activity-data", null).status());
        assertEquals(401, postJson("/api/v1/activity-data", null, payload).status());

        // forbidden envelope carries the Node code
        Api forbidden = getJson("/api/v1/activity-data", platform);
        assertEquals(403, forbidden.status());
        assertEquals("FORBIDDEN", forbidden.errorCode());
    }
}
