package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 7 task 7.6 — CSV export hardening (API.md §2.9, ADR-018).
 *
 * <p>Proves the three documented hardenings against the Node oracle:
 * every cell passes {@code csvCell} (formula injection neutralised, quotes
 * doubled), the four query filters are validated (400 on malformed values,
 * empty export on foreign ids — no existence leak), and output is
 * deterministic (identical bytes on repeat, {@code \n} endings, ISO-8601 UTC
 * timestamps). Fixtures live in freshly registered organizations, so a
 * cross-tenant leak would surface as an unexpected row.
 */
class ReportExportTest extends AccountingTestBase {

    private static final String HEADER = "Emission Record ID,Reporting Period,"
            + "Facility Name,Facility Code,Scope,Category,Scope 2 Method,"
            + "Activity Type,Original Quantity,Unit,Calculation Hash,"
            + "CO2e Tonnes,Status,Timestamp";

    private record Raw(int status, String contentType, String body) {
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private String[] isolatedTenant() throws Exception {
        String email = "csv-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"CSV Tenant " + suffix() + "\",\"country\":\"DE\","
                        + "\"industry\":\"Logistics\",\"fullName\":\"CSV Founder\","
                        + "\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(201, registration.status(), registration.body().toString());
        String orgId = registration.body().path("data").path("organization").path("id").asText();

        String platformToken = loginToken(PLATFORM_EMAIL, PASSWORD);
        Api approved = postJson("/api/v1/platform/tenants/" + orgId + "/approve",
                platformToken, null);
        assertEquals(200, approved.status(), approved.body().toString());

        return new String[] {loginToken(email, PASSWORD), orgId};
    }

    private void seedAndRun(String token, String periodId, String facilityId,
                            String scope, String category, String activityType,
                            String quantity, String unit) throws Exception {
        JsonNode activity = createActivity(token, periodId, facilityId,
                scope, category, activityType, quantity, unit);
        Api run = runCalculation(token, activity.path("id").asText());
        assertEquals(200, run.status(), run.body().toString());
    }

