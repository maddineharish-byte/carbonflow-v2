package com.carbonflow.recovery.hardening;

import com.carbonflow.repository.SeedIds;
import com.carbonflow.testsupport.PostgresBackedIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.9 — adversarial tenant-isolation probing.
 *
 * <p>Deliberately hostile: two <em>real, pre-approved</em> seeded tenants, then a
 * systematic sweep of identifier-substitution (IDOR) attempts from tenant A
 * against tenant B's identifiers across the resource families the approved
 * tenant scope covers.
 *
 * <p>The bar is deliberately strict, and stricter than "the request failed":
 * <ul>
 *   <li>a cross-tenant read must be <b>403 or 404</b> — never 200;</li>
 *   <li>the response body must not contain tenant B's distinctive content;</li>
 *   <li>after a refused mutation, tenant B's row must <b>still exist</b>, so a
 *       rejected request cannot have half-applied.</li>
 * </ul>
 *
 * <p>Isolation is also asserted in the positive direction: tenant B must still
 * read its own data. Over-blocking is a real defect too, and a suite that only
 * attacked would not notice it.
 *
 * <p>Runs against the hermetic embedded PostgreSQL with Flyway V1–V8 applied, so
 * the real schema, real composite foreign keys and the real RBAC matrix are in
 * play. No production data, no external service.
 */
class TenantIsolationAdversarialTest extends PostgresBackedIntegrationTest {

    /** Two independent, already-approved seeded tenants. */
    private static final String TENANT_A_EMAIL = "admin@acmeglobal.com";
    private static final String TENANT_B_EMAIL = "admin@apexcorp.com";
    private static final String PASSWORD = SeedIds.DEMO_PASSWORD;

