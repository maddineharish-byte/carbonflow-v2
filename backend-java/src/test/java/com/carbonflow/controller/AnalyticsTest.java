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
 * Phase 7 — analytics foundation (Workstream A).
 *
 * <p>Every assertion compares the HTTP payload against an independent SQL
 * aggregation of {@code emission_records} — the persisted ledger is the source
 * of truth (ADR-018). Because all fixtures live in freshly registered
 * organizations, a cross-tenant leak anywhere in the aggregation would break
 * the equality and fail the test.
 *
 * <p>Also proves the Phase 7 de-fabrication: no benchmark trajectory, no
 * synthetic {@code period-2024-mXX} months, no LLM narrative — only real
 * reporting periods and computed values.
 */
class AnalyticsTest extends AccountingTestBase {

    private static final String PERSPECTIVES =
            "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_1'), 0), "
                    + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                    + "AND scope2_type = 'LOCATION_BASED'), 0), "
                    + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                    + "AND scope2_type = 'MARKET_BASED'), 0), "
                    + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_3'), 0)";

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Registers a tenant, platform-approves it and logs the founder in. */
    private String[] isolatedTenant() throws Exception {
        String email = "analytics-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Analytics Tenant " + suffix() + "\",\"country\":\"DE\","
                        + "\"industry\":\"Logistics\",\"fullName\":\"Analytics Founder\","
                        + "\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(201, registration.status(), registration.body().toString());
        String orgId = registration.body().path("data").path("organization").path("id").asText();

        String platformToken = loginToken(PLATFORM_EMAIL, PASSWORD);
        Api approved = postJson("/api/v1/platform/tenants/" + orgId + "/approve",
                platformToken, null);
        assertEquals(200, approved.status(), approved.body().toString());

        return new String[] {loginToken(email, PASSWORD), orgId};
    }

    /** Reporting period with explicit distinct dates (trend ordering fixture). */
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

    // ------------------------------------------------------------------
    // Independent SQL oracle ([scope1, location, market, scope3])
    // ------------------------------------------------------------------

    private BigDecimal[] sums(String where, Object... args) {
        return jdbc.queryForObject(
                "SELECT " + PERSPECTIVES + " FROM emission_records "
                        + "WHERE status = 'ACTIVE' AND " + where,
                args,
                (rs, rowNum) -> new BigDecimal[] {
                        rs.getBigDecimal(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4)});
    }

