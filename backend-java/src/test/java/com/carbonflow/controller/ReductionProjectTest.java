package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 — reduction projects (API.md §2.8).
 *
 * <p>Proves Node parity (field set, defaults, messages) plus the documented
 * Java hardenings: ISO date/order/status validation and tenant validation of
 * {@code facilityId}/{@code targetId}. Fixtures live in freshly registered
 * organizations, so any cross-tenant leak fails the assertions; projects are
 * planning records only — emission rows are asserted untouched.
 */
class ReductionProjectTest extends AccountingTestBase {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private String[] isolatedTenant() throws Exception {
        String email = "prj-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Project Tenant " + suffix() + "\",\"country\":\"DE\","
                        + "\"industry\":\"Logistics\",\"fullName\":\"Project Founder\","
                        + "\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(201, registration.status(), registration.body().toString());
        String orgId = registration.body().path("data").path("organization").path("id").asText();

        String platformToken = loginToken(PLATFORM_EMAIL, PASSWORD);
        Api approved = postJson("/api/v1/platform/tenants/" + orgId + "/approve",
                platformToken, null);
        assertEquals(200, approved.status(), approved.body().toString());

        return new String[] {loginToken(email, PASSWORD), orgId};
    }

    private JsonNode createPeriodRange(String token, String name, String start, String end)
            throws Exception {
        Api api = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"" + name + "\",\"startDate\":\"" + start
                        + "\",\"endDate\":\"" + end + "\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }

    private String createTarget(String token) throws Exception {
        JsonNode baseline = createPeriodRange(token, "Baseline " + suffix(),
                "2043-01-01", "2043-12-31");
        JsonNode target = createPeriodRange(token, "Target " + suffix(),
                "2045-01-01", "2045-12-31");
        Api created = postJson("/api/v1/targets", token,
                "{\"name\":\"Link target " + suffix() + "\","
                        + "\"baselinePeriodId\":\"" + baseline.get("id").asText() + "\","
                        + "\"targetPeriodId\":\"" + target.get("id").asText() + "\","
                        + "\"baselineValueT\":1.0,\"targetValueT\":0.5,"
                        + "\"reductionPercentage\":50}");
        assertEquals(201, created.status(), created.body().toString());
        return created.body().path("data").path("id").asText();
    }

    private static void assertScaled(int scale, BigDecimal expected, JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertNotNull(value, "missing field " + field);
        assertEquals(0, expected.setScale(scale, RoundingMode.HALF_UP)
                        .compareTo(value.decimalValue().setScale(scale, RoundingMode.HALF_UP)),
                field + ": expected " + expected + ", wire was " + value);
    }

    private static String errorMessage(Api api) {
        return api.body().path("error").path("message").asText();
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    void projectCreationFollowsNodeParityWithTenantValidatedLinks() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];
        JsonNode facility = createFacility(token, "Proj Plant " + suffix(),
                "P7J-" + suffix());
        String facilityId = facility.get("id").asText();

        // Node defaults: missing numbers → 0, status → PLANNED, no links.
        Api defaulted = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"Defaults only\",\"startDate\":\"2044-01-01\","
                        + "\"endDate\":\"2044-12-31\"}");
        assertEquals(201, defaulted.status(), defaulted.body().toString());
        JsonNode defaults = defaulted.body().path("data");
        assertScaled(4, BigDecimal.ZERO, defaults, "baselineT");
        assertScaled(4, BigDecimal.ZERO, defaults, "expectedReductionT");
        assertScaled(4, BigDecimal.ZERO, defaults, "actualReductionT");
        assertEquals("PLANNED", defaults.path("status").asText());
        assertTrue(defaults.path("facilityId").isNull()
                || !defaults.hasNonNull("facilityId"));
        assertTrue(defaults.hasNonNull("ownerId"));
        assertTrue(defaults.hasNonNull("createdAt"));