    /** Tenant B's resources, each created under B's token only. */
    private record TenantBResources(String facilityId, String facilityName,
                                    String activityId, String evidenceId) {
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Creates real, identifiable resources inside tenant B.
     *
     * <p>Names are unique per run so a leaked body is unambiguous rather than a
     * coincidental match against shared seed data.
     */
    private TenantBResources resourcesInTenantB(String tokenB) throws Exception {
        String sfx = suffix();

        String facilityName = "PHASE109-B-Facility-" + sfx;
        Api facility = postJson("/api/v1/facilities", tokenB,
                "{\"name\":\"" + facilityName + "\",\"facilityCode\":\"P109-" + sfx + "\","
                        + "\"country\":\"US\",\"gridRegion\":\"US-TEST\"}");
        assertThat(facility.status())
                .as("tenant B facility fixture -> %s", facility.body())
                .isEqualTo(201);
        String facilityId = facility.body().path("data").path("id").asText();
        assertThat(facilityId).isNotBlank();

        // Activity data is scoped to a reporting period, so the fixture needs one
        // in tenant B as well; the activity dates must fall inside the period.
        Api period = postJson("/api/v1/reporting-periods", tokenB,
                "{\"name\":\"PHASE109-B-Period-" + sfx + "\",\"startDate\":\"2043-01-01\","
                        + "\"endDate\":\"2043-12-31\"}");
        assertThat(period.status())
                .as("tenant B reporting-period fixture -> %s", period.body())
                .isEqualTo(201);
        String periodId = period.body().path("data").path("id").asText();

        Api activity = postJson("/api/v1/activity-data", tokenB,
                "{\"reportingPeriodId\":\"" + periodId + "\",\"facilityId\":\"" + facilityId
                        + "\",\"scope\":\"SCOPE_1\",\"category\":\"Stationary Combustion\","
                        + "\"activityType\":\"NATURAL_GAS\",\"quantity\":10,\"unit\":\"M3\","
                        + "\"startDate\":\"2043-01-01\",\"endDate\":\"2043-12-31\","
                        + "\"source\":\"PHASE109-B-Activity-" + sfx + "\"}");
        assertThat(activity.status())
                .as("tenant B activity fixture -> %s", activity.body())
                .isEqualTo(201);
        String activityId = activity.body().path("data").path("id").asText();
        assertThat(activityId).isNotBlank();

        Api evidence = postMultipart("/api/v1/evidence/upload", tokenB, Map.of(),
                "phase109-b-" + sfx + ".txt", "text/plain",
                "PHASE109-TENANT-B-CONFIDENTIAL".getBytes());
        assertThat(evidence.status())
                .as("tenant B evidence fixture -> %s", evidence.body())
                .isEqualTo(201);
        String evidenceId = evidence.body().path("data").path("id").asText();
        assertThat(evidenceId).isNotBlank();

        return new TenantBResources(facilityId, facilityName, activityId, evidenceId);
    }

    // ------------------------------------------------------------------
    // Cross-tenant reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tenant A cannot fetch tenant B's facility, evidence or activity by id")
    void crossTenantReadsAreRefused() throws Exception {
        String tokenA = loginToken(TENANT_A_EMAIL, PASSWORD);
        String tokenB = loginToken(TENANT_B_EMAIL, PASSWORD);
        TenantBResources b = resourcesInTenantB(tokenB);

        // Activity data is deliberately absent here: the API exposes no
        // per-id GET for /activity-data/{id}, so a request to it is answered 405
        // (method not allowed) rather than 403/404. That is a correct refusal,
        // but it proves nothing about isolation. Activity isolation is therefore
        // asserted at the collection level in the next test, which is the only
        // read surface that actually exists.
        record Vector(String label, String uri) {
        }
        var vectors = java.util.List.of(
                new Vector("facility", "/api/v1/facilities/" + b.facilityId()),
                new Vector("evidence", "/api/v1/evidence/" + b.evidenceId()),
                new Vector("evidence-bytes", "/api/v1/evidence/" + b.evidenceId() + "/download"));

        for (Vector vector : vectors) {
            MvcResultHolder holder = rawStatus(vector.uri(), tokenA);
            int status = holder.status();
            String body = holder.body();

            assertThat(status)
                    .as("cross-tenant GET of a %s must be 403/404, never 200", vector.label())
                    .isIn(403, 404);

            assertThat(body)
                    .as("cross-tenant %s response must not leak tenant B content", vector.label())
                    .doesNotContain("PHASE109-TENANT-B-CONFIDENTIAL")
                    .doesNotContain(b.facilityName());
        }
    }

    @Test
    @DisplayName("tenant B's identifiers are absent from tenant A's collections")
    void crossTenantCollectionsExcludeForeignRows() throws Exception {
        String tokenA = loginToken(TENANT_A_EMAIL, PASSWORD);
        String tokenB = loginToken(TENANT_B_EMAIL, PASSWORD);
        TenantBResources b = resourcesInTenantB(tokenB);

        // Facilities: list endpoint, tenant-scoped.
        Api facilities = getJson("/api/v1/facilities", tokenA);
        assertThat(facilities.status()).isEqualTo(200);
        assertThat(facilities.body().toString())
                .as("tenant A's facility list must not contain tenant B's facility")
                .doesNotContain(b.facilityId())
                .doesNotContain(b.facilityName());

        // Evidence: list endpoint, tenant-scoped.
        Api evidence = getJson("/api/v1/evidence", tokenA);
        assertThat(evidence.status()).isEqualTo(200);
        assertThat(evidence.body().toString())
                .as("tenant A's evidence list must not contain tenant B's evidence")
                .doesNotContain(b.evidenceId());

        // Activity data: no per-id GET exists, so the collection is the vector.
        Api activity = getJson("/api/v1/activity-data", tokenA);
        assertThat(activity.status()).isEqualTo(200);
        assertThat(activity.body().toString())
                .as("tenant A's activity list must not contain tenant B's activity")
                .doesNotContain(b.activityId());
    }

    @Test
    @DisplayName("isolation is not over-blocking: each tenant still reads its own data")
    void ownerCanStillReadTheirOwn() throws Exception {
        String tokenB = loginToken(TENANT_B_EMAIL, PASSWORD);
        TenantBResources b = resourcesInTenantB(tokenB);

        assertThat(getJson("/api/v1/facilities/" + b.facilityId(), tokenB).status())
                .as("tenant B must still read its own facility")
                .isEqualTo(200);

        MvcResultHolder download = rawStatus(
                "/api/v1/evidence/" + b.evidenceId() + "/download", tokenB);
        assertThat(download.status())
                .as("tenant B must still download its own evidence")
                .isEqualTo(200);
        assertThat(download.body())
                .as("the owner must receive the real bytes")
                .contains("PHASE109-TENANT-B-CONFIDENTIAL");
    }

    // ------------------------------------------------------------------
    // Cross-tenant writes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tenant A cannot delete or update tenant B's resources")
    void crossTenantWritesAreRefusedAndLeaveDataIntact() throws Exception {
        String tokenA = loginToken(TENANT_A_EMAIL, PASSWORD);
        String tokenB = loginToken(TENANT_B_EMAIL, PASSWORD);
        TenantBResources b = resourcesInTenantB(tokenB);

        Api deleteAttempt = deleteJson("/api/v1/facilities/" + b.facilityId(), tokenA);
        assertThat(deleteAttempt.status())
                .as("cross-tenant DELETE must be refused")
                .isIn(403, 404);

        Api updateAttempt = putJson("/api/v1/facilities/" + b.facilityId(), tokenA,
                "{\"name\":\"HIJACKED-BY-TENANT-A\"}");
        assertThat(updateAttempt.status())
                .as("cross-tenant PUT must be refused")
                .isIn(403, 404);

        // The decisive check: a refused mutation must not have half-applied.
        Api stillThere = getJson("/api/v1/facilities/" + b.facilityId(), tokenB);
        assertThat(stillThere.status())
                .as("tenant B's facility must survive the refused cross-tenant writes")
                .isEqualTo(200);
        assertThat(stillThere.body().toString())
                .as("tenant B's facility must not have been renamed by tenant A")
                .doesNotContain("HIJACKED-BY-TENANT-A");
    }

    @Test
    @DisplayName("tenant A cannot overwrite tenant B's activity record")
    void crossTenantActivityUpdateIsRefused() throws Exception {
        String tokenA = loginToken(TENANT_A_EMAIL, PASSWORD);
        String tokenB = loginToken(TENANT_B_EMAIL, PASSWORD);
        TenantBResources b = resourcesInTenantB(tokenB);

        Api update = putJson("/api/v1/activity-data/" + b.activityId(), tokenA,
                "{\"quantity\":999999}");
        assertThat(update.status())
                .as("cross-tenant activity update must be refused")
                .isIn(403, 404);
    }

    // ------------------------------------------------------------------
    // Authentication and error-surface probes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unauthenticated request is refused")
    void unauthenticatedIsRefused() throws Exception {
        String tokenB = loginToken(TENANT_B_EMAIL, PASSWORD);
        TenantBResources b = resourcesInTenantB(tokenB);

        assertThat(getJson("/api/v1/facilities/" + b.facilityId(), null).status())
                .as("no token must not reach a tenant-scoped resource")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a query-string token does not authenticate")
    void queryStringTokenIsRefused() throws Exception {
        String tokenA = loginToken(TENANT_A_EMAIL, PASSWORD);
        String tokenB = loginToken(TENANT_B_EMAIL, PASSWORD);
        TenantBResources b = resourcesInTenantB(tokenB);

        MvcResultHolder holder = rawStatus(
                "/api/v1/facilities/" + b.facilityId() + "?token=" + tokenA, null);

        assertThat(holder.status())
                .as("a credential in a query string leaks into logs, proxies and browser "
                        + "history; it must not authenticate")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a malformed identifier yields a typed envelope, never internals")
    void malformedIdentifierDoesNotLeakInternals() throws Exception {
        String tokenA = loginToken(TENANT_A_EMAIL, PASSWORD);

        Api response = getJson("/api/v1/facilities/not-a-uuid", tokenA);

        assertThat(response.status()).isIn(400, 404);
        String body = response.body().toString();
        assertThat(body)
                .as("no stack trace, SQL text or driver detail may reach the client")
                .doesNotContain("org.postgresql")
                .doesNotContain("SQLException")
                .doesNotContain("at com.carbonflow")
                .doesNotContain("Exception in thread")
                .doesNotContain("SELECT");
        assertThat(response.errorCode())
                .as("errors must use the typed envelope")
                .isNotBlank();
    }

    /** Minimal status + body holder so raw (non-JSON) responses can be inspected. */
    private record MvcResultHolder(int status, String body) {
    }

    private MvcResultHolder rawStatus(String uri, String bearerToken) throws Exception {
        var result = super.rawGet(uri, bearerToken);
        String body = result.getResponse().getContentAsString();
        return new MvcResultHolder(result.getResponse().getStatus(),
                body == null ? "" : body);
    }
}