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
 * Phase 7 — carbon targets (API.md §2.8, ADR-018/019 progress semantics).
 *
 * <p>Progress assertions recompute the expected percentages from an
 * independent SQL aggregation of {@code emission_records}; fixtures live in
 * freshly registered organizations, so any cross-tenant leak (list, update,
 * period reference) breaks the assertions.
 */
class TargetTest extends AccountingTestBase {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private String[] isolatedTenant() throws Exception {
        String email = "tgt-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Target Tenant " + suffix() + "\",\"country\":\"DE\","
                        + "\"industry\":\"Logistics\",\"fullName\":\"Target Founder\","
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

    private void seedAndRun(String token, String periodId, String facilityId,
                            String scope, String category, String activityType,
                            String quantity, String unit) throws Exception {
        JsonNode activity = createActivity(token, periodId, facilityId,
                scope, category, activityType, quantity, unit);
        Api run = runCalculation(token, activity.path("id").asText());
        assertEquals(200, run.status(), run.body().toString());
    }

    /** [scope1, location, market, scope3] of the ACTIVE ledger. */
    private BigDecimal[] sums(String where, Object... args) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_1'), 0), "
                        + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "AND scope2_type = 'LOCATION_BASED'), 0), "
                        + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "AND scope2_type = 'MARKET_BASED'), 0), "
                        + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_3'), 0) "
                        + "FROM emission_records WHERE status = 'ACTIVE' AND " + where,
                args,
                (rs, rowNum) -> new BigDecimal[] {
                        rs.getBigDecimal(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4)});
    }

    private static void assertScaled(int scale, BigDecimal expected, JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertNotNull(value, "missing field " + field);
        assertEquals(0, expected.setScale(scale, RoundingMode.HALF_UP)
                        .compareTo(value.decimalValue().setScale(scale, RoundingMode.HALF_UP)),
                field + ": expected " + expected + ", wire was " + value);
    }

    private static String validBody(String name, String baselinePeriod, String targetPeriod) {
        return "{\"name\":\"" + name + "\","
                + "\"baselinePeriodId\":\"" + baselinePeriod + "\","
                + "\"targetPeriodId\":\"" + targetPeriod + "\","
                + "\"baselineValueT\":1.0,\"targetValueT\":0.5,"
                + "\"reductionPercentage\":50,\"notes\":\"Phase 7 target\"}";
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    void targetCreationFollowsNodeParityWithTenantValidatedPeriods() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        JsonNode baseline = createPeriodRange(token, "FY2043", "2043-01-01", "2043-12-31");
        JsonNode target = createPeriodRange(token, "FY2045", "2045-01-01", "2045-12-31");
        String baselineId = baseline.get("id").asText();
        String targetId = target.get("id").asText();

        // Field-by-field validation, in service order — every step a 400.
        Api noName = postJson("/api/v1/targets", token, "{}");
        assertEquals(400, noName.status(), noName.body().toString());
        assertEquals("VALIDATION_ERROR", noName.body().path("error").path("code").asText());
        assertEquals("Target name is required.",
                noName.body().path("error").path("message").asText());

        Api noPeriods = postJson("/api/v1/targets", token, "{\"name\":\"T\"}");
        assertEquals(400, noPeriods.status(), noPeriods.body().toString());
        assertEquals("baselinePeriodId is required.",
                noPeriods.body().path("error").path("message").asText());

        String base = "{\"name\":\"T\",\"baselinePeriodId\":\"" + baselineId
                + "\",\"targetPeriodId\":\"" + targetId + "\"";

        Api noBaseline = postJson("/api/v1/targets", token, base + "}");
        assertEquals(400, noBaseline.status(), noBaseline.body().toString());
        assertEquals("baselineValueT is required.",
                noBaseline.body().path("error").path("message").asText());

        Api noTargetValue = postJson("/api/v1/targets", token,
                base + ",\"baselineValueT\":1.0}");
        assertEquals(400, noTargetValue.status(), noTargetValue.body().toString());
        assertEquals("targetValueT is required.",
                noTargetValue.body().path("error").path("message").asText());

        Api noPct = postJson("/api/v1/targets", token,
                base + ",\"baselineValueT\":1.0,\"targetValueT\":0.5}");
        assertEquals(400, noPct.status(), noPct.body().toString());
        assertEquals("reductionPercentage is required.",
                noPct.body().path("error").path("message").asText());

        Api badPct = postJson("/api/v1/targets", token,
                base + ",\"baselineValueT\":1.0,\"targetValueT\":0.5,"
                        + "\"reductionPercentage\":150}");
        assertEquals(400, badPct.status(), badPct.body().toString());
        assertEquals("reductionPercentage must be between 0 and 100.",
                badPct.body().path("error").path("message").asText());

        Api negative = postJson("/api/v1/targets", token,
                base + ",\"baselineValueT\":-1,\"targetValueT\":0.5,"
                        + "\"reductionPercentage\":50}");
        assertEquals(400, negative.status(), negative.body().toString());
        assertEquals("Target values must be zero or greater.",
                negative.body().path("error").path("message").asText());

        // Periods are tenant-validated: malformed == foreign (indistinguishable).
        Api malformedPeriod = postJson("/api/v1/targets", token,
                validBody("T", "not-a-uuid", targetId));
        assertEquals(404, malformedPeriod.status(), malformedPeriod.body().toString());
        assertEquals("REPORTING_PERIOD_NOT_FOUND",
                malformedPeriod.body().path("error").path("code").asText());

        String[] other = isolatedTenant();
        String otherPeriod =
                createPeriodRange(other[0], "Foreign FY", "2046-01-01", "2046-12-31")
                        .get("id").asText();
        Api foreignPeriod = postJson("/api/v1/targets", token,
                validBody("T", otherPeriod, targetId));
        assertEquals(404, foreignPeriod.status(), foreignPeriod.body().toString());
        assertEquals(malformedPeriod.body().toString(), foreignPeriod.body().toString());

        // The valid create — Node parity message, status forced to ON_TRACK.
        Api created = postJson("/api/v1/targets", token,
                validBody("Phase 7 Reduction", baselineId, targetId));
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("Carbon target created.", created.body().path("message").asText());
        JsonNode data = created.body().path("data");
        assertEquals("Phase 7 Reduction", data.path("name").asText());
        assertEquals(baselineId, data.path("baselinePeriodId").asText());
        assertEquals(targetId, data.path("targetPeriodId").asText());
        assertEquals("ON_TRACK", data.path("status").asText());
        assertEquals("Phase 7 target", data.path("notes").asText());
        assertTrue(data.hasNonNull("ownerId"), "ownerId must be the caller");
        assertScaled(4, new BigDecimal("1.0"), data, "baselineValueT");
        assertScaled(4, new BigDecimal("0.5"), data, "targetValueT");
        assertScaled(4, new BigDecimal("50.00"), data, "reductionPercentage");
        assertScaled(4, new BigDecimal("0.5"), data, "plannedReductionT");
        // Empty target period: absence reported, nothing invented.
        assertTrue(data.path("hasPersistedEmissions").isBoolean());
        assertFalse(data.path("hasPersistedEmissions").asBoolean());
        assertTrue(data.path("progressLocationPct").isNull());
        assertTrue(data.path("progressMarketPct").isNull());
        assertTrue(data.path("currentLocationBasedT").isNull());
        assertTrue(data.path("currentMarketBasedT").isNull());
        assertTrue(data.hasNonNull("createdAt"));

        // The client cannot force a status on create (Node parity: ON_TRACK).
        Api forced = postJson("/api/v1/targets", token,
                validBody("Forced Status", baselineId, targetId)
                        .replace("\"notes\"", "\"status\":\"ACHIEVED\",\"notes\""));
        assertEquals(201, forced.status(), forced.body().toString());
        assertEquals("ON_TRACK", forced.body().path("data").path("status").asText());
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    @Test
    void targetProgressIsComputedFromPersistedEmissionsOnBothBases() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];

        JsonNode baselinePeriod = createPeriodRange(token, "FY2043 (Baseline)",
                "2043-01-01", "2043-12-31");
        JsonNode targetPeriod = createPeriodRange(token, "FY2045 (Target)",
                "2045-01-01", "2045-12-31");
        String baselineId = baselinePeriod.get("id").asText();
        String targetId = targetPeriod.get("id").asText();
        JsonNode facility = createFacility(token, "Target Plant " + suffix(),
                "P7G-" + suffix());
        String facilityId = facility.get("id").asText();

        // Baseline year: heavy. Target year: light — different per-basis moves.
        seedAndRun(token, baselineId, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "1000", "kWh");
        seedAndRun(token, baselineId, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "10000", "kWh");
        seedAndRun(token, baselineId, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "10000", "kWh");
        seedAndRun(token, targetId, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, targetId, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");
        seedAndRun(token, targetId, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "1000", "kWh");

        BigDecimal baselineValue = new BigDecimal("1.0000");
        BigDecimal targetValue = new BigDecimal("0.1000");
        Api created = postJson("/api/v1/targets", token,
                "{\"name\":\"Cut by half\","
                        + "\"baselinePeriodId\":\"" + baselineId + "\","
                        + "\"targetPeriodId\":\"" + targetId + "\","
                        + "\"baselineValueT\":1.0000,\"targetValueT\":0.1000,"
                        + "\"reductionPercentage\":90}");
        assertEquals(201, created.status(), created.body().toString());
        JsonNode data = created.body().path("data");

        // Current values == independent SQL aggregation of the target period.
        BigDecimal[] targetSums =
                sums("organization_id = ? AND reporting_period_id = ?", orgId, targetId);
        BigDecimal currentLoc = targetSums[0].add(targetSums[1]).add(targetSums[3]);
        BigDecimal currentMkt = targetSums[0].add(targetSums[2]).add(targetSums[3]);
        assertTrue(currentLoc.subtract(currentMkt).signum() != 0,
                "fixture must move the two bases differently");
        assertScaled(4, currentLoc, data, "currentLocationBasedT");
        assertScaled(4, currentMkt, data, "currentMarketBasedT");
        assertTrue(data.path("hasPersistedEmissions").asBoolean());

        // Progress == (baseline − current) / planned × 100, computed in-test.
        BigDecimal planned = baselineValue.subtract(targetValue);
        assertScaled(4, planned, data, "plannedReductionT");
        BigDecimal expectedLocPct = baselineValue.subtract(currentLoc)
                .multiply(BigDecimal.valueOf(100))
                .divide(planned, 2, RoundingMode.HALF_UP);
        BigDecimal expectedMktPct = baselineValue.subtract(currentMkt)
                .multiply(BigDecimal.valueOf(100))
                .divide(planned, 2, RoundingMode.HALF_UP);
        assertScaled(2, expectedLocPct, data, "progressLocationPct");
        assertScaled(2, expectedMktPct, data, "progressMarketPct");
        assertFalse(data.path("progressLocationPct").decimalValue()
                        .subtract(data.path("progressMarketPct").decimalValue())
                        .signum() == 0,
                "the two bases must produce distinct progress values");

        // Status is stored — never auto-flipped by computed progress.
        assertEquals("ON_TRACK", data.path("status").asText());

        // An empty target period reports absence, not a guessed zero.
        JsonNode emptyPeriod = createPeriodRange(token, "FY2099 (Empty)",
                "2099-01-01", "2099-12-31");
        Api emptyTarget = postJson("/api/v1/targets", token,
                validBody("Empty progress", baselineId,
                        emptyPeriod.get("id").asText()));
        assertEquals(201, emptyTarget.status(), emptyTarget.body().toString());
        JsonNode emptyData = emptyTarget.body().path("data");
        assertFalse(emptyData.path("hasPersistedEmissions").asBoolean());
        assertTrue(emptyData.path("progressLocationPct").isNull());
        assertTrue(emptyData.path("currentLocationBasedT").isNull());

        // Planned reduction <= 0: percentages are undefined, reported as null
        // even though the period has persisted emissions.
        Api noPlanned = postJson("/api/v1/targets", token,
                "{\"name\":\"Flat target\","
                        + "\"baselinePeriodId\":\"" + baselineId + "\","
                        + "\"targetPeriodId\":\"" + baselineId + "\","
                        + "\"baselineValueT\":1.0,\"targetValueT\":1.0,"
                        + "\"reductionPercentage\":0}");
        assertEquals(201, noPlanned.status(), noPlanned.body().toString());
        JsonNode noPlannedData = noPlanned.body().path("data");
        assertTrue(noPlannedData.path("hasPersistedEmissions").asBoolean());
        assertTrue(noPlannedData.path("progressLocationPct").isNull());
        assertTrue(noPlannedData.path("progressMarketPct").isNull());
        assertScaled(4, new BigDecimal("0.0000"), noPlannedData, "plannedReductionT");
    }

    // ------------------------------------------------------------------
    // Update + tenant safety
    // ------------------------------------------------------------------

    @Test
    void targetsAreTenantScopedAndPartiallyUpdatable() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();

        JsonNode baselineA = createPeriodRange(tenantA[0], "FY2043",
                "2043-01-01", "2043-12-31");
        JsonNode targetA = createPeriodRange(tenantA[0], "FY2045",
                "2045-01-01", "2045-12-31");
        Api created = postJson("/api/v1/targets", tenantA[0],
                validBody("Tenant A target", baselineA.get("id").asText(),
                        targetA.get("id").asText()));
        assertEquals(201, created.status(), created.body().toString());
        JsonNode original = created.body().path("data");
        String targetId = original.path("id").asText();

        // Tenant B's list never contains tenant A's target.
        Api listB = getJson("/api/v1/targets", tenantB[0]);
        assertEquals(200, listB.status(), listB.body().toString());
        assertEquals(0, listB.body().path("data").size());

        // Cross-tenant and malformed ids collapse to the same 404.
        Api foreignPut = putJson("/api/v1/targets/" + targetId, tenantB[0],
                "{\"name\":\"Hijack\"}");
        assertEquals(404, foreignPut.status(), foreignPut.body().toString());
        assertEquals("CARBON_TARGET_NOT_FOUND",
                foreignPut.body().path("error").path("code").asText());
        Api malformedPut = putJson("/api/v1/targets/not-a-uuid", tenantA[0],
                "{\"name\":\"x\"}");
        assertEquals(404, malformedPut.status(), malformedPut.body().toString());
        assertEquals(foreignPut.body().toString(), malformedPut.body().toString());

        // Partial update: provided fields change, the rest stay put.
        Api updated = putJson("/api/v1/targets/" + targetId, tenantA[0],
                "{\"name\":\"Renamed target\",\"status\":\"BEHIND\","
                        + "\"notes\":\"  \"}");
        assertEquals(200, updated.status(), updated.body().toString());
        assertEquals("Carbon target updated.", updated.body().path("message").asText());
        JsonNode after = updated.body().path("data");
        assertEquals("Renamed target", after.path("name").asText());
        assertEquals("BEHIND", after.path("status").asText());
        assertTrue(after.path("notes").isNull(), "blank notes clear the field");
        // Immutable identity fields survive the update.
        assertEquals(original.path("id").asText(), after.path("id").asText());
        assertEquals(original.path("organizationId").asText(),
                after.path("organizationId").asText());
        assertEquals(original.path("ownerId").asText(), after.path("ownerId").asText());
        assertEquals(original.path("createdAt").asText(), after.path("createdAt").asText());
        assertEquals(original.path("baselinePeriodId").asText(),
                after.path("baselinePeriodId").asText());
        assertScaled(4, original.path("baselineValueT").decimalValue(),
                after, "baselineValueT");

        // Validation on the update path: bad status, blank name, foreign period.
        Api badStatus = putJson("/api/v1/targets/" + targetId, tenantA[0],
                "{\"status\":\"MOONSHOT\"}");
        assertEquals(400, badStatus.status(), badStatus.body().toString());
        assertEquals("Target status must be one of: ON_TRACK, BEHIND, "
                        + "ACHIEVED, EXPIRED.",
                badStatus.body().path("error").path("message").asText());
        Api blankName = putJson("/api/v1/targets/" + targetId, tenantA[0],
                "{\"name\":\"   \"}");
        assertEquals(400, blankName.status(), blankName.body().toString());

        JsonNode periodB = createPeriodRange(tenantB[0], "FY2046",
                "2046-01-01", "2046-12-31");
        Api foreignPeriod = putJson("/api/v1/targets/" + targetId, tenantA[0],
                "{\"baselinePeriodId\":\"" + periodB.get("id").asText() + "\"}");
        assertEquals(404, foreignPeriod.status(), foreignPeriod.body().toString());
        assertEquals("REPORTING_PERIOD_NOT_FOUND",
                foreignPeriod.body().path("error").path("code").asText());

        // Nothing was persisted from the rejected updates.
        Api listA = getJson("/api/v1/targets", tenantA[0]);
        assertEquals(200, listA.status(), listA.body().toString());
        assertEquals(1, listA.body().path("data").size());
        JsonNode row = listA.body().path("data").get(0);
        assertEquals("Renamed target", row.path("name").asText());
        assertEquals("BEHIND", row.path("status").asText());
        assertEquals(baselineA.get("id").asText(), row.path("baselinePeriodId").asText());
        assertTrue(row.path("notes").isNull(), "blank notes stayed cleared");
    }

    @Test
    void targetsEndpointsRequireAuthentication() throws Exception {
        Api list = getJson("/api/v1/targets", null);
        assertEquals(401, list.status());
        Api create = postJson("/api/v1/targets", null, "{}");
        assertEquals(401, create.status());
        Api put = putJson("/api/v1/targets/" + UUID.randomUUID(), null, "{}");
        assertEquals(401, put.status());
    }
}