    /** Raw CSV fetch — bypasses the JSON helpers, keeps status + headers. */
    private Raw export(String uri, String bearerToken) throws Exception {
        var request = get(uri);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Raw(result.getResponse().getStatus(),
                result.getResponse().getContentType(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Non-empty {@code \n}-separated lines: header first, then data rows. */
    private static String[] lines(String csv) {
        List<String> kept = new ArrayList<>();
        for (String line : csv.split("\n", -1)) {
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        return kept.toArray(new String[0]);
    }

    private static String[] cells(String line) {
        return line.split(",", -1);
    }

    private static String unquote(String cell) {
        assertTrue(cell.length() >= 2 && cell.startsWith("\"") && cell.endsWith("\""),
                "data cell must be quoted: " + cell);
        return cell.substring(1, cell.length() - 1);
    }

    // ------------------------------------------------------------------
    // Injection guard
    // ------------------------------------------------------------------

    @Test
    void csvExportNeutralisesFormulaInjectionInEveryCell() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];

        // Facility name carries a formula WITH embedded quotes (guard + escape),
        // the period a leading '+', categories leading '@' and '-'.
        Api period = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"+FY2043\",\"startDate\":\"2043-01-01\","
                        + "\"endDate\":\"2043-12-31\"}");
        assertEquals(201, period.status(), period.body().toString());
        String p43 = period.body().path("data").path("id").asText();

        Api facility = postJson("/api/v1/facilities", token,
                "{\"name\":\"=IMPORTDATA(\\\"x\\\")\","
                        + "\"facilityCode\":\"P7X-" + suffix() + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");
        assertEquals(201, facility.status(), facility.body().toString());
        String facilityId = facility.body().path("data").path("id").asText();

        seedAndRun(token, p43, facilityId, "SCOPE_1", "@TOTAL",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, p43, facilityId, "SCOPE_2", "-ELEC",
                "GRID_ELECTRICITY_US", "1000", "kWh");

        Raw csv = export("/api/v1/reports/export-csv", token);
        assertEquals(200, csv.status(), csv.body());
        assertNotNull(csv.contentType());
        assertTrue(csv.contentType().contains("text/csv"), csv.contentType());

        String[] rows = lines(csv.body());
        assertEquals(HEADER, rows[0], "header must match the Node contract verbatim");
        assertEquals(3, rows.length, "header + 2 rows: " + csv.body());

        for (int i = 1; i < rows.length; i++) {
            String[] row = cells(rows[i]);
            assertEquals(14, row.length, "14 cells per row: " + rows[i]);
            for (String cell : row) {
                unquote(cell); // every data cell is quoted (Node csvCell parity)
            }
        }

        // Formula injection: prefixed with an apostrophe inside the quotes.
        String row1 = rows[1];
        String row2 = rows[2];
        String all = row1 + "\n" + row2;
        assertTrue(all.contains("\"'=IMPORTDATA(\"\"x\"\")\""),
                "facility-name formula must be escaped: " + all);
        assertTrue(all.contains("\"+FY2043\"") || all.contains("\"'+FY2043\""),
                "leading + must be neutralised: " + all);
        assertTrue(all.contains("\"'@TOTAL\""), "leading @ must be neutralised: " + all);
        assertTrue(all.contains("\"'-ELEC\""), "leading - must be neutralised: " + all);
        // No cell ever begins raw with a formula character.
        for (int i = 1; i < rows.length; i++) {
            for (String cell : cells(rows[i])) {
                char first = cell.charAt(0);
                assertFalse(first == '=' || first == '+' || first == '-'
                                || first == '@',
                        "unescaped formula character at cell start: " + cell);
            }
        }

        // Timestamps are ISO-8601 UTC.
        for (int i = 1; i < rows.length; i++) {
            String timestamp = unquote(cells(rows[i])[13]);
            assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"),
                    "ISO-8601 UTC expected: " + timestamp);
        }
    }

    // ------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------

    @Test
    void csvExportHonorsValidatedFilters() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();
        String token = tenantA[0];

        JsonNode p1 = createPeriod(token, "FY2043", "2043-01-01", "2043-12-31");
        JsonNode p2 = createPeriod(token, "FY2044", "2044-01-01", "2044-12-31");
        String periodOne = p1.get("id").asText();
        String periodTwo = p2.get("id").asText();
        String facilityOne =
                createFacility(token, "Filter Plant One " + suffix(), "P7Q-1-" + suffix())
                        .get("id").asText();
        String facilityTwo =
                createFacility(token, "Filter Plant Two " + suffix(), "P7Q-2-" + suffix())
                        .get("id").asText();

        seedAndRun(token, periodOne, facilityOne, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, periodOne, facilityOne, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");
        seedAndRun(token, periodOne, facilityTwo, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "1000", "kWh");
        seedAndRun(token, periodTwo, facilityTwo, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");

        // Tenant B owns exactly one period + row for the foreign-filter case.
        String periodB =
                createPeriod(tenantB[0], "Foreign FY", "2046-01-01", "2046-12-31")
                        .get("id").asText();
        String facilityB =
                createFacility(tenantB[0], "Foreign Plant " + suffix(), "P7Q-9-" + suffix())
                        .get("id").asText();
        seedAndRun(tenantB[0], periodB, facilityB, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");

        // Unfiltered: header + all four tenant-A rows.
        Raw all = export("/api/v1/reports/export-csv", token);
        assertEquals(200, all.status(), all.body());
        assertEquals(5, lines(all.body()).length, all.body());

        // periodId filter.
        Raw byPeriod = export("/api/v1/reports/export-csv?periodId=" + periodOne, token);
        assertEquals(200, byPeriod.status(), byPeriod.body());
        String[] periodRows = lines(byPeriod.body());
        assertEquals(4, periodRows.length, byPeriod.body());
        for (int i = 1; i < periodRows.length; i++) {
            assertEquals("FY2043", unquote(cells(periodRows[i])[1]));
        }

        // facilityId filter.
        Raw byFacility =
                export("/api/v1/reports/export-csv?facilityId=" + facilityTwo, token);
        assertEquals(200, byFacility.status(), byFacility.body());
        assertEquals(3, lines(byFacility.body()).length, byFacility.body());

        // scope filter.
        Raw byScope = export("/api/v1/reports/export-csv?scope=SCOPE_1", token);
        assertEquals(200, byScope.status(), byScope.body());
        String[] scopeRows = lines(byScope.body());
        assertEquals(3, scopeRows.length, byScope.body());
        for (int i = 1; i < scopeRows.length; i++) {
            assertEquals("SCOPE_1", unquote(cells(scopeRows[i])[4]));
        }

        // scope2Type filter — each perspective exactly one row.
        Raw byLocation =
                export("/api/v1/reports/export-csv?scope2Type=LOCATION_BASED", token);
        assertEquals(200, byLocation.status(), byLocation.body());
        assertEquals(2, lines(byLocation.body()).length, byLocation.body());
        assertEquals("ELECTRICITY_LOCATION",
                unquote(cells(lines(byLocation.body())[1])[5]));
        Raw byMarket =
                export("/api/v1/reports/export-csv?scope2Type=MARKET_BASED", token);
        assertEquals(200, byMarket.status(), byMarket.body());
        assertEquals(2, lines(byMarket.body()).length, byMarket.body());
        assertEquals("ELECTRICITY_MARKET",
                unquote(cells(lines(byMarket.body())[1])[5]));

        // Combined filters intersect.
        Raw combined = export("/api/v1/reports/export-csv?periodId=" + periodOne
                + "&facilityId=" + facilityTwo + "&scope=SCOPE_2", token);
        assertEquals(200, combined.status(), combined.body());
        assertEquals(2, lines(combined.body()).length, combined.body());

        // Blank params behave as absent.
        Raw blank = export("/api/v1/reports/export-csv?periodId=", token);
        assertEquals(200, blank.status(), blank.body());
        assertEquals(5, lines(blank.body()).length, blank.body());

        // Malformed values answer 400 — never a 500 SQL cast error.
        Raw badPeriod =
                export("/api/v1/reports/export-csv?periodId=not-a-uuid", token);
        assertEquals(400, badPeriod.status(), badPeriod.body);
        assertTrue(badPeriod.body.contains("VALIDATION_ERROR"), badPeriod.body);
        assertTrue(badPeriod.body.contains("periodId must be a valid UUID."),
                badPeriod.body);
        Raw badFacility =
                export("/api/v1/reports/export-csv?facilityId=not-a-uuid", token);
        assertEquals(400, badFacility.status(), badFacility.body);
        Raw badScope = export("/api/v1/reports/export-csv?scope=SCOPE_4", token);
        assertEquals(400, badScope.status(), badScope.body);
        assertTrue(badScope.body.contains(
                "scope must be one of: SCOPE_1, SCOPE_2, SCOPE_3."), badScope.body);
        Raw badBasis =
                export("/api/v1/reports/export-csv?scope2Type=TOTAL", token);
        assertEquals(400, badBasis.status(), badBasis.body);
        assertTrue(badBasis.body.contains(
                "scope2Type must be one of: LOCATION_BASED, MARKET_BASED."),
                badBasis.body);

        // A well-formed foreign id filters to an EMPTY export (200, header
        // only) — no existence leak either way.
        Raw foreign = export("/api/v1/reports/export-csv?periodId=" + periodB, token);
        assertEquals(200, foreign.status(), foreign.body);
        assertEquals(1, lines(foreign.body).length, foreign.body);
        assertFalse(foreign.body.contains("Foreign Plant"), foreign.body);
    }

    // ------------------------------------------------------------------
    // Determinism + tenancy + authentication
    // ------------------------------------------------------------------

    @Test
    void csvExportIsDeterministicTenantScopedAndAuthenticated() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();

        String periodA =
                createPeriod(tenantA[0], "FY2043", "2043-01-01", "2043-12-31")
                        .get("id").asText();
        String facilityA =
                createFacility(tenantA[0], "Owner Plant " + suffix(), "P7D-" + suffix())
                        .get("id").asText();
        seedAndRun(tenantA[0], periodA, facilityA, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");

        String periodB =
                createPeriod(tenantB[0], "Foreign FY", "2046-01-01", "2046-12-31")
                        .get("id").asText();
        String facilityB =
                createFacility(tenantB[0], "Foreign Plant " + suffix(), "P7E-" + suffix())
                        .get("id").asText();
        seedAndRun(tenantB[0], periodB, facilityB, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");

        // Deterministic: identical bytes on repeat.
        Raw first = export("/api/v1/reports/export-csv", tenantA[0]);
        assertEquals(200, first.status(), first.body);
        Raw second = export("/api/v1/reports/export-csv", tenantA[0]);
        assertEquals(200, second.status(), second.body);
        assertEquals(first.body, second.body,
                "identical ledgers must produce identical CSVs");
        assertTrue(first.body.endsWith("\n"), "platform-independent \\n endings");
        assertFalse(first.body.contains("\r"), "no platform line endings");
        assertFalse(first.body.contains("Foreign Plant"),
                "cross-tenant rows must never appear");

        // Tenant B sees exactly its own row.
        Raw tenantBRows = export("/api/v1/reports/export-csv", tenantB[0]);
        assertEquals(200, tenantBRows.status(), tenantBRows.body);
        assertEquals(2, lines(tenantBRows.body).length, tenantBRows.body);
        assertTrue(tenantBRows.body.contains("Foreign Plant"), tenantBRows.body);
        assertFalse(tenantBRows.body.contains("Owner Plant"), tenantBRows.body);

        // Authentication is required.
        Raw anonymous = export("/api/v1/reports/export-csv", null);
        assertEquals(401, anonymous.status(), anonymous.body);
    }

    // Local fixture helpers -------------------------------------------------

    private JsonNode createPeriod(String token, String name, String start, String end)
            throws Exception {
        Api api = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"" + name + "\",\"startDate\":\"" + start
                        + "\",\"endDate\":\"" + end + "\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }
}
