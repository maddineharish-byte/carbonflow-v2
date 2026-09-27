package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 modules 1 + 2 + 11: carbon-audit CRUD, the canonical ten-state
 * server-side state machine and its authorization/prerequisite gates.
 *
 * <p>Client-proposed transitions are never trusted: skipped states, unknown
 * targets, post-lock moves, wrong-permission roles and unsatisfied
 * prerequisites must all be rejected with the Node contract's error codes —
 * and must leave the persisted state untouched.
 */
class AuditLifecycleTest extends AuditTestBase {

    private static final String ACME_ADMIN = "admin@acmeglobal.com";
    private static final String ACME_MANAGER = "manager@acmeglobal.com";
    private static final String ACME_AUDITOR = "auditor@ey-assurance.com";
    private static final String APEX_ADMIN = "admin@apexcorp.com";

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    void createSeedsEightCanonicalChecklistItemsAndTenantsStamp() throws Exception {
        String token = loginToken(ACME_ADMIN, PASSWORD);
        JsonNode period = createPeriod(token, "P5 Create " + suffix());

        Api api = postJson("/api/v1/audits", token,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\"}");
        assertEquals(201, api.status(), api.body().toString());
        assertEquals("Audit initiated.", api.body().path("message").asText());

        JsonNode audit = api.body().get("data");
        assertEquals(SeedIds.ORG_ACME, audit.get("organizationId").asText(),
                "organization id comes from the tenant context, not the request");
        assertEquals("DRAFT", audit.get("status").asText());
        assertEquals(period.get("id").asText(), audit.get("reportingPeriodId").asText());
        assertEquals(SeedIds.USER_ACME_ADMIN, audit.get("initiatedBy").asText());

        Api detail = getJson("/api/v1/audits/" + audit.get("id").asText(), token);
        assertEquals(200, detail.status(), detail.body().toString());
        JsonNode checklist = detail.body().path("data").path("checklist");
        assertEquals(8, checklist.size(),
                "every audit seeds the 8 canonical workflow checklist items");
        List<String> codes = new ArrayList<>();
        for (JsonNode item : checklist) {
            codes.add(item.get("code").asText());
            assertTrue(item.get("isMandatory").asBoolean(),
                    "canonical items are mandatory: " + item.get("code").asText());
            assertFalse(item.get("isSatisfied").asBoolean(),
                    "canonical items start unsatisfied: " + item.get("code").asText());
        }
        assertTrue(codes.containsAll(List.of("CHK-BND-01", "CHK-FAC-02", "CHK-DAT-03",
                "CHK-EVD-04", "CHK-FAC-05", "CHK-GWP-06", "CHK-S2D-07", "CHK-FIN-08")),
                "canonical codes must be present: " + codes);
        assertEquals(8, detail.body().path("data").path("checklistSummary")
                .path("total").asInt());
        assertEquals(0, detail.body().path("data").path("checklistSummary")
                .path("satisfied").asInt());
        assertEquals(0, detail.body().path("data").path("openFindingsCount").asInt());
        assertEquals(period.get("name").asText(),
                detail.body().path("data").path("period").path("name").asText());
    }

