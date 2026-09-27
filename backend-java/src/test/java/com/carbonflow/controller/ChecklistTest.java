package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 module 3: audit checklist items — create/read/update/verify, the
 * explicit-boolean rule (Node silently coerced a missing value to false),
 * the completion-gate input, tenant isolation and RBAC
 * ({@code audits.review} for writes, {@code audits.read} for reads).
 */
class ChecklistTest extends AuditTestBase {

    private static final String ACME_ADMIN = "admin@acmeglobal.com";
    private static final String ACME_MANAGER = "manager@acmeglobal.com";
    private static final String ACME_AUDITOR = "auditor@ey-assurance.com";
    private static final String APEX_ADMIN = "admin@apexcorp.com";

    @Test
    void reviewerCreatesCustomItemsAndDuplicatesAreRejected() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Chk");
        String auditId = audit.get("id").asText();

        String code = "CHK-EXT-" + suffix().toUpperCase();
        Api created = postJson("/api/v1/audits/" + auditId + "/checklist", auditor,
                "{\"code\":\"" + code + "\",\"title\":\"Supplier attestation reviewed\","
                        + "\"isMandatory\":false}");
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("Checklist item created.", created.body().path("message").asText());
        assertEquals(code, created.body().path("data").path("code").asText());
        assertFalse(created.body().path("data").path("isMandatory").asBoolean(),
                "isMandatory honors the request");
        assertFalse(created.body().path("data").path("isSatisfied").asBoolean());

        // Extra items are additive: the 8 canonical codes stay intact.
        Api detail = getJson("/api/v1/audits/" + auditId, admin);
        assertEquals(9, detail.body().path("data").path("checklist").size(),
                "8 canonical + 1 custom item");

        Api duplicate = postJson("/api/v1/audits/" + auditId + "/checklist", auditor,
                "{\"code\":\"" + code + "\",\"title\":\"Same code again\"}");
        assertEquals(409, duplicate.status(), duplicate.body().toString());
        assertEquals("DUPLICATE_CHECKLIST_ITEM", duplicate.errorCode());