    private BigDecimal[] categoryPerspectives(String orgId, String category) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(co2e_tonnes) "
                        + "FILTER (WHERE scope <> 'SCOPE_2' OR scope2_type = 'LOCATION_BASED'), 0), "
                        + "COALESCE(SUM(co2e_tonnes) "
                        + "FILTER (WHERE scope = 'SCOPE_2' AND scope2_type = 'MARKET_BASED'), 0) "
                        + "FROM emission_records "
                        + "WHERE status = 'ACTIVE' AND organization_id = ? AND category = ?",
                new Object[] {orgId, category},
                (rs, rowNum) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)});
    }

    private int countRows(String table, String orgId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id = ?",
                Integer.class, orgId);
        return count == null ? 0 : count;
    }

    private String latestAuditStatusOrNull(String orgId) {
        java.util.List<String> statuses = jdbc.queryForList(
                "SELECT status FROM carbon_audits WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id",
                String.class, orgId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private static void assertScaled(int scale, BigDecimal expected, JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertNotNull(value, "missing field " + field);
        assertEquals(0, expected.setScale(scale, RoundingMode.HALF_UP)
                        .compareTo(value.decimalValue().setScale(scale, RoundingMode.HALF_UP)),
                field + ": expected " + expected + ", wire was " + value);
    }

    private static void assert2dp(BigDecimal expected, JsonNode node, String field) {
        assertScaled(2, expected, node, field);
    }

    private static void assertPerspectivesAt(int scale, BigDecimal[] expected, JsonNode emissions) {
        assertScaled(scale, expected[0], emissions, "scope1Tonnes");
        assertScaled(scale, expected[1], emissions, "scope2LocationTonnes");
        assertScaled(scale, expected[2], emissions, "scope2MarketTonnes");
        assertScaled(scale, expected[3], emissions, "scope3Tonnes");
        assertScaled(scale, expected[0].add(expected[1]).add(expected[3]),
                emissions, "totalLocationBasedTonnes");
        assertScaled(scale, expected[0].add(expected[2]).add(expected[3]),
                emissions, "totalMarketBasedTonnes");
    }

    private static void assertPerspectives(BigDecimal[] expected, JsonNode emissions) {
        assertPerspectivesAt(2, expected, emissions);
    }

    private static final BigDecimal[] ZEROES = {BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO};

    private static JsonNode byField(JsonNode array, String field, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        return null;
    }

    /** ASCII-only codepoint dump for charset-safe failure diagnostics. */
    private static String codepoints(String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> out.append(String.format("U+%04X ", cp)));
        return out.toString().trim();
    }

    // ------------------------------------------------------------------
    // Dashboard
    // ------------------------------------------------------------------

    @Test
    void dashboardMirrorsThePersistedLedgerWithoutFabricatedPeriods() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];

        JsonNode fy2043 = createPeriodRange(token, "FY2043", "2043-01-01", "2043-12-31");
        JsonNode fy2044 = createPeriodRange(token, "FY2044", "2044-01-01", "2044-12-31");
        JsonNode fy2045 = createPeriodRange(token, "FY2045 (Empty)", "2045-01-01", "2045-12-31");
        JsonNode facility = createFacility(token, "Analytics Plant " + suffix(),
                "P7A-" + suffix());
        String facilityId = facility.get("id").asText();

        String p43 = fy2043.get("id").asText();
        String p44 = fy2044.get("id").asText();
        String p45 = fy2045.get("id").asText();

        seedAndRun(token, p43, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, p43, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");
        seedAndRun(token, p43, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "1000", "kWh");
        seedAndRun(token, p44, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "200", "kWh");
        seedAndRun(token, p44, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "20000", "kWh");
        seedAndRun(token, p44, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "100", "kWh");

        // An audit exists so the health block carries real checklist counts.
        JsonNode audit = createAudit(token, p43);

        Api dashboard = getJson("/api/v1/analytics/dashboard", token);
        assertEquals(200, dashboard.status(), dashboard.body().toString());
        JsonNode data = dashboard.body().path("data");
        String raw = dashboard.body().toString();

        // Emissions == independent SQL aggregation, both bases preserved.
        assertPerspectives(sums("organization_id = ?", orgId), data.path("emissions"));
        BigDecimal[] orgSums = sums("organization_id = ?", orgId);
        assertTrue(orgSums[1].subtract(orgSums[2]).signum() != 0,
                "fixture must produce different location/market perspectives");

        // Audit state straight from PostgreSQL (never the old hardcoded stub).
        assertTrue(data.path("auditStatus").isTextual(), "auditStatus must be a string");
        String expectedStatus = latestAuditStatusOrNull(orgId);
        assertEquals(expectedStatus == null ? "DRAFT" : expectedStatus,
                data.path("auditStatus").asText());
        String auditId = audit.get("id").asText();
        int checklistTotal = jdbc.queryForObject(
                "SELECT count(*) FROM audit_checklist_items WHERE audit_id = ?",
                Integer.class, auditId);
        assertTrue(checklistTotal > 0, "audit must seed checklist items");
        assertEquals(checklistTotal,
                data.path("auditHealth").path("checklistTotal").asInt());
        assertEquals(0, data.path("auditHealth").path("checklistSatisfied").asInt(),
                "fresh checklist must be unsatisfied");
        assertEquals(0, data.path("auditHealth").path("openFindingsCount").asInt());

        // Counts == row counts (no invented counters).
        assertEquals(countRows("activity_data", orgId),
                data.path("activityCount").asInt());
        assertEquals(countRows("carbon_targets", orgId),
                data.path("targetsCount").asInt());
        assertEquals(countRows("reduction_projects", orgId),
                data.path("reductionProjectsCount").asInt());

        // Period trends: exactly the real periods, in start-date order, empty
        // period reported as zero — never the benchmark trajectory.
        JsonNode trends = data.path("periodTrends");
        assertEquals(3, trends.size(), "one row per real reporting period");
        assertEquals(p43, trends.get(0).path("periodId").asText());
        assertEquals(p44, trends.get(1).path("periodId").asText());
        assertEquals(p45, trends.get(2).path("periodId").asText());
        assertPerspectives(sums("organization_id = ? AND reporting_period_id = ?", orgId, p43),
                trends.get(0));
        assertPerspectives(sums("organization_id = ? AND reporting_period_id = ?", orgId, p44),
                trends.get(1));
        assert2dp(BigDecimal.ZERO, trends.get(2), "totalLocationBasedTonnes");
        assert2dp(BigDecimal.ZERO, trends.get(2), "scope1Tonnes");
        assertFalse(raw.contains("period-2024"),
                "synthetic fallback period ids must be gone: " + raw);
        assertFalse(raw.toLowerCase().contains("benchmark"),
                "benchmark trajectory must be gone: " + raw);

        // Facility row == per-facility SQL sums, market basis present.
        JsonNode facRow = byField(data.path("facilities"), "id", facilityId);
        assertNotNull(facRow, "seeded facility must appear");
        BigDecimal[] facSums = sums("organization_id = ? AND facility_id = ?", orgId, facilityId);
        assert2dp(facSums[0], facRow, "scope1Tonnes");
        assert2dp(facSums[1], facRow, "scope2Tonnes");
        assert2dp(facSums[1], facRow, "scope2LocationTonnes");
        assert2dp(facSums[2], facRow, "scope2MarketTonnes");
        assert2dp(facSums[0].add(facSums[1]), facRow, "totalTonnes");
        assert2dp(facSums[0].add(facSums[2]), facRow, "totalMarketBasedTonnes");

        // Category rows: location perspective charted, market kept additive.
        JsonNode locCategory = byField(data.path("categories"), "category",
                "ELECTRICITY_LOCATION");
        assertNotNull(locCategory, "seeded category must appear");
        BigDecimal[] locSums = categoryPerspectives(orgId, "ELECTRICITY_LOCATION");
        assert2dp(locSums[0], locCategory, "tonnes");
        assert2dp(locSums[1], locCategory, "tonnesMarketBased");
        JsonNode marketCategory = byField(data.path("categories"), "category",
                "ELECTRICITY_MARKET");
        assertNotNull(marketCategory, "seeded category must appear");
        BigDecimal[] marketSums = categoryPerspectives(orgId, "ELECTRICITY_MARKET");
        assert2dp(marketSums[0], marketCategory, "tonnes");
        assert2dp(marketSums[1], marketCategory, "tonnesMarketBased");
    }

    @Test
    void dashboardsAreTenantScopedToTheAuthenticatedOrganization() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();

        JsonNode periodA = createPeriodRange(tenantA[0], "FY2043",
                "2043-01-01", "2043-12-31");
        JsonNode facilityA = createFacility(tenantA[0], "Plant A " + suffix(),
                "P7B-" + suffix());
        seedAndRun(tenantA[0], periodA.get("id").asText(),
                facilityA.get("id").asText(), "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");

        Api dashboardA = getJson("/api/v1/analytics/dashboard", tenantA[0]);
        assertEquals(200, dashboardA.status(), dashboardA.body().toString());
        assertPerspectives(sums("organization_id = ?", tenantA[1]),
                dashboardA.body().path("data").path("emissions"));
        assertTrue(dashboardA.body().path("data").path("emissions")
                        .path("totalLocationBasedTonnes").decimalValue().signum() > 0,
                "tenant A must see its own emissions");

        // Tenant B registered its own empty tenant: zero totals, empty trends.
        Api dashboardB = getJson("/api/v1/analytics/dashboard", tenantB[0]);
        assertEquals(200, dashboardB.status(), dashboardB.body().toString());
        JsonNode emissionsB = dashboardB.body().path("data").path("emissions");
        assertPerspectives(new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO}, emissionsB);
        assertEquals(0, dashboardB.body().path("data").path("periodTrends").size(),
                "no periods were created for tenant B");
        assertEquals("DRAFT",
                dashboardB.body().path("data").path("auditStatus").asText());
        assertEquals(0, dashboardB.body().path("data").path("activityCount").asInt());
    }

    @Test
    void analyticsEndpointsRequireAuthentication() throws Exception {
        Api dashboard = getJson("/api/v1/analytics/dashboard", null);
        assertEquals(401, dashboard.status());
        Api trend = postJson("/api/v1/analytics/trend-insights", null, "{}");
        assertEquals(401, trend.status());
        Api breakdown = getJson("/api/v1/analytics/breakdown?dimension=facility", null);
        assertEquals(401, breakdown.status());
        Api summary = getJson("/api/v1/analytics/periods/" + UUID.randomUUID()
                + "/summary", null);
        assertEquals(401, summary.status());
    }

    // ------------------------------------------------------------------
    // Trend insights
    // ------------------------------------------------------------------

    @Test
    void trendInsightsAreComputedFromPersistedData() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];

        JsonNode base = createPeriodRange(token, "FY2043 (Base)",
                "2043-01-01", "2043-12-31");
        JsonNode current = createPeriodRange(token, "FY2044 (Current)",
                "2044-01-01", "2044-12-31");
        JsonNode facility = createFacility(token, "Trend Plant " + suffix(),
                "P7T-" + suffix());
        String facilityId = facility.get("id").asText();

        String baseId = base.get("id").asText();
        String currentId = current.get("id").asText();

        // Location rises sharply while market falls: DIVERGENCE fixture.
        seedAndRun(token, baseId, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, baseId, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");
        seedAndRun(token, baseId, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "1000", "kWh");
        seedAndRun(token, currentId, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "200", "kWh");
        seedAndRun(token, currentId, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "20000", "kWh");
        seedAndRun(token, currentId, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "100", "kWh");

        Api trend = postJson("/api/v1/analytics/trend-insights", token, "{}");
        assertEquals(200, trend.status(), trend.body().toString());
        JsonNode data = trend.body().path("data");
        String raw = trend.body().toString();

        assertEquals("carbonflow-deterministic-analytics",
                data.path("modelUsed").asText());
        assertFalse(raw.contains("gemini"), "no LLM claims: " + raw);
        assertFalse(raw.contains("Decoupling"), "no fabricated narrative: " + raw);
        assertFalse(raw.contains("Space Heating"), "no fabricated narrative: " + raw);
        assertFalse(raw.contains("VPPA"), "no fabricated narrative: " + raw);

        JsonNode summary = data.path("summary");
        assertEquals("INCREASING", summary.path("overallTrajectory").asText());
        assertTrue(summary.path("headline").asText().contains("FY2043 (Base)"),
                summary.path("headline").asText());
        assertTrue(summary.path("headline").asText().contains("FY2044 (Current)"),
                summary.path("headline").asText());
        assertTrue(summary.path("headline").asText().contains("rose"),
                summary.path("headline").asText());
        String expectedRange = "FY2043 (Base) \u2013 FY2044 (Current)";
        String actualRange = summary.path("periodRange").asText();
        assertEquals(expectedRange, actualRange,
                "expectedCodes=" + codepoints(expectedRange)
                        + " actualCodes=" + codepoints(actualRange));
        assert2dp(BigDecimal.ONE, summary, "confidenceScore");

        boolean observationFound = false;
        for (JsonNode observation : summary.path("keyObservations")) {
            if (observation.asText().equals(
                    "Periods with persisted emissions: 2 of 2 in the analysis window.")) {
                observationFound = true;
            }
        }
        assertTrue(observationFound, summary.path("keyObservations").toString());

        // The opposite-direction Scope 2 movement is a computed DIVERGENCE.
        JsonNode anomalies = data.path("anomalies");
        assertEquals(1, anomalies.size(), anomalies.toString());
        assertEquals("DIVERGENCE", anomalies.get(0).path("type").asText());
        assertEquals("HIGH", anomalies.get(0).path("severity").asText());
        assertTrue(anomalies.get(0).path("affectedPeriod").asText()
                .contains("FY2043 (Base)"), anomalies.get(0).toString());
        assertTrue(anomalies.get(0).path("affectedPeriod").asText()
                .contains("FY2044 (Current)"), anomalies.get(0).toString());

        // Opportunities are the observed period-over-period category increases.
        JsonNode opportunities = data.path("reductionOpportunities");
        assertEquals(2, opportunities.size(), opportunities.toString());
        assertEquals("RENEWABLE_PROCUREMENT",
                opportunities.get(0).path("category").asText());
        assertEquals("MEDIUM", opportunities.get(0).path("priority").asText());
        assertEquals("Not assessed",
                opportunities.get(0).path("paybackPeriod").asText());
        BigDecimal expectedIncrease = jdbc.queryForObject(
                "SELECT COALESCE(SUM(co2e_tonnes), 0) FROM emission_records "
                        + "WHERE status = 'ACTIVE' AND organization_id = ? "
                        + "AND category = 'ELECTRICITY_LOCATION' "
                        + "AND reporting_period_id = ?",
                BigDecimal.class, orgId, currentId);
        BigDecimal expectedDecreaseBaseline = jdbc.queryForObject(
                "SELECT COALESCE(SUM(co2e_tonnes), 0) FROM emission_records "
                        + "WHERE status = 'ACTIVE' AND organization_id = ? "
                        + "AND category = 'ELECTRICITY_LOCATION' "
                        + "AND reporting_period_id = ?",
                BigDecimal.class, orgId, baseId);
        assert2dp(expectedIncrease.subtract(expectedDecreaseBaseline),
                opportunities.get(0), "estimatedReductionTonnes");
        assertEquals("ENERGY_EFFICIENCY",
                opportunities.get(1).path("category").asText());
    }

    @Test
    void trendInsightsReportInsufficientDataWithoutInventingValues() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];

        JsonNode period = createPeriodRange(token, "Lonely FY2043",
                "2043-01-01", "2043-12-31");

        Api trend = postJson("/api/v1/analytics/trend-insights", token, "{}");
        assertEquals(200, trend.status(), trend.body().toString());
        JsonNode data = trend.body().path("data");
        String raw = trend.body().toString();

        assertEquals("carbonflow-deterministic-analytics",
                data.path("modelUsed").asText());
        assertEquals("Not enough persisted data: trend analysis requires at least "
                        + "two reporting periods with emissions.",
                data.path("summary").path("headline").asText());
        assertEquals("Lonely FY2043",
                data.path("summary").path("periodRange").asText());
        assert2dp(BigDecimal.ZERO, data.path("summary"), "confidenceScore");
        assertEquals(0, data.path("anomalies").size(), raw);
        assertEquals(0, data.path("reductionOpportunities").size(), raw);
        assertFalse(raw.contains("gemini"), raw);
        assertNotNull(period);
    }

    // ------------------------------------------------------------------
    // Period summary (Workstream B)
    // ------------------------------------------------------------------

    @Test
    void periodSummaryMatchesTheLedgerWithCoverageAndGovernance() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];

        JsonNode fy2043 = createPeriodRange(token, "FY2043", "2043-01-01", "2043-12-31");
        JsonNode fy2045 = createPeriodRange(token, "FY2045 (Empty)",
                "2045-01-01", "2045-12-31");
        JsonNode facility = createFacility(token, "Summary Plant " + suffix(),
                "P7S-" + suffix());
        String facilityId = facility.get("id").asText();
        String p43 = fy2043.get("id").asText();
        String p45 = fy2045.get("id").asText();

        seedAndRun(token, p43, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, p43, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");
        seedAndRun(token, p43, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "1000", "kWh");

        Api summary = getJson("/api/v1/analytics/periods/" + p43 + "/summary", token);
        assertEquals(200, summary.status(), summary.body().toString());
        JsonNode data = summary.body().path("data");

        // Identity straight from the tenant-scoped period row.
        assertEquals(orgId, data.path("organizationId").asText());
        assertEquals(p43, data.path("period").path("id").asText());
        assertEquals("FY2043", data.path("period").path("name").asText());
        assertEquals("2043-01-01", data.path("period").path("startDate").asText());
        assertEquals("2043-12-31", data.path("period").path("endDate").asText());
        assertEquals("OPEN", data.path("period").path("status").asText());

        // Totals == independent SQL aggregation at 4dp, both bases separate.
        assertPerspectivesAt(4,
                sums("organization_id = ? AND reporting_period_id = ?", orgId, p43),
                data.path("totals"));
        assertTrue(sums("organization_id = ? AND reporting_period_id = ?", orgId, p43)[1]
                        .subtract(sums("organization_id = ? AND reporting_period_id = ?",
                                orgId, p43)[2]).signum() != 0,
                "fixture must differ between the two Scope 2 perspectives");

        // Counts == raw row counts (never derived from presentation maths).
        int activities = jdbc.queryForObject(
                "SELECT count(*) FROM activity_data "
                        + "WHERE organization_id = ? AND reporting_period_id = ?",
                Integer.class, orgId, p43);
        int calculations = jdbc.queryForObject(
                "SELECT count(*) FROM calculations "
                        + "WHERE organization_id = ? AND reporting_period_id = ?",
                Integer.class, orgId, p43);
        int records = jdbc.queryForObject(
                "SELECT count(*) FROM emission_records "
                        + "WHERE organization_id = ? AND reporting_period_id = ? "
                        + "AND status = 'ACTIVE'",
                Integer.class, orgId, p43);
        assertEquals(3, records, "one record per seeded activity");
        assertEquals(activities, data.path("counts").path("activityData").asInt());
        assertEquals(calculations, data.path("counts").path("calculations").asInt());
        assertEquals(records, data.path("counts").path("emissionRecords").asInt());

        // Coverage: one tenant facility, one reporting in this period.
        assertEquals(1, data.path("coverage").path("facilitiesTotal").asInt());
        assertEquals(1, data.path("coverage").path("facilitiesWithEmissions").asInt());

        // Governance: unlocked, no audit yet.
        assertFalse(data.path("governance").path("locked").asBoolean());
        assertTrue(data.path("governance").path("auditId").isNull());
        assertTrue(data.path("governance").path("auditStatus").isNull());

        // The empty period: zero totals, zero counts, no coverage.
        Api empty = getJson("/api/v1/analytics/periods/" + p45 + "/summary", token);
        assertEquals(200, empty.status(), empty.body().toString());
        JsonNode emptyData = empty.body().path("data");
        assertPerspectivesAt(4, ZEROES, emptyData.path("totals"));
        assertEquals(0, emptyData.path("counts").path("activityData").asInt());
        assertEquals(0, emptyData.path("counts").path("calculations").asInt());
        assertEquals(0, emptyData.path("counts").path("emissionRecords").asInt());
        assertEquals(1, emptyData.path("coverage").path("facilitiesTotal").asInt());
        assertEquals(0, emptyData.path("coverage").path("facilitiesWithEmissions").asInt());

        // Governance follows persisted audit + lock state.
        JsonNode audit = createAudit(token, p43);
        Api withAudit = getJson("/api/v1/analytics/periods/" + p43 + "/summary", token);
        JsonNode gov = withAudit.body().path("data").path("governance");
        assertEquals(audit.get("id").asText(), gov.path("auditId").asText());
        assertEquals("DRAFT", gov.path("auditStatus").asText());
        assertFalse(gov.path("locked").asBoolean());

        jdbc.update("UPDATE reporting_periods SET status = 'LOCKED' WHERE id = ?", p43);
        Api locked = getJson("/api/v1/analytics/periods/" + p43 + "/summary", token);
        assertEquals(200, locked.status(), locked.body().toString());
        assertTrue(locked.body().path("data").path("governance").path("locked").asBoolean(),
                "summary must report the same lock guard that protects accounting");
        // Reporting reads stay available on a locked period (only writes 409).
        assertPerspectivesAt(4,
                sums("organization_id = ? AND reporting_period_id = ?", orgId, p43),
                locked.body().path("data").path("totals"));
    }

    // ------------------------------------------------------------------
    // Breakdown (Workstream A) + validation / tenant isolation
    // ------------------------------------------------------------------

    @Test
    void periodSummaryAndBreakdownFailSafelyAcrossTenantBoundaries() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();
        JsonNode periodA = createPeriodRange(tenantA[0], "FY2043",
                "2043-01-01", "2043-12-31");
        String periodAId = periodA.get("id").asText();

        // Malformed period id: safe 404 — never a 500 SQL cast error.
        Api malformed = getJson("/api/v1/analytics/periods/not-a-uuid/summary",
                tenantA[0]);
        assertEquals(404, malformed.status(), malformed.body().toString());
        assertEquals("REPORTING_PERIOD_NOT_FOUND",
                malformed.body().path("error").path("code").asText());

        // Cross-tenant: byte-identical to the malformed answer (no existence leak).
        Api foreign = getJson("/api/v1/analytics/periods/" + periodAId + "/summary",
                tenantB[0]);
        assertEquals(404, foreign.status(), foreign.body().toString());
        assertEquals(malformed.body().toString(), foreign.body().toString());

        // Missing dimension / unknown dimension / bad periodId / wrong combo.
        Api noDimension = getJson("/api/v1/analytics/breakdown", tenantA[0]);
        assertEquals(400, noDimension.status(), noDimension.body().toString());
        assertEquals("VALIDATION_ERROR",
                noDimension.body().path("error").path("code").asText());

        Api badDimension = getJson("/api/v1/analytics/breakdown?dimension=company",
                tenantA[0]);
        assertEquals(400, badDimension.status(), badDimension.body().toString());
        assertEquals("VALIDATION_ERROR",
                badDimension.body().path("error").path("code").asText());

        Api badPeriod = getJson(
                "/api/v1/analytics/breakdown?dimension=facility&periodId=not-a-uuid",
                tenantA[0]);
        assertEquals(404, badPeriod.status(), badPeriod.body().toString());
        assertEquals("REPORTING_PERIOD_NOT_FOUND",
                badPeriod.body().path("error").path("code").asText());

        Api foreignFilter = getJson(
                "/api/v1/analytics/breakdown?dimension=facility&periodId=" + periodAId,
                tenantB[0]);
        assertEquals(404, foreignFilter.status(), foreignFilter.body().toString());
        assertEquals(malformed.body().toString(), foreignFilter.body().toString());

        Api periodDimension = getJson(
                "/api/v1/analytics/breakdown?dimension=period&periodId=" + periodAId,
                tenantA[0]);
        assertEquals(400, periodDimension.status(), periodDimension.body().toString());
        assertEquals("VALIDATION_ERROR",
                periodDimension.body().path("error").path("code").asText());

        // Tenant B sees only its own (empty) world.
        Api breakdownB = getJson("/api/v1/analytics/breakdown?dimension=facility",
                tenantB[0]);
        assertEquals(200, breakdownB.status(), breakdownB.body().toString());
        assertEquals(0, breakdownB.body().path("data").path("rows").size());
        Api summaryB = getJson("/api/v1/analytics/periods/" + periodAId + "/summary",
                tenantB[0]);
        assertEquals(404, summaryB.status(), summaryB.body().toString());
    }

    @Test
    void breakdownGroupsByDimensionFromPersistedRecords() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];

        JsonNode fy2043 = createPeriodRange(token, "FY2043", "2043-01-01", "2043-12-31");
        JsonNode fy2044 = createPeriodRange(token, "FY2044", "2044-01-01", "2044-12-31");
        JsonNode fy2045 = createPeriodRange(token, "FY2045 (Empty)",
                "2045-01-01", "2045-12-31");

        // A legal entity whose facility never emitted, plus the seeded facility
        // that has no legal entity (both groupings exercised).
        Api entity = postJson("/api/v1/legal-entities", token,
                "{\"name\":\"Breakdown Holdings " + suffix() + "\",\"jurisdiction\":\"DE\"}");
        assertEquals(201, entity.status(), entity.body().toString());
        String entityId = entity.body().path("data").path("id").asText();

        Api linked = postJson("/api/v1/facilities", token,
                "{\"name\":\"Linked Plant " + suffix() + "\","
                        + "\"facilityCode\":\"P7L-" + suffix() + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\","
                        + "\"legalEntityId\":\"" + entityId + "\"}");
        assertEquals(201, linked.status(), linked.body().toString());
        String linkedId = linked.body().path("data").path("id").asText();

        JsonNode plain = createFacility(token, "Plain Plant " + suffix(),
                "P7P-" + suffix());
        String plainId = plain.get("id").asText();

        String p43 = fy2043.get("id").asText();
        String p44 = fy2044.get("id").asText();
        String p45 = fy2045.get("id").asText();

        seedAndRun(token, p43, plainId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, p43, plainId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");
        seedAndRun(token, p43, plainId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "1000", "kWh");
        seedAndRun(token, p44, plainId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "200", "kWh");

        // dimension=facility: every tenant facility (zero rows included),
        // ordered by name; no unlinked row since all records reference one.
        Api fac = getJson("/api/v1/analytics/breakdown?dimension=facility", token);
        assertEquals(200, fac.status(), fac.body().toString());
        JsonNode facData = fac.body().path("data");
        assertEquals("facility", facData.path("dimension").asText());
        JsonNode facRows = facData.path("rows");
        assertEquals(2, facRows.size(), "both tenant facilities listed");
        assertTrue(facRows.get(0).path("label").asText()
                        .compareTo(facRows.get(1).path("label").asText()) <= 0,
                "facility rows must be name-ordered");
        JsonNode plainRow = byField(facRows, "key", plainId);
        assertNotNull(plainRow, "seeded facility must appear");
        assertPerspectives(sums("organization_id = ? AND facility_id = ?", orgId, plainId),
                plainRow);
        JsonNode linkedRow = byField(facRows, "key", linkedId);
        assertNotNull(linkedRow, "zero-emission facility must appear");
        assertPerspectives(ZEROES, linkedRow);

        // dimension=legal_entity: unlinked emissions under "No legal entity",
        // the emitting facility's entity as a zero row.
        Api le = getJson("/api/v1/analytics/breakdown?dimension=legal_entity", token);
        assertEquals(200, le.status(), le.body().toString());
        JsonNode leRows = le.body().path("data").path("rows");
        assertEquals(2, leRows.size(), "entity row + unlinked row");
        JsonNode unlinkedRow = byField(leRows, "key", "");
        assertNotNull(unlinkedRow, "records without legal-entity linkage must show");
        assertEquals("No legal entity", unlinkedRow.path("label").asText());
        assertPerspectives(sums("organization_id = ?", orgId), unlinkedRow);
        JsonNode entityRow = byField(leRows, "key", entityId);
        assertNotNull(entityRow, "seeded legal entity must appear");
        assertPerspectives(ZEROES, entityRow);

        // dimension=scope: only scopes with records, in scope order; the two
        // Scope 2 perspectives stay strictly separate per row.
        Api sc = getJson("/api/v1/analytics/breakdown?dimension=scope", token);
        assertEquals(200, sc.status(), sc.body().toString());
        JsonNode scRows = sc.body().path("data").path("rows");
        assertEquals(2, scRows.size(), "SCOPE_1 and SCOPE_2 only");
        assertEquals("SCOPE_1", scRows.get(0).path("key").asText());
        assertEquals("SCOPE_2", scRows.get(1).path("key").asText());
        BigDecimal[] orgSums = sums("organization_id = ?", orgId);
        JsonNode s1 = scRows.get(0);
        assert2dp(orgSums[0], s1, "scope1Tonnes");
        assert2dp(BigDecimal.ZERO, s1, "scope2LocationTonnes");
        assert2dp(BigDecimal.ZERO, s1, "scope3Tonnes");
        assert2dp(orgSums[0], s1, "totalLocationBasedTonnes");
        assert2dp(orgSums[0], s1, "totalMarketBasedTonnes");
        JsonNode s2 = scRows.get(1);
        assert2dp(BigDecimal.ZERO, s2, "scope1Tonnes");
        assert2dp(orgSums[1], s2, "scope2LocationTonnes");
        assert2dp(orgSums[2], s2, "scope2MarketTonnes");
        assert2dp(orgSums[1], s2, "totalLocationBasedTonnes");
        assert2dp(orgSums[2], s2, "totalMarketBasedTonnes");

        // dimension=category: one row per persisted category, values == SQL.
        Api cat = getJson("/api/v1/analytics/breakdown?dimension=category", token);
        assertEquals(200, cat.status(), cat.body().toString());
        JsonNode catRows = cat.body().path("data").path("rows");
        assertEquals(3, catRows.size(), catRows.toString());
        for (String category : new String[] {"Stationary Combustion",
                "ELECTRICITY_LOCATION", "ELECTRICITY_MARKET"}) {
            JsonNode row = byField(catRows, "key", category);
            assertNotNull(row, "category row must appear: " + category);
            BigDecimal[] expected =
                    sums("organization_id = ? AND category = ?", orgId, category);
            assert2dp(expected[0], row, "scope1Tonnes");
            assert2dp(expected[1], row, "scope2LocationTonnes");
            assert2dp(expected[2], row, "scope2MarketTonnes");
            assert2dp(expected[3], row, "scope3Tonnes");
        }

        // dimension=period: every real period in start-date order, empty = 0.
        Api per = getJson("/api/v1/analytics/breakdown?dimension=period", token);
        assertEquals(200, per.status(), per.body().toString());
        JsonNode perRows = per.body().path("data").path("rows");
        assertEquals(3, perRows.size(), "one row per real period, none synthetic");
        assertEquals(p43, perRows.get(0).path("key").asText());
        assertEquals(p44, perRows.get(1).path("key").asText());
        assertEquals(p45, perRows.get(2).path("key").asText());
        assertPerspectives(sums("organization_id = ? AND reporting_period_id = ?",
                orgId, p43), perRows.get(0));
        assertPerspectives(sums("organization_id = ? AND reporting_period_id = ?",
                orgId, p44), perRows.get(1));
        assertPerspectives(ZEROES, perRows.get(2));
        String raw = per.body().toString();
        assertFalse(raw.contains("period-2024"), "no synthetic months: " + raw);

        // periodId filter scopes the aggregation to that period only.
        Api filtered = getJson(
                "/api/v1/analytics/breakdown?dimension=facility&periodId=" + p44, token);
        assertEquals(200, filtered.status(), filtered.body().toString());
        JsonNode filteredData = filtered.body().path("data");
        assertEquals(p44, filteredData.path("periodId").asText());
        JsonNode filteredPlain = byField(filteredData.path("rows"), "key", plainId);
        assertNotNull(filteredPlain);
        assertPerspectives(sums("organization_id = ? AND facility_id = ? "
                        + "AND reporting_period_id = ?", orgId, plainId, p44),
                filteredPlain);
        Api unfiltered = getJson("/api/v1/analytics/breakdown?dimension=facility", token);
        assertTrue(unfiltered.body().path("data").path("periodId").isNull(),
                "no periodId means the whole tenant ledger");
    }
}
