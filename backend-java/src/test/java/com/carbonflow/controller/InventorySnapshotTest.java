package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 — inventory snapshots (API.md §2.8, ADR-019).
 *
 * <p>Every value assertion compares against an independent SQL aggregation of
 * {@code emission_records}; every hash assertion recomputes SHA-256 in the
 * test. Because fixtures live in freshly registered organizations, a
 * cross-tenant leak anywhere (list, create, lock) fails the assertions.
 *
 * <p>Also proves the ADR-019 deviations: reproducible hash (no
 * {@code Date.now()}), one-ACTIVE-per-period supersede, LOCKED freeze, and
 * the dual-basis columns never summed together.
 */
class InventorySnapshotTest extends AccountingTestBase {

    // ------------------------------------------------------------------
    // Fixtures (local copies — AnalyticsTest's helpers are private)
    // ------------------------------------------------------------------

    private String[] isolatedTenant() throws Exception {
        String email = "inv-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Inventory Tenant " + suffix() + "\",\"country\":\"DE\","
                        + "\"industry\":\"Logistics\",\"fullName\":\"Inventory Founder\","
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

    // ------------------------------------------------------------------
    // Independent SQL oracle + hash helpers
    // ------------------------------------------------------------------

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

    /** Mirrors the service's stored-value format (NUMERIC(18,6), plain). */
    private static String fmt6(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sha256(String payload) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static void assertScaled(int scale, BigDecimal expected, JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertNotNull(value, "missing field " + field);
        assertEquals(0, expected.setScale(scale, RoundingMode.HALF_UP)
                        .compareTo(value.decimalValue().setScale(scale, RoundingMode.HALF_UP)),
                field + ": expected " + expected + ", wire was " + value);
    }

    private static JsonNode byId(JsonNode array, String id) {
        for (JsonNode item : array) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    void snapshotCreationSumsTheLedgerWithAReproducibleHash() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];

        JsonNode period = createPeriodRange(token, "FY2043", "2043-01-01", "2043-12-31");
        String p43 = period.get("id").asText();
        JsonNode facility = createFacility(token, "Snapshot Plant " + suffix(),
                "P7I-" + suffix());
        String facilityId = facility.get("id").asText();

        seedAndRun(token, p43, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");
        seedAndRun(token, p43, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");
        seedAndRun(token, p43, facilityId, "SCOPE_2", "ELECTRICITY_MARKET",
                "RESIDUAL_MIX_US", "1000", "kWh");

        Api created = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + p43 + "\"}");
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("Immutable inventory snapshot created.",
                created.body().path("message").asText());
        JsonNode data = created.body().path("data");

        // Values == independent SQL aggregation, both bases separate at 6dp.
        BigDecimal[] expected =
                sums("organization_id = ? AND reporting_period_id = ?", orgId, p43);
        assertScaled(6, expected[0], data, "scope1Co2eT");
        assertScaled(6, expected[1], data, "scope2LocationCo2eT");
        assertScaled(6, expected[2], data, "scope2MarketCo2eT");
        assertScaled(6, BigDecimal.ZERO, data, "biogenicCo2eT");
        assertTrue(expected[1].subtract(expected[2]).signum() != 0,
                "fixture must produce different location/market perspectives");

        assertEquals("ACTIVE", data.path("status").asText());
        assertEquals(orgId, data.path("organizationId").asText());
        assertEquals(p43, data.path("reportingPeriodId").asText());
        assertFalse(data.hasNonNull("auditId"),
                "no audit linkage is invented (schema column stays null)");
        assertTrue(data.hasNonNull("createdAt"));

        // Reproducible hash == independently computed sha256(org|period|s1|loc|mkt).
        String payload = orgId + "|" + p43 + "|" + fmt6(expected[0])
                + "|" + fmt6(expected[1]) + "|" + fmt6(expected[2]);
        String hash = data.path("snapshotHash").asText();
        assertEquals(64, hash.length(), "sha256 hex is 64 chars: " + hash);
        assertEquals(sha256(payload), hash);
    }

    @Test
    void reSnapshottingSupersedesThePreviousActiveRowWithTheSameHash() throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];
        String orgId = tenant[1];

        JsonNode period = createPeriodRange(token, "FY2043", "2043-01-01", "2043-12-31");
        String p43 = period.get("id").asText();
        JsonNode facility = createFacility(token, "Supersede Plant " + suffix(),
                "P7R-" + suffix());
        String facilityId = facility.get("id").asText();
        seedAndRun(token, p43, facilityId, "SCOPE_2", "ELECTRICITY_LOCATION",
                "GRID_ELECTRICITY_US", "1000", "kWh");

        Api first = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + p43 + "\"}");
        assertEquals(201, first.status(), first.body().toString());
        String firstId = first.body().path("data").path("id").asText();
        String firstHash = first.body().path("data").path("snapshotHash").asText();

