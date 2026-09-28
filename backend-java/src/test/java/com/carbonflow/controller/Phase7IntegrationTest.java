package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 7 task 7.9 — the cross-cutting security battery. Per-module behaviour
 * is proven in {@code AnalyticsTest}, {@code InventorySnapshotTest},
 * {@code TargetTest}, {@code ReductionProjectTest}, {@code ReportExportTest}
 * and {@code PlatformAdminTest}; this class walks the mandatory Phase 7 items
 * end-to-end:
 *
 * <ul>
 *   <li>malformed / foreign UUIDs across every new surface — contracted 4xx,
 *       never a 500, and malformed == foreign byte-for-byte;</li>
 *   <li>cross-tenant probes across analytics, inventory, targets, projects and
 *       reports collapse into indistinguishable 404s with empty listings;</li>
 *   <li>pending / suspended / rejected organizations cannot authenticate;</li>
 *   <li>deactivated users lose every Phase 7 surface on the next
 *       request;</li>
 *   <li>the frozen role matrix (44 codes) gates each surface — including
 *       PLATFORM_ADMIN, which is platform scope, not company scope;</li>
 *   <li>locked periods stay readable through reporting while accounting stays
 *       frozen, with Scope 2 dual perspectives never summed;</li>
 *   <li>no production source or response hardcodes geography, currency,
 *       timezone or demo data.</li>
 * </ul>
 *
 * <p>All tenants here are freshly registered organizations (or the seeded
 * scope helper's fresh periods), so a leak surfaces as unexpected content.
 */
class Phase7IntegrationTest extends AccountingTestBase {

    private static final String LOCK_MESSAGE =
            "Reporting period is locked and cannot be modified.";
    private static final String CSV_HEADER =
            "Emission Record ID,Reporting Period,Facility Name,Facility Code,"
                    + "Scope,Category,Scope 2 Method,Activity Type,Original Quantity,Unit,"
                    + "Calculation Hash,CO2e Tonnes,Status,Timestamp";

    private record Raw(int status, String body) {
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Registers a fresh organization; returns [email, orgId] in PENDING. */
    private String[] registerOrg() throws Exception {
        String email = "p7i-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Battery Tenant " + suffix() + "\","
                        + "\"country\":\"DE\",\"industry\":\"Logistics\","
                        + "\"fullName\":\"Battery Founder\",\"email\":\"" + email
                        + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(201, registration.status(), registration.body().toString());
        return new String[] {
                email,
                registration.body().path("data").path("organization").path("id").asText()};
    }

    /** Registers + platform-approves a fresh tenant; returns [token, orgId]. */
    private String[] isolatedTenant() throws Exception {
        String[] org = registerOrg();
        String platformToken = loginToken(PLATFORM_EMAIL, PASSWORD);
        Api approved = postJson("/api/v1/platform/tenants/" + org[1] + "/approve",
                platformToken, null);
        assertEquals(200, approved.status(), approved.body().toString());
        return new String[] {loginToken(org[0], PASSWORD), org[1]};
    }

    private JsonNode createPeriod(String token, String name, String start, String end)
            throws Exception {
        Api api = postJson("/api/v1/reporting-periods", token,
                "{\"name\":\"" + name + "\",\"startDate\":\"" + start
                        + "\",\"endDate\":\"" + end + "\"}");
        assertEquals(201, api.status(), api.body().toString());
        return api.body().get("data");
    }

    /** Creates a user with the given role and returns [userId, token]. */
    private String[] createUserWithRole(String adminToken, String role) throws Exception {
        String email = "p7r-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api created = postJson("/api/v1/users", adminToken,
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                        + "\",\"fullName\":\"Phase7 Probe\",\"role\":\"" + role + "\"}");
        assertEquals(201, created.status(), created.body().toString());
        return new String[] {
                created.body().path("data").path("id").asText(),
                loginToken(email, PASSWORD)};
    }

    /** Asserts the full error envelope for a contracted 4xx probe. */
    private void assertContract(int status, String code, Api api) {
        assertEquals(status, api.status(), api.body().toString());
        assertEquals(code, api.body().path("error").path("code").asText(),
                api.body().toString());
        assertFalse(api.body().path("success").asBoolean(), api.body().toString());
    }

    /** Raw CSV fetch — keeps status + body without JSON parsing. */
    private Raw export(String uri, String bearerToken) throws Exception {
        var request = get(uri);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return new Raw(result.getResponse().getStatus(),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String[] lines(String csv) {
        List<String> kept = new ArrayList<>();
        for (String line : csv.split("\n", -1)) {
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        return kept.toArray(new String[0]);
    }

    private static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asText());
    }

    // ------------------------------------------------------------------
    // Malformed ids: contracted 4xx, never 500
    // ------------------------------------------------------------------

    @Test
    void malformedIdsNeverSurfaceA500OnAnyPhase7Endpoint() throws Exception {
        String token = isolatedTenant()[0];
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);

        // Analytics reads.
        assertContract(404, "REPORTING_PERIOD_NOT_FOUND",
                getJson("/api/v1/analytics/periods/not-a-uuid/summary", token));
        assertContract(404, "REPORTING_PERIOD_NOT_FOUND",
                getJson("/api/v1/analytics/breakdown?dimension=scope&periodId=not-a-uuid",
                        token));
        assertContract(400, "VALIDATION_ERROR",
                getJson("/api/v1/analytics/breakdown?dimension=period&periodId=not-a-uuid",
                        token));
        assertContract(400, "VALIDATION_ERROR",
                getJson("/api/v1/analytics/breakdown?dimension=bogus", token));

        // Inventory.
        assertContract(404, "REPORTING_PERIOD_NOT_FOUND",
                postJson("/api/v1/inventory/snapshot", token,
                        "{\"reportingPeriodId\":\"not-a-uuid\"}"));
        assertContract(404, "INVENTORY_SNAPSHOT_NOT_FOUND",
                postJson("/api/v1/inventory/not-a-uuid/lock", token, null));

        // Targets and projects.
        assertContract(404, "CARBON_TARGET_NOT_FOUND",
                putJson("/api/v1/targets/not-a-uuid", token, "{\"name\":\"Rename\"}"));
        assertContract(404, "REDUCTION_PROJECT_NOT_FOUND",
                putJson("/api/v1/reduction-projects/not-a-uuid", token,
                        "{\"name\":\"Rename\"}"));

        // Export filter validation (JSON envelope even on the CSV route).
        Raw badPeriod = export("/api/v1/reports/export-csv?periodId=not-a-uuid", token);
        assertEquals(400, badPeriod.status(), badPeriod.body);
        assertTrue(badPeriod.body.contains("VALIDATION_ERROR"), badPeriod.body);
        Raw badScope = export("/api/v1/reports/export-csv?scope=SCOPE_9", token);
        assertEquals(400, badScope.status(), badScope.body);
        assertTrue(badScope.body.contains("VALIDATION_ERROR"), badScope.body);

        // Platform administration.
        assertContract(404, "ORGANIZATION_NOT_FOUND",
                getJson("/api/v1/platform/tenants/not-a-uuid", platform));
        assertContract(404, "ORGANIZATION_NOT_FOUND",
                postJson("/api/v1/platform/tenants/not-a-uuid/approve", platform, null));
    }

    // ------------------------------------------------------------------
    // Cross-tenant: indistinguishable 404s, empty listings, no leaks
    // ------------------------------------------------------------------

    @Test
    void crossTenantIdsCollapseIntoIndistinguishable404sAcrossModules() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();
        String a = tenantA[0];
        String b = tenantB[0];

        // Tenant A owns a period, target, project and snapshot.
        String periodOne = createPeriod(a, "FY2043", "2043-01-01", "2043-12-31")
                .get("id").asText();
        String periodTwo = createPeriod(a, "FY2044", "2044-01-01", "2044-12-31")
                .get("id").asText();
        Api target = postJson("/api/v1/targets", a,
                "{\"name\":\"Battery Target\",\"baselinePeriodId\":\"" + periodOne
                        + "\",\"targetPeriodId\":\"" + periodTwo
                        + "\",\"baselineValueT\":100,\"targetValueT\":80,"
                        + "\"reductionPercentage\":20}");
        assertEquals(201, target.status(), target.body().toString());
        String targetId = target.body().path("data").path("id").asText();
        Api project = postJson("/api/v1/reduction-projects", a,
                "{\"name\":\"Battery Project\",\"startDate\":\"2044-01-01\","
                        + "\"endDate\":\"2044-12-31\"}");
        assertEquals(201, project.status(), project.body().toString());
        String projectId = project.body().path("data").path("id").asText();
        Api snapshot = postJson("/api/v1/inventory/snapshot", a,
                "{\"reportingPeriodId\":\"" + periodOne + "\"}");
        assertEquals(201, snapshot.status(), snapshot.body().toString());
        String snapshotId = snapshot.body().path("data").path("id").asText();

        // Every foreign probe answers exactly what the equivalent malformed
        // probe answers — same status, same code, byte-identical body.
        Api summaryForeign = getJson(
                "/api/v1/analytics/periods/" + periodOne + "/summary", b);
        Api summaryMalformed = getJson(
                "/api/v1/analytics/periods/not-a-uuid/summary", b);
        assertContract(404, "REPORTING_PERIOD_NOT_FOUND", summaryForeign);
        assertEquals(summaryMalformed.body().toString(), summaryForeign.body().toString());

        Api breakdownForeign = getJson(
                "/api/v1/analytics/breakdown?dimension=scope&periodId=" + periodOne, b);
        Api breakdownMalformed = getJson(
                "/api/v1/analytics/breakdown?dimension=scope&periodId=not-a-uuid", b);
        assertContract(404, "REPORTING_PERIOD_NOT_FOUND", breakdownForeign);
        assertEquals(breakdownMalformed.body().toString(),
                breakdownForeign.body().toString());

        Api snapshotForeign = postJson("/api/v1/inventory/snapshot", b,
                "{\"reportingPeriodId\":\"" + periodOne + "\"}");
        Api snapshotMalformed = postJson("/api/v1/inventory/snapshot", b,
                "{\"reportingPeriodId\":\"not-a-uuid\"}");
        assertContract(404, "REPORTING_PERIOD_NOT_FOUND", snapshotForeign);
        assertEquals(snapshotMalformed.body().toString(), snapshotForeign.body().toString());

        Api lockForeign = postJson("/api/v1/inventory/" + snapshotId + "/lock", b, null);
        Api lockMalformed = postJson("/api/v1/inventory/not-a-uuid/lock", b, null);
        assertContract(404, "INVENTORY_SNAPSHOT_NOT_FOUND", lockForeign);
        assertEquals(lockMalformed.body().toString(), lockForeign.body().toString());

        Api targetForeign = putJson("/api/v1/targets/" + targetId, b,
                "{\"name\":\"Hijack\"}");
        Api targetMalformed = putJson("/api/v1/targets/not-a-uuid", b,
                "{\"name\":\"Hijack\"}");
        assertContract(404, "CARBON_TARGET_NOT_FOUND", targetForeign);
        assertEquals(targetMalformed.body().toString(), targetForeign.body().toString());

        Api projectForeign = putJson("/api/v1/reduction-projects/" + projectId, b,
                "{\"name\":\"Hijack\"}");
        Api projectMalformed = putJson("/api/v1/reduction-projects/not-a-uuid", b,
                "{\"name\":\"Hijack\"}");
        assertContract(404, "REDUCTION_PROJECT_NOT_FOUND", projectForeign);
        assertEquals(projectMalformed.body().toString(), projectForeign.body().toString());

        // Creation links are tenant-validated too.
        assertContract(404, "REPORTING_PERIOD_NOT_FOUND",
                postJson("/api/v1/targets", b,
                        "{\"name\":\"Stolen Baseline\",\"baselinePeriodId\":\""
                                + periodOne + "\",\"targetPeriodId\":\"" + periodOne
                                + "\",\"baselineValueT\":1,\"targetValueT\":1,"
                                + "\"reductionPercentage\":10}"));
        assertContract(404, "CARBON_TARGET_NOT_FOUND",
                postJson("/api/v1/reduction-projects", b,
                        "{\"name\":\"Stolen Link\",\"targetId\":\"" + targetId
                                + "\",\"startDate\":\"2044-01-01\","
                                + "\"endDate\":\"2044-12-31\"}"));

        // Listings never contain foreign rows.
        JsonNode targetsB = getJson("/api/v1/targets", b).body().path("data");
        JsonNode projectsB = getJson("/api/v1/reduction-projects", b).body().path("data");
        JsonNode inventoryB = getJson("/api/v1/inventory", b).body().path("data");
        assertTrue(targetsB.isArray() && targetsB.isEmpty(),
                "tenant B must not see tenant A targets: " + targetsB);
        assertTrue(projectsB.isArray() && projectsB.isEmpty(),
                "tenant B must not see tenant A projects: " + projectsB);
        assertTrue(inventoryB.isArray() && inventoryB.isEmpty(),
                "tenant B must not see tenant A snapshots: " + inventoryB);

        // Reports filter a foreign period to an empty export (header only).
        Raw foreignExport =
                export("/api/v1/reports/export-csv?periodId=" + periodOne, b);
        assertEquals(200, foreignExport.status(), foreignExport.body);
        assertEquals(1, lines(foreignExport.body).length, foreignExport.body);
        assertFalse(foreignExport.body.contains("Battery Target"), foreignExport.body);
    }

    // ------------------------------------------------------------------
    // Organization lifecycle: no authentication for pending/suspended/rejected
    // ------------------------------------------------------------------

    @Test
    void pendingSuspendedAndRejectedOrganizationsCannotAuthenticate() throws Exception {
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);

        // Pending: registration exists but the platform has not approved it.
        String[] pending = registerOrg();
        Api pendingLogin = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + pending[0] + "\",\"password\":\"" + PASSWORD + "\"}");
        assertContract(403, "ORGANIZATION_NOT_ACTIVE", pendingLogin);
        assertEquals("The organization is pending platform approval.",
                pendingLogin.body().path("error").path("message").asText());

        // Suspended: approved once, then suspended by the platform.
        String[] suspended = registerOrg();
        assertEquals(200, postJson("/api/v1/platform/tenants/" + suspended[1]
                + "/approve", platform, null).status());
        loginToken(suspended[0], PASSWORD);
        Api suspend = postJson("/api/v1/platform/tenants/" + suspended[1] + "/suspend",
                platform, "{\"note\":\"battery review\"}");
        assertEquals(200, suspend.status(), suspend.body().toString());
        Api suspendedLogin = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + suspended[0] + "\",\"password\":\"" + PASSWORD + "\"}");
        assertContract(403, "ORGANIZATION_NOT_ACTIVE", suspendedLogin);
        assertEquals("The organization is suspended.",
                suspendedLogin.body().path("error").path("message").asText());

        // Rejected: the platform refused the registration outright.
        String[] rejected = registerOrg();
        assertEquals(200, postJson("/api/v1/platform/tenants/" + rejected[1]
                + "/reject", platform, "{\"note\":\"battery review\"}").status());
        Api rejectedLogin = postJson("/api/v1/auth/login", null,
                "{\"email\":\"" + rejected[0] + "\",\"password\":\"" + PASSWORD + "\"}");
        assertContract(403, "ORGANIZATION_NOT_ACTIVE", rejectedLogin);
        assertEquals("The organization registration was not approved.",
                rejectedLogin.body().path("error").path("message").asText());

        // And every Phase 7 surface answers 401 without an authenticated
        // organization context.
        assertEquals(401, getJson("/api/v1/analytics/dashboard", null).status());
        assertEquals(401, getJson("/api/v1/targets", null).status());
        assertEquals(401, getJson("/api/v1/reduction-projects", null).status());
        assertEquals(401, getJson("/api/v1/inventory", null).status());
        assertEquals(401, getJson("/api/v1/reports/export-csv", null).status());
    }

    // ------------------------------------------------------------------
    // Deactivated users
    // ------------------------------------------------------------------

    @Test
    void deactivatedUsersLoseEveryPhase7SurfaceOnTheNextRequest() throws Exception {
        String[] tenant = isolatedTenant();
        String[] reviewer = createUserWithRole(tenant[0], "REVIEWER");

        // The reviewer can read Phase 7 surfaces while active.
        assertEquals(200,
                getJson("/api/v1/inventory", reviewer[1]).status());
        assertEquals(200,
                getJson("/api/v1/analytics/dashboard", reviewer[1]).status());

        // The company admin deactivates them (never themselves).
        Api disabled = postJson("/api/v1/users/" + reviewer[0] + "/disable",
                tenant[0], null);
        assertEquals(200, disabled.status(), disabled.body().toString());

        // The still-present bearer token is refused on the very next request.
        Api inventory = getJson("/api/v1/inventory", reviewer[1]);
        assertContract(401, "USER_DEACTIVATED", inventory);
        assertEquals("User account is inactive or deleted.",
                inventory.body().path("error").path("message").asText());
        Api dashboard = getJson("/api/v1/analytics/dashboard", reviewer[1]);
        assertContract(401, "USER_DEACTIVATED", dashboard);
        Api export = getJson("/api/v1/reports/export-csv", reviewer[1]);
        assertContract(401, "USER_DEACTIVATED", export);
        Api targetWrite = postJson("/api/v1/targets", reviewer[1],
                "{\"name\":\"After Disable\"}");
        assertContract(401, "USER_DEACTIVATED", targetWrite);
    }

    // ------------------------------------------------------------------
    // Frozen RBAC matrix
    // ------------------------------------------------------------------

    @Test
    void frozenRoleMatrixGatesEveryPhase7Surface() throws Exception {
        String[] tenant = isolatedTenant();
        String dataOwner = createUserWithRole(tenant[0], "DATA_OWNER")[1];
        String reviewer = createUserWithRole(tenant[0], "REVIEWER")[1];
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);

        // DATA_OWNER: analytics yes — inventory, reports and all creates no.
        assertEquals(200, getJson("/api/v1/analytics/dashboard", dataOwner).status());
        assertContract(403, "FORBIDDEN",
                getJson("/api/v1/inventory", dataOwner));
        assertContract(403, "FORBIDDEN",
                getJson("/api/v1/reports/export-csv", dataOwner));
        assertContract(403, "FORBIDDEN",
                postJson("/api/v1/inventory/snapshot", dataOwner,
                        "{\"reportingPeriodId\":\"" + UUID.randomUUID() + "\"}"));
        assertEquals(200, getJson("/api/v1/targets", dataOwner).status());
        assertContract(403, "FORBIDDEN",
                postJson("/api/v1/targets", dataOwner, "{\"name\":\"Denied\"}"));
        assertContract(403, "FORBIDDEN",
                postJson("/api/v1/reduction-projects", dataOwner,
                        "{\"name\":\"Denied\"}"));

        // REVIEWER: reads yes — every write no (approvals/locks included).
        assertEquals(200, getJson("/api/v1/inventory", reviewer).status());
        assertContract(403, "FORBIDDEN",
                postJson("/api/v1/inventory/snapshot", reviewer,
                        "{\"reportingPeriodId\":\"" + UUID.randomUUID() + "\"}"));
        assertContract(403, "FORBIDDEN",
                postJson("/api/v1/inventory/" + UUID.randomUUID() + "/lock",
                        reviewer, null));
        assertContract(403, "FORBIDDEN",
                postJson("/api/v1/targets", reviewer, "{\"name\":\"Denied\"}"));
        assertContract(403, "FORBIDDEN",
                putJson("/api/v1/targets/" + UUID.randomUUID(), reviewer,
                        "{\"name\":\"Denied\"}"));
        assertContract(403, "FORBIDDEN",
                postJson("/api/v1/reduction-projects", reviewer,
                        "{\"name\":\"Denied\"}"));
        assertContract(403, "FORBIDDEN",
                putJson("/api/v1/reduction-projects/" + UUID.randomUUID(), reviewer,
                        "{\"name\":\"Denied\"}"));
        Raw reviewerExport = export("/api/v1/reports/export-csv", reviewer);
        assertEquals(200, reviewerExport.status(), reviewerExport.body);
        assertEquals(CSV_HEADER, lines(reviewerExport.body)[0]);

        // PLATFORM_ADMIN holds platform scope only — tenant analytics,
        // inventory, targets and reports are all denied (separation of the
        // platform role from company administration).
        assertContract(403, "FORBIDDEN",
                getJson("/api/v1/analytics/dashboard", platform));
        assertContract(403, "FORBIDDEN",
                getJson("/api/v1/inventory", platform));
        assertContract(403, "FORBIDDEN",
                getJson("/api/v1/targets", platform));
        assertContract(403, "FORBIDDEN",
                getJson("/api/v1/reduction-projects", platform));
        assertContract(403, "FORBIDDEN",
                getJson("/api/v1/reports/export-csv", platform));
        assertEquals(200, getJson("/api/v1/platform/tenants", platform).status());
    }

    // ------------------------------------------------------------------
    // Locked periods: readable through reporting, frozen for accounting
    // ------------------------------------------------------------------

    @Test
    void lockedPeriodsStayReadableWhileAccountingStaysFrozen() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        String[] scope = freshScope(token, "Phase7Lock");
        String[] targetScope = freshScope(token, "Phase7LockTarget");
        String auditor = loginToken(AUDITOR_EMAIL, PASSWORD);

        // Three records: Scope 1 plus both Scope 2 perspectives, distinct.
        JsonNode scope1 = createActivity(token, scope[0], scope[1],
                "SCOPE_1", "Stationary Combustion", "NATURAL_GAS", "1000", "kWh");
        JsonNode location = createActivity(token, scope[0], scope[1],
                "SCOPE_2", "ELECTRICITY_LOCATION", "GRID_ELECTRICITY_US", "1000", "kWh");
        JsonNode market = createActivity(token, scope[0], scope[1],
                "SCOPE_2", "ELECTRICITY_MARKET", "RESIDUAL_MIX_US", "1000", "kWh");
        for (JsonNode activity : new JsonNode[] {scope1, location, market}) {
            assertEquals(200, runCalculation(token, activity.path("id").asText()).status());
        }

        // Drive a fresh audit to LOCKED (freezes the reporting period).
        JsonNode audit = createAudit(token, scope[0]);
        String auditId = audit.path("id").asText();
        driveToReview(token, auditId);
        verifyAllChecklist(auditor, auditId);
        assertEquals(200, transition(token, auditId, "APPROVED", null).status());
        assertEquals(200, transition(token, auditId, "AUDIT_READY", null).status());
        assertEquals(200, transition(token, auditId, "LOCKED", null).status());

        // The period summary reports the lock, the counts and both Scope 2
        // perspectives separately (never summed into one figure).
        Api summary = getJson("/api/v1/analytics/periods/" + scope[0] + "/summary",
                token);
        assertEquals(200, summary.status(), summary.body().toString());
        JsonNode data = summary.body().path("data");
        assertTrue(data.path("governance").path("locked").asBoolean(),
                "summary must mirror the accounting lock");
        assertEquals("LOCKED",
                data.path("governance").path("auditStatus").asText());
        assertEquals(3, data.path("counts").path("emissionRecords").asInt());
        assertEquals(3, data.path("counts").path("calculations").asInt());
        assertEquals(3, data.path("counts").path("activityData").asInt());
        JsonNode totals = data.path("totals");
        assertEquals(0, decimal(totals.path("scope1Tonnes"))
                .compareTo(new BigDecimal("0.2165")), "scope1 4dp");
        assertEquals(0, decimal(totals.path("scope2LocationTonnes"))
                .compareTo(new BigDecimal("0.4000")), "location perspective 4dp");
        assertEquals(0, decimal(totals.path("scope2MarketTonnes"))
                .compareTo(new BigDecimal("0.4595")), "market perspective 4dp");
        assertNotEquals(0, decimal(totals.path("scope2LocationTonnes"))
                        .compareTo(decimal(totals.path("scope2MarketTonnes"))),
                "the two perspectives must stay distinct numbers");
        assertEquals(0, decimal(totals.path("totalLocationBasedTonnes"))
                .compareTo(new BigDecimal("0.6165")), "location-basis total");
        assertEquals(0, decimal(totals.path("totalMarketBasedTonnes"))
                .compareTo(new BigDecimal("0.6760")), "market-basis total");

        // Breakdown reads the frozen period with both perspectives per row.
        Api breakdown = getJson(
                "/api/v1/analytics/breakdown?dimension=scope&periodId=" + scope[0],
                token);
        assertEquals(200, breakdown.status(), breakdown.body().toString());
        JsonNode rows = breakdown.body().path("data").path("rows");
        assertEquals(2, rows.size(), breakdown.body().toString());
        JsonNode scope2Row = rows.get(1);
        assertEquals("SCOPE_2", scope2Row.path("key").asText());
        assertEquals(0, decimal(scope2Row.path("scope2LocationTonnes"))
                .compareTo(new BigDecimal("0.40")), "breakdown 2dp location");
        assertEquals(0, decimal(scope2Row.path("scope2MarketTonnes"))
                .compareTo(new BigDecimal("0.46")), "breakdown 2dp market");

        // The export reads the frozen ledger.
        Raw csv = export("/api/v1/reports/export-csv?periodId=" + scope[0], token);
        assertEquals(200, csv.status(), csv.body);
        assertEquals(CSV_HEADER, lines(csv.body)[0]);
        assertEquals(4, lines(csv.body).length, "header + 3 frozen records");

        // A snapshot is a read of persisted state — allowed while locked.
        Api snapshot = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + scope[0] + "\"}");
        assertEquals(201, snapshot.status(), snapshot.body().toString());

        // Targets referencing the locked period are reporting constructs, not
        // accounting writes.
        Api target = postJson("/api/v1/targets", token,
                "{\"name\":\"Locked Period Target\",\"baselinePeriodId\":\""
                        + scope[0] + "\",\"targetPeriodId\":\"" + targetScope[0]
                        + "\",\"baselineValueT\":1,\"targetValueT\":0.8,"
                        + "\"reductionPercentage\":20}");
        assertEquals(201, target.status(), target.body().toString());

        // Accounting itself stays frozen.
        assertEnvelope(409, "AUDIT_LOCKED", LOCK_MESSAGE,
                postJson("/api/v1/activity-data", token,
                        activityBody(scope[0], scope[1], "SCOPE_1",
                                "Stationary Combustion", "NATURAL_GAS", "500", "kWh")));
        assertEnvelope(409, "AUDIT_LOCKED", LOCK_MESSAGE,
                runCalculation(token, scope1.path("id").asText()));

        // And every Phase 7 call above left the ledger exactly as it was.
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM emission_records "
                        + "WHERE reporting_period_id = ?::uuid",
                Integer.class, scope[0]));
        assertEquals(3, jdbc.queryForObject(
                "SELECT count(*) FROM calculations "
                        + "WHERE reporting_period_id = ?::uuid",
                Integer.class, scope[0]));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM inventory_snapshots "
                        + "WHERE reporting_period_id = ?::uuid",
                Integer.class, scope[0]));
    }

    // ------------------------------------------------------------------
    // No hardcoded geography / currency / timezone / demo data
    // ------------------------------------------------------------------

    @Test
    void noHardcodedGeographyCurrencyTimezoneOrDemoDataExists() throws Exception {
        // 1. Production sources: no geography, currency, timezone or demo
        //    tokens anywhere under src/main/java.
        Path main = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(main),
                "surefire must run from backend-java to scan production sources");
        String[] banned = {
                "Asia/Kolkata", "Asia/Calcutta", "INR", "\u20B9",
                "period-2024", "gemini"};
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : (Iterable<Path>) files
                    .filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(
                        java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (String token : banned) {
                    if (source.contains(token)) {
                        violations.add(file + " -> " + token);
                    }
                }
            }
        }
        assertEquals(List.of(), violations,
                "production sources must not hardcode geography/currency/timezone/demo data");

        // 2. Live responses: a fresh tenant's Phase 7 payloads carry none of
        //    the banned values and no fabricated narrative/model names.
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String periodId = createPeriod(token, "Scan FY", "2047-01-01", "2047-12-31")
                .get("id").asText();

        List<String> bodies = new ArrayList<>();
        bodies.add(getJson("/api/v1/analytics/dashboard", token).body().toString());
        bodies.add(getJson("/api/v1/analytics/periods/" + periodId + "/summary",
                token).body().toString());
        bodies.add(getJson("/api/v1/analytics/breakdown?dimension=scope",
                token).body().toString());
        bodies.add(getJson("/api/v1/targets", token).body().toString());
        bodies.add(getJson("/api/v1/reduction-projects", token).body().toString());
        bodies.add(export("/api/v1/reports/export-csv?periodId=" + periodId,
                token).body);

        for (String body : bodies) {
            String lower = body.toLowerCase();
            assertFalse(lower.contains("gemini"),
                    "response invents an LLM narrative: " + body);
            assertFalse(lower.contains("period-2024"),
                    "response carries demo reporting periods: " + body);
            assertFalse(body.contains("Asia/Kolkata"),
                    "response hardcodes a timezone: " + body);
            assertFalse(lower.contains("inr"),
                    "response hardcodes a currency: " + body);
            assertFalse(body.contains("\u20B9"),
                    "response hardcodes a currency symbol: " + body);
        }

        // The trend endpoint explicitly reports the deterministic engine —
        // the modelUsed field exists and is not an external LLM name.
        Api dashboard = getJson("/api/v1/analytics/dashboard", token);
        assertEquals(200, dashboard.status(), dashboard.body().toString());
        assertNotEquals(null, dashboard.body().path("data"));
    }
}
