package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 task 7.7 — platform administration hardening (ADR-014/020).
 *
 * <p>Proves the three greenfield gaps closed against the documented state
 * machine: the detail endpoint (with the V8 audit columns exposed), from-state
 * transition validation (409 {@code INVALID_STATUS_TRANSITION} instead of the
 * previous silent overwrite), and safe 404s for malformed/unknown ids. The
 * frozen RBAC boundary (PLATFORM_ADMIN only) is re-asserted for every new
 * surface; fixtures live in freshly registered organizations.
 */
class PlatformAdminTest extends AccountingTestBase {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Registers an org and returns its id — left in PENDING_ACTIVATION. */
    private String registerPending() throws Exception {
        String email = "padm-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"Platform Tenant " + suffix() + "\","
                        + "\"country\":\"DE\",\"industry\":\"Logistics\","
                        + "\"fullName\":\"Platform Founder\",\"email\":\"" + email
                        + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(201, registration.status(), registration.body().toString());
        return registration.body().path("data").path("organization").path("id").asText();
    }

    private String platformUserId() {
        return jdbc.queryForObject(
                "SELECT id::text FROM users WHERE email = ?", String.class, PLATFORM_EMAIL);
    }

    /** Registers + platform-approves a fresh tenant; returns [token, orgId]. */
    private String[] isolatedTenant() throws Exception {
        String email = "padm2-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        Api registration = postJson("/api/v1/auth/register", null,
                "{\"organizationName\":\"RBAC Tenant " + suffix() + "\","
                        + "\"country\":\"DE\",\"industry\":\"Logistics\","
                        + "\"fullName\":\"RBAC Founder\",\"email\":\"" + email
                        + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(201, registration.status(), registration.body().toString());
        String orgId = registration.body().path("data").path("organization").path("id").asText();

        String platformToken = loginToken(PLATFORM_EMAIL, PASSWORD);
        Api approved = postJson("/api/v1/platform/tenants/" + orgId + "/approve",
                platformToken, null);
        assertEquals(200, approved.status(), approved.body().toString());

        return new String[] {loginToken(email, PASSWORD), orgId};
    }

    private void assertStatus(String platformToken, String orgId, String expected)
            throws Exception {
        Api detail = getJson("/api/v1/platform/tenants/" + orgId, platformToken);
        assertEquals(200, detail.status(), detail.body().toString());
        assertEquals(expected, detail.body().path("data").path("status").asText());
    }

    // ------------------------------------------------------------------
    // Detail endpoint + V8 audit columns
    // ------------------------------------------------------------------

    @Test
    void tenantDetailExposesV8AuditColumnsAndSafe404s() throws Exception {
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);
        String orgId = registerPending();

        // Detail before any transition: V8 columns are null → omitted.
        Api detail = getJson("/api/v1/platform/tenants/" + orgId, platform);
        assertEquals(200, detail.status(), detail.body().toString());
        JsonNode data = detail.body().path("data");
        assertEquals(orgId, data.path("id").asText());
        assertEquals("PENDING_ACTIVATION", data.path("status").asText());
        assertEquals("DE", data.path("country").asText());
        assertFalse(data.hasNonNull("statusChangedAt"),
                "no transition happened yet — column stays null");
        assertFalse(data.hasNonNull("statusChangedBy"));
        assertFalse(data.hasNonNull("statusNote"));

        // Approve → V8 columns record actor + timestamp.
        Api approved =
                postJson("/api/v1/platform/tenants/" + orgId + "/approve", platform, null);
        assertEquals(200, approved.status(), approved.body().toString());
        String platformAdminId = platformUserId();