    @Test
    void createValidatesPeriodOwnershipUniquenessAndPresence() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);

        JsonNode apexPeriod = createPeriod(apex, "P5 Apex Period " + suffix());

        // Foreign period -> uniform 404 (IDOR).
        Api foreign = postJson("/api/v1/audits", admin,
                "{\"reportingPeriodId\":\"" + apexPeriod.get("id").asText() + "\"}");
        assertEquals(404, foreign.status(), foreign.body().toString());
        assertEquals("REPORTING_PERIOD_NOT_FOUND", foreign.errorCode());

        // Missing field -> validation error.
        Api missing = postJson("/api/v1/audits", admin, "{}");
        assertEquals(400, missing.status(), missing.body().toString());
        assertEquals("VALIDATION_ERROR", missing.errorCode());

        // One audit per (organization, period) — V1 uq_audit_period.
        JsonNode period = createPeriod(admin, "P5 Unique " + suffix());
        Api first = postJson("/api/v1/audits", admin,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\"}");
        assertEquals(201, first.status(), first.body().toString());
        Api duplicate = postJson("/api/v1/audits", admin,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\"}");
        assertEquals(409, duplicate.status(), duplicate.body().toString());
        assertEquals("AUDIT_ALREADY_EXISTS", duplicate.errorCode());
    }

    @Test
    void createRequiresAuditsCreatePermission() throws Exception {
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode period = createPeriod(loginToken(ACME_ADMIN, PASSWORD),
                "P5 RBAC " + suffix());
        Api api = postJson("/api/v1/audits", auditor,
                "{\"reportingPeriodId\":\"" + period.get("id").asText() + "\"}");
        assertEquals(403, api.status(), api.body().toString());
        assertEquals("FORBIDDEN", api.errorCode());
    }

    // ------------------------------------------------------------------
    // Read / list scoping
    // ------------------------------------------------------------------

    @Test
    void listAndDetailStayTenantScopedWithNodeEnrichment() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);

        JsonNode audit = freshAudit(admin, "P5 List");
        String auditId = audit.get("id").asText();

        Api acmeList = getJson("/api/v1/audits", admin);
        assertEquals(200, acmeList.status(), acmeList.body().toString());
        assertTrue(listContains(acmeList.body().path("data"), auditId),
                "own audit must appear in the org list");
        JsonNode first = acmeList.body().path("data").get(0);
        assertTrue(first.has("periodName"), "list enriches with periodName");
        assertTrue(first.has("checklistSummary"), "list enriches with checklistSummary");
        assertTrue(first.has("openFindingsCount"),
                "list enriches with openFindingsCount");

        Api apexList = getJson("/api/v1/audits", apex);
        assertEquals(200, apexList.status());
        assertFalse(listContains(apexList.body().path("data"), auditId),
                "another tenant's audit must never appear in the list");
    }

    @Test
    void detailForeignAndMalformedIdsAreIndistinguishable() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 IDOR");
        String auditId = audit.get("id").asText();

        Api own = getJson("/api/v1/audits/" + auditId, admin);
        assertEquals(200, own.status());
        assertTrue(own.body().path("data").has("checklist"));
        assertTrue(own.body().path("data").has("findings"));
        assertTrue(own.body().path("data").has("comments"));

        Api foreign = getJson("/api/v1/audits/" + auditId, apex);
        Api malformed = getJson("/api/v1/audits/not-a-uuid", apex);
        assertEquals(404, foreign.status(), foreign.body().toString());
        assertEquals(404, malformed.status(), malformed.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreign.errorCode());
        assertEquals("AUDIT_NOT_FOUND", malformed.errorCode(),
                "malformed ids answer exactly like foreign ids (anti-enumeration)");
        assertEquals(foreign.errorMessage(), malformed.errorMessage());
        assertTrue(foreign.errorMessage()
                        .contains("does not exist or access denied"),
                "Phase 4/5 message shape: " + foreign.errorMessage());

        // Reads stay open for other org members holding audits.read.
        String manager = loginToken(ACME_MANAGER, PASSWORD);
        assertEquals(200, getJson("/api/v1/audits/" + auditId, manager).status());
    }

    // ------------------------------------------------------------------
    // Draft update
    // ------------------------------------------------------------------

    @Test
    void draftUpdateChangesNotesOnlyWhileDraft() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Draft");
        String auditId = audit.get("id").asText();

        Api updated = putJson("/api/v1/audits/" + auditId, admin,
                "{\"notes\":\"Boundary scoping in progress\"}");
        assertEquals(200, updated.status(), updated.body().toString());
        assertEquals("Boundary scoping in progress",
                updated.body().path("data").path("notes").asText());

        Api noNotes = putJson("/api/v1/audits/" + auditId, admin, "{}");
        assertEquals(400, noNotes.status(), noNotes.body().toString());
        assertEquals("VALIDATION_ERROR", noNotes.errorCode());

        Api foreign = putJson("/api/v1/audits/" + auditId, apex,
                "{\"notes\":\"cross-tenant edit attempt\"}");
        assertEquals(404, foreign.status(), foreign.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreign.errorCode());

        Api submit = transition(admin, auditId, "SUBMITTED", null);
        assertEquals(200, submit.status(), submit.body().toString());

        Api afterSubmit = putJson("/api/v1/audits/" + auditId, admin,
                "{\"notes\":\"too late\"}");
        assertEquals(409, afterSubmit.status(), afterSubmit.body().toString());
        assertEquals("AUDIT_NOT_DRAFT", afterSubmit.errorCode());
    }

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    @Test
    void validTransitionUpdatesStatusLogsCommentAndEchoesNodeMessage()
            throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Transition");
        String auditId = audit.get("id").asText();

        Api api = transition(admin, auditId, "SUBMITTED", "Ready for data collection");
        assertEquals(200, api.status(), api.body().toString());
        assertEquals("SUBMITTED", api.body().path("data").path("status").asText());
        assertEquals("Audit successfully transitioned to SUBMITTED.",
                api.body().path("message").asText(),
                "Node's exact success message");

        Api detail = getJson("/api/v1/audits/" + auditId, admin);
        assertEquals("SUBMITTED",
                detail.body().path("data").path("status").asText());
        boolean logFound = false;
        for (JsonNode comment : detail.body().path("data").path("comments")) {
            if (comment.path("commentText").asText()
                    .contains("Transitioned status from [DRAFT] to [SUBMITTED]. "
                            + "Reason: Ready for data collection")) {
                logFound = true;
            }
        }
        assertTrue(logFound, "every transition writes a server-side log comment");
    }

    @Test
    void invalidSkippedAndUnknownTransitionsUseNodeMessages() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Invalid");
        String auditId = audit.get("id").asText();

        // Skipped state.
        Api skipped = transition(admin, auditId, "REVIEW", null);
        assertEquals(400, skipped.status(), skipped.body().toString());
        assertEquals("INVALID_TRANSITION", skipped.errorCode());
        assertEquals("Cannot transition audit from 'DRAFT' to 'REVIEW'. "
                        + "Valid targets: SUBMITTED.",
                skipped.errorMessage(), "Node's exact rejection message");

        // Unknown target state.
        Api unknown = transition(admin, auditId, "SEALED", null);
        assertEquals(400, unknown.status(), unknown.body().toString());
        assertEquals("INVALID_TRANSITION", unknown.errorCode());
        assertEquals("Cannot transition audit from 'DRAFT' to 'SEALED'. "
                        + "Valid targets: SUBMITTED.", unknown.errorMessage());

        // Backwards transition.
        Api submit = transition(admin, auditId, "SUBMITTED", null);
        assertEquals(200, submit.status(), submit.body().toString());
        Api backwards = transition(admin, auditId, "DRAFT", null);
        assertEquals(400, backwards.status(), backwards.body().toString());
        assertEquals("INVALID_TRANSITION", backwards.errorCode());

        // Nothing changed server-side.
        Api detail = getJson("/api/v1/audits/" + auditId, admin);
        assertEquals("SUBMITTED", detail.body().path("data").path("status").asText());
    }

    @Test
    void perTransitionPermissionNarrowsTheEndpointUnion() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        String manager = loginToken(ACME_MANAGER, PASSWORD);

        // ASSURANCE_PROVIDER holds audits.review (passes the §2.6 endpoint
        // union) but NOT audits.submit — the service must still refuse.
        JsonNode audit = freshAudit(admin, "P5 Perm");
        String auditId = audit.get("id").asText();
        Api auditorSubmit = transition(auditor, auditId, "SUBMITTED", null);
        assertEquals(403, auditorSubmit.status(), auditorSubmit.body().toString());
        assertEquals("FORBIDDEN", auditorSubmit.errorCode());
        assertEquals("Your role cannot perform the 'DRAFT -> SUBMITTED' transition.",
                auditorSubmit.errorMessage());

        driveToReview(admin, auditId);

        // COMPANY_ADMIN holds audits.submit (union passes) but not
        // audits.review — reviewer outcomes are refused at the service.
        Api adminReject = transition(admin, auditId, "REJECTED", "some reason");
        assertEquals(403, adminReject.status(), adminReject.body().toString());
        assertEquals("FORBIDDEN", adminReject.errorCode());

        // SUSTAINABILITY_MANAGER likewise lacks audits.review.
        Api managerCorrection = transition(manager, auditId, "CORRECTION_REQUESTED",
                null);
        assertEquals(403, managerCorrection.status(),
                managerCorrection.body().toString());
        assertEquals("FORBIDDEN", managerCorrection.errorCode());

        // ASSURANCE_PROVIDER lacks audits.approve — approval is refused and
        // NO approval row may appear (nothing persisted on failure).
        Api auditorApprove = transition(auditor, auditId, "APPROVED", null);
        assertEquals(403, auditorApprove.status(), auditorApprove.body().toString());
        assertEquals("FORBIDDEN", auditorApprove.errorCode());
        Api approvals = getJson("/api/v1/audits/" + auditId + "/approvals", admin);
        assertEquals(200, approvals.status());
        assertEquals(0, approvals.body().path("data").size(),
                "a refused approval must not create an approval record");

        // The audit never moved.
        Api detail = getJson("/api/v1/audits/" + auditId, admin);
        assertEquals("REVIEW", detail.body().path("data").path("status").asText());
    }

    @Test
    void checklistGateBlocksApprovalReadinessAndLock() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Gate");
        String auditId = audit.get("id").asText();
        driveToReview(admin, auditId);

        Api approve = transition(admin, auditId, "APPROVED", null);
        assertEquals(400, approve.status(), approve.body().toString());
        assertEquals("CHECKLIST_INCOMPLETE", approve.errorCode());
        assertTrue(approve.errorMessage()
                        .startsWith("Cannot transition to 'APPROVED'. "
                                + "8 mandatory checklist item(s) are unsatisfied:"),
                "Node's exact gate message: " + approve.errorMessage());
        for (String code : new String[] {"CHK-BND-01", "CHK-FAC-02", "CHK-DAT-03",
                "CHK-EVD-04", "CHK-FAC-05", "CHK-GWP-06", "CHK-S2D-07", "CHK-FIN-08"}) {
            assertTrue(approve.errorMessage().contains(code),
                    "gate message lists " + code);
        }

        verifyAllChecklist(auditor, auditId);
        assertEquals(200, transition(admin, auditId, "APPROVED", null).status());
        assertEquals(200, transition(admin, auditId, "AUDIT_READY", null).status());

        // Re-opening one mandatory item blocks the final lock as well.
        Api detail = getJson("/api/v1/audits/" + auditId, auditor);
        String itemId = detail.body().path("data").path("checklist").get(0)
                .get("id").asText();
        Api unverify = postJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId + "/verify",
                auditor, "{\"isSatisfied\":false}");
        assertEquals(200, unverify.status(), unverify.body().toString());

        Api lock = transition(admin, auditId, "LOCKED", null);
        assertEquals(400, lock.status(), lock.body().toString());
        assertEquals("CHECKLIST_INCOMPLETE", lock.errorCode());

        // Restore and lock succeeds.
        assertEquals(200, postJson(
                        "/api/v1/audits/" + auditId + "/checklist/" + itemId + "/verify",
                        auditor, "{\"isSatisfied\":true}").status());
        assertEquals(200, transition(admin, auditId, "LOCKED", null).status());
    }

    @Test
    void unresolvedHighSeverityFindingsBlockApprovalOnly() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 FindGate");
        String auditId = audit.get("id").asText();
        driveToReview(admin, auditId);
        verifyAllChecklist(auditor, auditId);

        // LOW does not block.
        Api low = postJson("/api/v1/audits/" + auditId + "/findings", auditor,
                "{\"title\":\"Minor wording issue\",\"description\":\"Cosmetic\"}");
        assertEquals(201, low.status(), low.body().toString());

        Api approveWithLow = transition(admin, auditId, "APPROVED", null);
        assertEquals(200, approveWithLow.status(), approveWithLow.body().toString());

        // CRITICAL blocks AUDIT_READY until resolved.
        JsonNode critical = postJson("/api/v1/audits/" + auditId + "/findings",
                        auditor,
                        "{\"title\":\"Material misstatement\",\"description\":\"Restatement needed\","
                                + "\"severity\":\"CRITICAL\"}").body().get("data");
        Api ready = transition(admin, auditId, "AUDIT_READY", null);
        assertEquals(400, ready.status(), ready.body().toString());
        assertEquals("UNRESOLVED_FINDINGS", ready.errorCode());
        assertTrue(ready.errorMessage().contains("Material misstatement"),
                "gate message names the blocking finding");

        Api resolve = postJson("/api/v1/audits/" + auditId + "/findings/"
                + critical.get("id").asText() + "/resolve", auditor, "{}");
        assertEquals(200, resolve.status(), resolve.body().toString());

        assertEquals(200, transition(admin, auditId, "AUDIT_READY", null).status());
    }

    @Test
    void correctionAndRejectionPrerequisitesAreEnforced() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Paths");
        String auditId = audit.get("id").asText();
        driveToReview(admin, auditId);

        // Correction path requires a logged finding (workflow doc §2) AND
        // audits.review — the reviewer makes the call, not the admin.
        Api noFinding = transition(auditor, auditId, "CORRECTION_REQUESTED", null);
        assertEquals(400, noFinding.status(), noFinding.body().toString());
        assertEquals("NO_REVIEW_FINDING", noFinding.errorCode());

        postJson("/api/v1/audits/" + auditId + "/findings", auditor,
                "{\"title\":\"Data gap\",\"description\":\"Meter missing\"}");

        Api correction = transition(auditor, auditId, "CORRECTION_REQUESTED", null);
        assertEquals(200, correction.status(), correction.body().toString());
        assertEquals("CORRECTION_REQUESTED",
                correction.body().path("data").path("status").asText());

        // Resume path works and stays inside the machine.
        assertEquals(200, transition(admin, auditId, "DATA_COLLECTION", null).status());
        assertEquals(200, transition(admin, auditId, "VALIDATION", null).status());
        assertEquals(200, transition(admin, auditId, "REVIEW", null).status());

        // Rejection requires a reason (auditor holds audits.review).
        Api noReason = transition(auditor, auditId, "REJECTED", null);
        assertEquals(400, noReason.status(), noReason.body().toString());
        assertEquals("VALIDATION_ERROR", noReason.errorCode());
        assertTrue(noReason.errorMessage().contains("reason is required"));

        Api rejected = transition(auditor, auditId, "REJECTED",
                "Material data quality failure");
        assertEquals(200, rejected.status(), rejected.body().toString());
        assertEquals("REJECTED",
                rejected.body().path("data").path("status").asText());
        assertEquals(200, transition(admin, auditId, "DATA_COLLECTION", null).status());
    }

    @Test
    void fullLifecyclePersistsApprovalLockEventHashAndPeriodFreeze()
            throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode period = createPeriod(admin, "P5 Full " + suffix());
        JsonNode audit = createAudit(admin, period.get("id").asText());
        String auditId = audit.get("id").asText();

        driveToReview(admin, auditId);
        assertEquals(0, getJson("/api/v1/audits/" + auditId + "/approvals", admin)
                .body().path("data").size(), "no approval before REVIEW approval");

        verifyAllChecklist(auditor, auditId);

        Api approve = transition(admin, auditId, "APPROVED", "Checklist complete");
        assertEquals(200, approve.status(), approve.body().toString());
        assertEquals(SeedIds.USER_ACME_ADMIN,
                approve.body().path("data").path("approvedBy").asText());

        Api approvals = getJson("/api/v1/audits/" + auditId + "/approvals", admin);
        assertEquals(200, approvals.status());
        assertEquals(1, approvals.body().path("data").size());
        JsonNode approval = approvals.body().path("data").get(0);
        assertEquals(SeedIds.USER_ACME_ADMIN,
                approval.path("approverId").asText());
        assertEquals("COMPANY_ADMIN", approval.path("role").asText());
        assertTrue(approval.path("signatureHash").asText().matches("[0-9a-f]{64}"),
                "approval carries a SHA-256 integrity hash");
        assertTrue(approval.path("timestamp").asText().length() > 0);

        assertEquals(200, transition(admin, auditId, "AUDIT_READY", null).status());
        Api lock = transition(admin, auditId, "LOCKED", null);
        assertEquals(200, lock.status(), lock.body().toString());
        assertEquals("LOCKED", lock.body().path("data").path("status").asText());
        assertFalse(lock.body().path("data").path("lockedAt").isNull(),
                "lock stamps locked_at");

        Api detail = getJson("/api/v1/audits/" + auditId, admin);
        JsonNode data = detail.body().path("data");
        assertEquals("LOCKED", data.path("status").asText());
        JsonNode lockEvent = data.path("lockEvent");
        assertEquals(SeedIds.USER_ACME_ADMIN, lockEvent.path("lockedBy").asText());
        assertTrue(lockEvent.path("inventoryHash").asText().matches("[0-9a-f]{64}"),
                "lock event stores a SHA-256 governance-state hash");

        // The reporting period freezes (Node parity).
        Api frozen = getJson(
                "/api/v1/reporting-periods/" + period.get("id").asText(), admin);
        assertEquals(200, frozen.status(), frozen.body().toString());
        assertEquals("LOCKED", frozen.body().path("data").path("status").asText(),
                "AUDIT_READY -> LOCKED freezes the associated reporting period");

        // LOCKED is terminal: every further move is refused by the machine.
        for (String target : new String[] {"DRAFT", "REVIEW", "APPROVED",
                "AUDIT_READY", "SUBMITTED"}) {
            Api attempt = transition(admin, auditId, target, null);
            assertEquals(400, attempt.status(), target + ": " + attempt.body());
            assertEquals("INVALID_TRANSITION", attempt.errorCode());
            assertTrue(attempt.errorMessage().endsWith("Valid targets: ."),
                    "LOCKED has no outgoing transitions: " + attempt.errorMessage());
        }
        assertEquals("LOCKED",
                getJson("/api/v1/audits/" + auditId, admin)
                        .body().path("data").path("status").asText(),
                "state unchanged after refused transitions");
    }
}