        Api missingFields = postJson("/api/v1/audits/" + auditId + "/checklist",
                auditor, "{\"title\":\"No code\"}");
        assertEquals(400, missingFields.status(), missingFields.body().toString());
        assertEquals("VALIDATION_ERROR", missingFields.errorCode());
    }

    @Test
    void updateChangesOnlySuppliedFields() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 ChkUpd");
        String auditId = audit.get("id").asText();

        JsonNode item = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data").path("checklist").get(0);
        String itemId = item.get("id").asText();
        String originalTitle = item.get("title").asText();

        Api notesOnly = putJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId, auditor,
                "{\"title\":\"Renamed boundary item\","
                        + "\"isMandatory\":false,\"notes\":\"Boundary memo attached\"}");
        assertEquals(200, notesOnly.status(), notesOnly.body().toString());
        assertEquals("Renamed boundary item",
                notesOnly.body().path("data").path("title").asText());
        assertFalse(notesOnly.body().path("data").path("isMandatory").asBoolean());
        assertEquals("Boundary memo attached",
                notesOnly.body().path("data").path("notes").asText());

        // Partial update keeps everything else (COALESCE semantics).
        Api partial = putJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId, auditor,
                "{\"notes\":\"Additional evidence noted\"}");
        assertEquals(200, partial.status(), partial.body().toString());
        assertEquals("Renamed boundary item",
                partial.body().path("data").path("title").asText(),
                "absent fields must never be nulled");
        assertEquals("Additional evidence noted",
                partial.body().path("data").path("notes").asText());

        Api empty = putJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId, auditor, "{}");
        assertEquals(400, empty.status(), empty.body().toString());
        assertEquals("VALIDATION_ERROR", empty.errorCode());

        // Canonical item titles are data, editable like any other field —
        // but the seeded code is immutable (not part of the update contract).
        JsonNode reloaded = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data").path("checklist").get(0);
        assertNotEquals(originalTitle, reloaded.path("title").asText());
        assertEquals("CHK-BND-01", reloaded.path("code").asText());
    }

    @Test
    void verifyRequiresExplicitBooleanAndStampsVerifier() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 ChkVer");
        String auditId = audit.get("id").asText();

        JsonNode item = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data").path("checklist").get(0);
        String itemId = item.get("id").asText();
        String verifyUri = "/api/v1/audits/" + auditId + "/checklist/" + itemId
                + "/verify";

        // Node coerced a missing value to false; Java rejects the ambiguity.
        Api missingFlag = postJson(verifyUri, auditor, "{\"notes\":\"only notes\"}");
        assertEquals(400, missingFlag.status(), missingFlag.body().toString());
        assertEquals("VALIDATION_ERROR", missingFlag.errorCode());
        assertTrue(missingFlag.errorMessage().contains("isSatisfied"));

        Api verify = postJson(verifyUri, auditor,
                "{\"isSatisfied\":true,\"notes\":\"Meter photos reviewed\"}");
        assertEquals(200, verify.status(), verify.body().toString());
        assertEquals("Checklist item status updated.",
                verify.body().path("message").asText());
        JsonNode data = verify.body().path("data");
        assertTrue(data.path("isSatisfied").asBoolean());
        assertEquals(SeedIds.USER_ACME_AUDITOR, data.path("verifiedBy").asText());
        assertFalse(data.path("verifiedAt").isNull(), "verifier and time recorded");
        assertEquals("Meter photos reviewed", data.path("notes").asText());

        // Blank/absent notes leave the existing note in place (Node's
        // truthy-only overwrite); the flag still flips.
        Api flip = postJson(verifyUri, auditor, "{\"isSatisfied\":false,\"notes\":\"\"}");
        assertEquals(200, flip.status(), flip.body().toString());
        assertFalse(flip.body().path("data").path("isSatisfied").asBoolean());
        assertEquals("Meter photos reviewed", flip.body().path("data").path("notes")
                .asText(), "blank notes must not clobber existing notes");
    }

    @Test
    void checklistRowsAreScopedToTheAuditAndTenant() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        // Reviewer-grade foreign actor: RBAC runs before tenancy, so only a
        // token that passes audits.review can demonstrate the audit scope.
        String auditorApex = loginSwitchedToken(ACME_AUDITOR, SeedIds.ORG_APEX,
                "ASSURANCE_PROVIDER");
        JsonNode audit = freshAudit(admin, "P5 ChkTen");
        String auditId = audit.get("id").asText();
        String itemId = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data").path("checklist").get(0).path("id").asText();

        // Cross-tenant: the parent audit scope fails first, identically for
        // update and verify (no oracle between foreign and missing).
        Api foreignUpdate = putJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId, auditorApex,
                "{\"title\":\"attempted cross-tenant edit\"}");
        assertEquals(404, foreignUpdate.status(), foreignUpdate.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreignUpdate.errorCode());

        Api foreignVerify = postJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId + "/verify",
                auditorApex, "{\"isSatisfied\":true}");
        assertEquals(404, foreignVerify.status(), foreignVerify.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreignVerify.errorCode());

        // Wrong item id under a valid audit (including a foreign audit's real
        // item id) resolves to the same uniform 404.
        JsonNode apexAudit = freshAudit(apex, "P5 ChkTenApex");
        String apexItemId = getJson(
                        "/api/v1/audits/" + apexAudit.get("id").asText(), apex).body()
                .path("data").path("checklist").get(0).path("id").asText();

        Api unknownItem = postJson(
                "/api/v1/audits/" + auditId + "/checklist/" + UUID.randomUUID()
                        + "/verify", loginToken(ACME_AUDITOR, PASSWORD),
                "{\"isSatisfied\":true}");
        assertEquals(404, unknownItem.status(), unknownItem.body().toString());
        assertEquals("CHECKLIST_ITEM_NOT_FOUND", unknownItem.errorCode());

        Api foreignItem = postJson(
                "/api/v1/audits/" + auditId + "/checklist/" + apexItemId + "/verify",
                loginToken(ACME_AUDITOR, PASSWORD), "{\"isSatisfied\":true}");
        assertEquals(404, foreignItem.status(), foreignItem.body().toString());
        assertEquals("CHECKLIST_ITEM_NOT_FOUND", foreignItem.errorCode());
        assertEquals(unknownItem.errorMessage(), foreignItem.errorMessage(),
                "foreign and unknown item ids are indistinguishable");
    }

    @Test
    void checklistWritesRequireAuditsReview() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String manager = loginToken(ACME_MANAGER, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 ChkRbac");
        String auditId = audit.get("id").asText();
        String itemId = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data").path("checklist").get(0).path("id").asText();

        // SUSTAINABILITY_MANAGER holds audits.read but not audits.review.
        Api create = postJson("/api/v1/audits/" + auditId + "/checklist", manager,
                "{\"code\":\"CHK-NOPE\",\"title\":\"Should not exist\"}");
        assertEquals(403, create.status(), create.body().toString());
        assertEquals("FORBIDDEN", create.errorCode());

        Api update = putJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId, manager,
                "{\"title\":\"Should not stick\"}");
        assertEquals(403, update.status(), update.body().toString());

        Api verify = postJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId + "/verify",
                manager, "{\"isSatisfied\":true}");
        assertEquals(403, verify.status(), verify.body().toString());

        // The row is untouched.
        JsonNode item = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data").path("checklist").get(0);
        assertFalse(item.path("isSatisfied").asBoolean());
        assertFalse(item.has("verifiedBy"), "no verifier recorded");

        // Reads stay available to audits.read holders.
        assertEquals(200, getJson("/api/v1/audits/" + auditId, manager).status());
    }
}