        Api second = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + p43 + "\"}");
        assertEquals(201, second.status(), second.body().toString());
        JsonNode secondData = second.body().path("data");
        String secondId = secondData.path("id").asText();

        // Unchanged ledger ⇒ identical hash (the Node timestamped hash could
        // never reproduce it) and identical stored values.
        assertEquals(firstHash, secondData.path("snapshotHash").asText(),
                "hash must be reproducible over the same ledger state");
        assertNotEquals(firstId, secondId, "each snapshot is a new row");
        BigDecimal[] expected =
                sums("organization_id = ? AND reporting_period_id = ?", orgId, p43);
        assertScaled(6, expected[1], secondData, "scope2LocationCo2eT");

        // One ACTIVE per period: the new row is ACTIVE, the old one REVERTED.
        Api list = getJson("/api/v1/inventory", token);
        assertEquals(200, list.status(), list.body().toString());
        JsonNode rows = list.body().path("data");
        assertEquals(2, rows.size(), rows.toString());
        assertEquals(secondId, rows.get(0).path("id").asText(), "newest first");
        assertEquals("ACTIVE", rows.get(0).path("status").asText());
        assertEquals(firstId, rows.get(1).path("id").asText());
        assertEquals("REVERTED", rows.get(1).path("status").asText());

        // And the superseded row keeps its original immutable hash.
        assertEquals(firstHash, rows.get(1).path("snapshotHash").asText());
    }

    // ------------------------------------------------------------------
    // Lock
    // ------------------------------------------------------------------

    @Test
    void lockTransitionsAreValidatedAndFreezeThePeriodAgainstNewSnapshots()
            throws Exception {
        String[] tenant = isolatedTenant();
        String token = tenant[0];

        JsonNode periodA = createPeriodRange(token, "FY2043", "2043-01-01", "2043-12-31");
        JsonNode periodB = createPeriodRange(token, "FY2044", "2044-01-01", "2044-12-31");
        String p43 = periodA.get("id").asText();
        String p44 = periodB.get("id").asText();
        JsonNode facility = createFacility(token, "Lock Plant " + suffix(),
                "P7K-" + suffix());
        String facilityId = facility.get("id").asText();
        seedAndRun(token, p43, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "100", "kWh");

        Api created = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + p43 + "\"}");
        assertEquals(201, created.status(), created.body().toString());
        JsonNode snapshot = created.body().path("data");
        String snapshotId = snapshot.path("id").asText();
        String hashBefore = snapshot.path("snapshotHash").asText();

        Api locked = postJson("/api/v1/inventory/" + snapshotId + "/lock", token, null);
        assertEquals(200, locked.status(), locked.body().toString());
        assertEquals("Inventory snapshot locked.", locked.body().path("message").asText());
        JsonNode lockedData = locked.body().path("data");
        assertEquals("LOCKED", lockedData.path("status").asText());
        // Status is the only mutable field — amounts and hash are immutable.
        assertEquals(hashBefore, lockedData.path("snapshotHash").asText());
        assertScaled(6, snapshot.path("scope1Co2eT").decimalValue(),
                lockedData, "scope1Co2eT");

        // Re-lock → 409 with its own code.
        Api relock = postJson("/api/v1/inventory/" + snapshotId + "/lock", token, null);
        assertEquals(409, relock.status(), relock.body().toString());
        assertEquals("INVENTORY_SNAPSHOT_LOCKED",
                relock.body().path("error").path("code").asText());

        // A LOCKED snapshot freezes its period against re-snapshots.
        Api again = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + p43 + "\"}");
        assertEquals(409, again.status(), again.body().toString());
        assertEquals("INVENTORY_SNAPSHOT_LOCKED",
                again.body().path("error").path("code").asText());

        // A REVERTED row cannot be locked either.
        seedAndRun(token, p44, facilityId, "SCOPE_1", "Stationary Combustion",
                "NATURAL_GAS", "200", "kWh");
        Api snapB1 = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + p44 + "\"}");
        assertEquals(201, snapB1.status(), snapB1.body().toString());
        String snapB1Id = snapB1.body().path("data").path("id").asText();
        Api snapB2 = postJson("/api/v1/inventory/snapshot", token,
                "{\"reportingPeriodId\":\"" + p44 + "\"}");
        assertEquals(201, snapB2.status(), snapB2.body().toString());
        Api lockReverted = postJson("/api/v1/inventory/" + snapB1Id + "/lock",
                token, null);
        assertEquals(409, lockReverted.status(), lockReverted.body().toString());
        assertEquals("INVENTORY_SNAPSHOT_REVERTED",
                lockReverted.body().path("error").path("code").asText());
    }

    // ------------------------------------------------------------------
    // Validation + tenant safety + authentication
    // ------------------------------------------------------------------

    @Test
    void inventoryEndpointsFailSafelyAcrossTenantBoundaries() throws Exception {
        String[] tenantA = isolatedTenant();
        String[] tenantB = isolatedTenant();

        JsonNode periodA = createPeriodRange(tenantA[0], "FY2043",
                "2043-01-01", "2043-12-31");
        String p43 = periodA.get("id").asText();
        JsonNode facilityA = createFacility(tenantA[0], "Tenant A Plant " + suffix(),
                "P7T-" + suffix());
        seedAndRun(tenantA[0], p43, facilityA.get("id").asText(),
                "SCOPE_2", "ELECTRICITY_LOCATION", "GRID_ELECTRICITY_US", "1000", "kWh");
        Api created = postJson("/api/v1/inventory/snapshot", tenantA[0],
                "{\"reportingPeriodId\":\"" + p43 + "\"}");
        assertEquals(201, created.status(), created.body().toString());
        String snapshotAId = created.body().path("data").path("id").asText();

        // Tenant B sees only its own (empty) list.
        Api listB = getJson("/api/v1/inventory", tenantB[0]);
        assertEquals(200, listB.status(), listB.body().toString());
        assertEquals(0, listB.body().path("data").size(),
                "cross-tenant snapshots must not leak");

        // Tenant B cannot lock tenant A's snapshot — same 404 as unknown.
        Api foreignLock = postJson("/api/v1/inventory/" + snapshotAId + "/lock",
                tenantB[0], null);
        assertEquals(404, foreignLock.status(), foreignLock.body().toString());
        assertEquals("INVENTORY_SNAPSHOT_NOT_FOUND",
                foreignLock.body().path("error").path("code").asText());
        Api malformedLock = postJson("/api/v1/inventory/not-a-uuid/lock",
                tenantB[0], null);
        assertEquals(404, malformedLock.status(), malformedLock.body().toString());
        assertEquals(foreignLock.body().toString(), malformedLock.body().toString(),
                "malformed and foreign ids must be indistinguishable");

        // Tenant B cannot snapshot tenant A's reporting period.
        Api foreignPeriod = postJson("/api/v1/inventory/snapshot", tenantB[0],
                "{\"reportingPeriodId\":\"" + p43 + "\"}");
        assertEquals(404, foreignPeriod.status(), foreignPeriod.body().toString());
        assertEquals("REPORTING_PERIOD_NOT_FOUND",
                foreignPeriod.body().path("error").path("code").asText());
        Api malformedPeriod = postJson("/api/v1/inventory/snapshot", tenantB[0],
                "{\"reportingPeriodId\":\"not-a-uuid\"}");
        assertEquals(404, malformedPeriod.status(), malformedPeriod.body().toString());
        assertEquals(foreignPeriod.body().toString(), malformedPeriod.body().toString());

        // Missing body field → 400; absent body → 400; never 500.
        Api missing = postJson("/api/v1/inventory/snapshot", tenantB[0], "{}");
        assertEquals(400, missing.status(), missing.body().toString());
        assertEquals("VALIDATION_ERROR",
                missing.body().path("error").path("code").asText());
        Api empty = postJson("/api/v1/inventory/snapshot", tenantB[0], null);
        assertEquals(400, empty.status(), empty.body().toString());

        // Tenant A still has exactly its own snapshot.
        Api listA = getJson("/api/v1/inventory", tenantA[0]);
        assertEquals(200, listA.status(), listA.body().toString());
        assertEquals(1, listA.body().path("data").size());
        assertEquals(snapshotAId, listA.body().path("data").get(0).path("id").asText());
    }

    @Test
    void inventoryEndpointsRequireAuthentication() throws Exception {
        Api list = getJson("/api/v1/inventory", null);
        assertEquals(401, list.status());
        Api create = postJson("/api/v1/inventory/snapshot", null,
                "{\"reportingPeriodId\":\"" + UUID.randomUUID() + "\"}");
        assertEquals(401, create.status());
        Api lock = postJson("/api/v1/inventory/" + UUID.randomUUID() + "/lock",
                null, null);
        assertEquals(401, lock.status());
    }
}