        // Planning writes never touch the emission ledger.
        int recordsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM emission_records WHERE organization_id = ?",
                Integer.class, orgId);

        // Validation chain — every step a 400 with a precise message.
        Api noName = postJson("/api/v1/reduction-projects", token,
                "{\"startDate\":\"2044-01-01\",\"endDate\":\"2044-12-31\"}");
        assertEquals(400, noName.status(), noName.body().toString());
        assertEquals("Project name is required.", errorMessage(noName));

        Api noStart = postJson("/api/v1/reduction-projects", token, "{\"name\":\"X\"}");
        assertEquals(400, noStart.status(), noStart.body().toString());
        assertEquals("startDate is required.", errorMessage(noStart));

        Api badDate = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"startDate\":\"next spring\",\"endDate\":\"2044-12-31\"}");
        assertEquals(400, badDate.status(), badDate.body().toString());
        assertEquals("startDate must be an ISO date (YYYY-MM-DD).", errorMessage(badDate));

        Api wrongOrder = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"startDate\":\"2045-01-01\",\"endDate\":\"2044-12-31\"}");
        assertEquals(400, wrongOrder.status(), wrongOrder.body().toString());
        assertEquals("Project startDate must not be after endDate.", errorMessage(wrongOrder));

        Api negative = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"baselineT\":-5,\"startDate\":\"2044-01-01\","
                        + "\"endDate\":\"2044-12-31\"}");
        assertEquals(400, negative.status(), negative.body().toString());
        assertEquals("Project values must be zero or greater.", errorMessage(negative));

        Api badStatus = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"status\":\"RELEASED\",\"startDate\":\"2044-01-01\","
                        + "\"endDate\":\"2044-12-31\"}");
        assertEquals(400, badStatus.status(), badStatus.body().toString());
        assertEquals("Project status must be one of: PLANNED, IN_PROGRESS, "
                        + "COMPLETED, CANCELLED.", errorMessage(badStatus));

        // Facility links are tenant-validated: malformed == foreign.
        Api malformedFacility = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"facilityId\":\"not-a-uuid\","
                        + "\"startDate\":\"2044-01-01\",\"endDate\":\"2044-12-31\"}");
        assertEquals(404, malformedFacility.status(), malformedFacility.body().toString());
        assertEquals("FACILITY_NOT_FOUND",
                malformedFacility.body().path("error").path("code").asText());

        String[] other = isolatedTenant();
        JsonNode foreignFacility =
                createFacility(other[0], "Foreign Plant " + suffix(), "P7F-" + suffix());
        Api foreignFacilityCall = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"facilityId\":\""
                        + foreignFacility.get("id").asText() + "\","
                        + "\"startDate\":\"2044-01-01\",\"endDate\":\"2044-12-31\"}");
        assertEquals(404, foreignFacilityCall.status(), foreignFacilityCall.body().toString());
        assertEquals(malformedFacility.body().toString(),
                foreignFacilityCall.body().toString());

        // Target links are tenant-validated too.
        Api malformedTarget = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"targetId\":\"not-a-uuid\","
                        + "\"startDate\":\"2044-01-01\",\"endDate\":\"2044-12-31\"}");
        assertEquals(404, malformedTarget.status(), malformedTarget.body().toString());
        assertEquals("CARBON_TARGET_NOT_FOUND",
                malformedTarget.body().path("error").path("code").asText());

        String foreignTargetId = createTarget(other[0]);
        Api foreignTarget = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"X\",\"targetId\":\"" + foreignTargetId + "\","
                        + "\"startDate\":\"2044-01-01\",\"endDate\":\"2044-12-31\"}");
        assertEquals(404, foreignTarget.status(), foreignTarget.body().toString());
        assertEquals(malformedTarget.body().toString(), foreignTarget.body().toString());

        // The valid full create — parity message, links echoed, status honored.
        String targetId = createTarget(token);
        Api created = postJson("/api/v1/reduction-projects", token,
                "{\"name\":\"LED retrofit\",\"description\":\"Replace lighting\","
                        + "\"facilityId\":\"" + facilityId + "\","
                        + "\"targetId\":\"" + targetId + "\","
                        + "\"baselineT\":100.0,\"expectedReductionT\":40.0,"
                        + "\"actualReductionT\":5.0,"
                        + "\"startDate\":\"2044-01-01\",\"endDate\":\"2044-12-31\","
                        + "\"status\":\"IN_PROGRESS\"}");
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("Reduction project created.", created.body().path("message").asText());
        JsonNode data = created.body().path("data");
        assertEquals("LED retrofit", data.path("name").asText());
        assertEquals("Replace lighting", data.path("description").asText());
        assertEquals(facilityId, data.path("facilityId").asText());
        assertEquals(targetId, data.path("targetId").asText());
        assertEquals("IN_PROGRESS", data.path("status").asText());
        assertEquals("2044-01-01", data.path("startDate").asText());
        assertEquals("2044-12-31", data.path("endDate").asText());
        assertScaled(4, new BigDecimal("100.0"), data, "baselineT");
        assertScaled(4, new BigDecimal("40.0"), data, "expectedReductionT");
        assertScaled(4, new BigDecimal("5.0"), data, "actualReductionT");

        int recordsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM emission_records WHERE organization_id = ?",
                Integer.class, orgId);
        assertEquals(recordsBefore, recordsAfter,
                "projects must never write emission records");
    }

    // ------------------------------------------------------------------
    // Update + tenant safety
    // ------------------------------------------------------------------

    @Test
    void projectsAreTenantScopedAndPartiallyUpdatable() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();

        JsonNode facilityA =
                createFacility(tenantA[0], "Tenant A Plant " + suffix(), "P7A-" + suffix());
        String facilityId = facilityA.get("id").asText();
        Api created = postJson("/api/v1/reduction-projects", tenantA[0],
                "{\"name\":\"Original project\",\"description\":\"Keep me\","
                        + "\"facilityId\":\"" + facilityId + "\","
                        + "\"baselineT\":10.0,\"expectedReductionT\":4.0,"
                        + "\"startDate\":\"2044-01-01\",\"endDate\":\"2044-12-31\"}");
        assertEquals(201, created.status(), created.body().toString());
        JsonNode original = created.body().path("data");
        String projectId = original.path("id").asText();

        // Tenant B's list never contains tenant A's project.
        Api listB = getJson("/api/v1/reduction-projects", tenantB[0]);
        assertEquals(200, listB.status(), listB.body().toString());
        assertEquals(0, listB.body().path("data").size());

        // Cross-tenant and malformed ids collapse to the same 404.
        Api foreignPut = putJson("/api/v1/reduction-projects/" + projectId,
                tenantB[0], "{\"name\":\"Hijack\"}");
        assertEquals(404, foreignPut.status(), foreignPut.body().toString());
        assertEquals("REDUCTION_PROJECT_NOT_FOUND",
                foreignPut.body().path("error").path("code").asText());
        Api malformedPut = putJson("/api/v1/reduction-projects/not-a-uuid",
                tenantA[0], "{\"name\":\"x\"}");
        assertEquals(404, malformedPut.status(), malformedPut.body().toString());
        assertEquals(foreignPut.body().toString(), malformedPut.body().toString());

        // Partial update: provided fields change, blanks clear links/description.
        Api updated = putJson("/api/v1/reduction-projects/" + projectId, tenantA[0],
                "{\"name\":\"Renamed project\",\"status\":\"COMPLETED\","
                        + "\"description\":\"\",\"facilityId\":\"\"}");
        assertEquals(200, updated.status(), updated.body().toString());
        assertEquals("Reduction project updated.", updated.body().path("message").asText());
        JsonNode after = updated.body().path("data");
        assertEquals("Renamed project", after.path("name").asText());
        assertEquals("COMPLETED", after.path("status").asText());
        assertTrue(after.path("description").isNull()
                || !after.hasNonNull("description"), "blank description clears");
        assertFalse(after.hasNonNull("facilityId"), "blank facilityId clears the link");
        // Immutable identity fields survive the update.
        assertEquals(original.path("id").asText(), after.path("id").asText());
        assertEquals(original.path("organizationId").asText(),
                after.path("organizationId").asText());
        assertEquals(original.path("ownerId").asText(), after.path("ownerId").asText());
        assertEquals(original.path("createdAt").asText(), after.path("createdAt").asText());
        assertScaled(4, original.path("baselineT").decimalValue(), after, "baselineT");
        assertEquals(original.path("startDate").asText(), after.path("startDate").asText());

        // Validation on the update path: bad status, order violation, bad link.
        Api badStatus = putJson("/api/v1/reduction-projects/" + projectId, tenantA[0],
                "{\"status\":\"PAUSED\"}");
        assertEquals(400, badStatus.status(), badStatus.body().toString());
        assertEquals("Project status must be one of: PLANNED, IN_PROGRESS, "
                        + "COMPLETED, CANCELLED.", errorMessage(badStatus));

        Api wrongOrder = putJson("/api/v1/reduction-projects/" + projectId, tenantA[0],
                "{\"endDate\":\"2043-06-30\"}");
        assertEquals(400, wrongOrder.status(), wrongOrder.body().toString());
        assertEquals("Project startDate must not be after endDate.", errorMessage(wrongOrder));

        JsonNode facilityB =
                createFacility(tenantB[0], "Tenant B Plant " + suffix(), "P7B-" + suffix());
        Api foreignFacilityPut = putJson("/api/v1/reduction-projects/" + projectId,
                tenantA[0], "{\"facilityId\":\"" + facilityB.get("id").asText() + "\"}");
        assertEquals(404, foreignFacilityPut.status(), foreignFacilityPut.body().toString());
        assertEquals("FACILITY_NOT_FOUND",
                foreignFacilityPut.body().path("error").path("code").asText());

        // Nothing from the rejected updates was persisted.
        Api listA = getJson("/api/v1/reduction-projects", tenantA[0]);
        assertEquals(200, listA.status(), listA.body().toString());
        assertEquals(1, listA.body().path("data").size());
        JsonNode row = listA.body().path("data").get(0);
        assertEquals("Renamed project", row.path("name").asText());
        assertEquals("COMPLETED", row.path("status").asText());
        assertEquals("2044-12-31", row.path("endDate").asText());
        assertFalse(row.hasNonNull("facilityId"));
    }

    @Test
    void projectsEndpointsRequireAuthentication() throws Exception {
        Api list = getJson("/api/v1/reduction-projects", null);
        assertEquals(401, list.status());
        Api create = postJson("/api/v1/reduction-projects", null, "{}");
        assertEquals(401, create.status());
        Api put = putJson("/api/v1/reduction-projects/" + UUID.randomUUID(), null, "{}");
        assertEquals(401, put.status());
    }
}