        Api after = getJson("/api/v1/platform/tenants/" + orgId, platform);
        assertEquals(200, after.status(), after.body().toString());
        JsonNode audited = after.body().path("data");
        assertEquals("ACTIVE", audited.path("status").asText());
        assertTrue(audited.hasNonNull("statusChangedAt"),
                "V8 statusChangedAt must be exposed");
        String changedAt = audited.path("statusChangedAt").asText();
        assertTrue(changedAt.matches(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"),
                "ISO-8601 UTC instant expected: " + changedAt);
        assertEquals(platformAdminId, audited.path("statusChangedBy").asText(),
                "the platform administrator must be recorded as actor");
        assertFalse(audited.hasNonNull("statusNote"),
                "approve carried no note — nothing invented");

        // Reject with a note → the note is persisted and exposed.
        String orgTwo = registerPending();
        Api rejected = postJson("/api/v1/platform/tenants/" + orgTwo + "/reject",
                platform, "{\"note\":\"Incomplete paperwork.\"}");
        assertEquals(200, rejected.status(), rejected.body().toString());
        Api detailTwo = getJson("/api/v1/platform/tenants/" + orgTwo, platform);
        JsonNode rejectedData = detailTwo.body().path("data");
        assertEquals("REJECTED", rejectedData.path("status").asText());
        assertEquals("Incomplete paperwork.", rejectedData.path("statusNote").asText());
        assertEquals(platformAdminId, rejectedData.path("statusChangedBy").asText());
        assertTrue(rejectedData.hasNonNull("statusChangedAt"));

        // The list endpoint exposes the same audit columns (additive fields).
        Api list = getJson("/api/v1/platform/tenants", platform);
        assertEquals(200, list.status(), list.body().toString());
        JsonNode row = null;
        for (JsonNode candidate : list.body().path("data")) {
            if (orgTwo.equals(candidate.path("id").asText())) {
                row = candidate;
            }
        }
        assertNotNull(row, "the rejected tenant must appear in the list");
        assertEquals(platformAdminId, row.path("statusChangedBy").asText());
        assertEquals("Incomplete paperwork.", row.path("statusNote").asText());

        // Malformed and unknown ids collapse into the same 404 (no leak,
        // no SQL cast error).
        Api unknown =
                getJson("/api/v1/platform/tenants/" + UUID.randomUUID(), platform);
        assertEquals(404, unknown.status(), unknown.body().toString());
        assertEquals("ORGANIZATION_NOT_FOUND",
                unknown.body().path("error").path("code").asText());
        Api malformed = getJson("/api/v1/platform/tenants/not-a-uuid", platform);
        assertEquals(404, malformed.status(), malformed.body().toString());
        assertEquals(unknown.body().toString(), malformed.body().toString());
    }

    // ------------------------------------------------------------------
    // From-state transition validation
    // ------------------------------------------------------------------

    @Test
    void transitionsEnforceTheDocumentedFromStateMachine() throws Exception {
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);
        String orgId = registerPending();

        // PENDING may only be approved or rejected — suspend is a 409.
        Api suspendPending =
                postJson("/api/v1/platform/tenants/" + orgId + "/suspend", platform, null);
        assertEquals(409, suspendPending.status(), suspendPending.body().toString());
        assertEquals("INVALID_STATUS_TRANSITION",
                suspendPending.body().path("error").path("code").asText());
        assertTrue(suspendPending.body().path("error").path("message").asText()
                        .contains("from PENDING_ACTIVATION to SUSPENDED"),
                suspendPending.body().toString());
        assertStatus(platform, orgId, "PENDING_ACTIVATION");

        // PENDING --approve--> ACTIVE.
        Api approved =
                postJson("/api/v1/platform/tenants/" + orgId + "/approve", platform, null);
        assertEquals(200, approved.status(), approved.body().toString());
        assertStatus(platform, orgId, "ACTIVE");

        // ACTIVE may only be suspended — repeat approve and reject are 409.
        Api reapprove =
                postJson("/api/v1/platform/tenants/" + orgId + "/approve", platform, null);
        assertEquals(409, reapprove.status(), reapprove.body().toString());
        assertEquals("INVALID_STATUS_TRANSITION",
                reapprove.body().path("error").path("code").asText());
        Api rejectActive = postJson("/api/v1/platform/tenants/" + orgId + "/reject",
                platform, "{\"note\":\"no\"}");
        assertEquals(409, rejectActive.status(), rejectActive.body().toString());
        assertEquals("INVALID_STATUS_TRANSITION",
                rejectActive.body().path("error").path("code").asText());
        assertStatus(platform, orgId, "ACTIVE");

        // ACTIVE --suspend--> SUSPENDED; suspend again → 409; reject → 409.
        Api suspended =
                postJson("/api/v1/platform/tenants/" + orgId + "/suspend", platform,
                        "{\"note\":\"audit backlog\"}");
        assertEquals(200, suspended.status(), suspended.body().toString());
        assertStatus(platform, orgId, "SUSPENDED");
        Api suspendAgain =
                postJson("/api/v1/platform/tenants/" + orgId + "/suspend", platform, null);
        assertEquals(409, suspendAgain.status(), suspendAgain.body().toString());
        assertEquals("INVALID_STATUS_TRANSITION",
                suspendAgain.body().path("error").path("code").asText());
        Api rejectSuspended =
                postJson("/api/v1/platform/tenants/" + orgId + "/reject", platform, null);
        assertEquals(409, rejectSuspended.status(), rejectSuspended.body().toString());
        assertStatus(platform, orgId, "SUSPENDED");

        // SUSPENDED --approve--> ACTIVE (the documented reactivation path).
        Api reactivated =
                postJson("/api/v1/platform/tenants/" + orgId + "/approve", platform, null);
        assertEquals(200, reactivated.status(), reactivated.body().toString());
        assertStatus(platform, orgId, "ACTIVE");

        // REJECTED --approve--> ACTIVE; repeat reject / suspend → 409.
        String rejectedId = registerPending();
        Api rejected = postJson("/api/v1/platform/tenants/" + rejectedId + "/reject",
                platform, "{\"note\":\"nope\"}");
        assertEquals(200, rejected.status(), rejected.body().toString());
        assertStatus(platform, rejectedId, "REJECTED");
        Api rejectAgain =
                postJson("/api/v1/platform/tenants/" + rejectedId + "/reject", platform, null);
        assertEquals(409, rejectAgain.status(), rejectAgain.body().toString());
        assertEquals("INVALID_STATUS_TRANSITION",
                rejectAgain.body().path("error").path("code").asText());
        Api suspendRejected =
                postJson("/api/v1/platform/tenants/" + rejectedId + "/suspend", platform, null);
        assertEquals(409, suspendRejected.status(), suspendRejected.body().toString());
        assertStatus(platform, rejectedId, "REJECTED");
        Api approveRejected =
                postJson("/api/v1/platform/tenants/" + rejectedId + "/approve", platform, null);
        assertEquals(200, approveRejected.status(), approveRejected.body().toString());
        assertStatus(platform, rejectedId, "ACTIVE");

        // Malformed organization id → safe 404 on transitions (never a 500).
        Api malformed =
                postJson("/api/v1/platform/tenants/not-a-uuid/approve", platform, null);
        assertEquals(404, malformed.status(), malformed.body().toString());
        assertEquals("ORGANIZATION_NOT_FOUND",
                malformed.body().path("error").path("code").asText());
        Api unknown = postJson("/api/v1/platform/tenants/" + UUID.randomUUID()
                + "/approve", platform, null);
        assertEquals(404, unknown.status(), unknown.body().toString());
        assertEquals(malformed.body().toString(), unknown.body().toString());
    }

    // ------------------------------------------------------------------
    // Frozen RBAC boundary
    // ------------------------------------------------------------------

    @Test
    void platformEndpointsStayRestrictedToPlatformRoles() throws Exception {
        String[] tenant = isolatedTenant();
        String companyAdmin = tenant[0];
        String orgId = tenant[1];

        // The new detail surface obeys the same 403 boundary.
        Api detailForbidden =
                getJson("/api/v1/platform/tenants/" + orgId, companyAdmin);
        assertEquals(403, detailForbidden.status(), detailForbidden.body().toString());

        // Every transition stays forbidden for tenant roles (approve is
        // covered by PlatformTenantTest; suspend/reject are re-asserted here).
        Api approveForbidden =
                postJson("/api/v1/platform/tenants/" + orgId + "/approve",
                        companyAdmin, null);
        assertEquals(403, approveForbidden.status(), approveForbidden.body().toString());
        Api suspendForbidden =
                postJson("/api/v1/platform/tenants/" + orgId + "/suspend",
                        companyAdmin, null);
        assertEquals(403, suspendForbidden.status(), suspendForbidden.body().toString());
        Api rejectForbidden =
                postJson("/api/v1/platform/tenants/" + orgId + "/reject",
                        companyAdmin, "{\"note\":\"x\"}");
        assertEquals(403, rejectForbidden.status(), rejectForbidden.body().toString());

        // Anonymous callers get 401 before any permission evaluation.
        assertEquals(401,
                getJson("/api/v1/platform/tenants/" + orgId, null).status());
        assertEquals(401, postJson("/api/v1/platform/tenants/" + orgId + "/approve",
                null, null).status());
    }
}
