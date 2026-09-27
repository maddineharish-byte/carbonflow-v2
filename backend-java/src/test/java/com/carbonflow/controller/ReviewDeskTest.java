package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 modules 4–7: the review desk — findings, comments, correction
 * requests and approvals.
 *
 * <p>Proves: author-only comment mutation, the {@code audits.review} write
 * gate (Node left comments ungated — documented defect fix per API.md §2.6),
 * the REVIEW/CORRECTION_REQUESTED correction window, item-level IDOR
 * (own-audit + foreign-child id resolves to the same 404 as an unknown id),
 * and that approvals exist <b>only</b> as a side effect of the governed
 * REVIEW → APPROVED transition (no POST endpoint at all).
 */
class ReviewDeskTest extends AuditTestBase {

    private static final String ACME_ADMIN = "admin@acmeglobal.com";
    private static final String ACME_MANAGER = "manager@acmeglobal.com";
    private static final String ACME_AUDITOR = "auditor@ey-assurance.com";
    private static final String APEX_ADMIN = "admin@apexcorp.com";

    // ------------------------------------------------------------------
    // Findings
    // ------------------------------------------------------------------

    @Test
    void findingLifecycleDefaultsValidationAndResolutionStamp() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Fnd");
        String auditId = audit.get("id").asText();
        String findingsUri = "/api/v1/audits/" + auditId + "/findings";

        Api created = postJson(findingsUri, auditor,
                "{\"title\":\"Missing utility bill\",\"description\":"
                        + "\"July gas invoice not in the data set\"}");
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("Review finding recorded.",
                created.body().path("message").asText());
        JsonNode finding = created.body().path("data");
        assertEquals("MEDIUM", finding.path("severity").asText(),
                "Node's default severity");
        assertEquals("OPEN", finding.path("status").asText());
        assertEquals(SeedIds.USER_ACME_AUDITOR, finding.path("createdBy").asText());
        assertFalse(finding.has("resolvedBy"),
                "resolvedBy omitted until a resolution is stamped");
        String findingId = finding.path("id").asText();

        // Node's exact validation message.
        Api missing = postJson(findingsUri, auditor, "{\"title\":\"Only a title\"}");
        assertEquals(400, missing.status(), missing.body().toString());
        assertEquals("VALIDATION_ERROR", missing.errorCode());
        assertEquals("Title and description required.", missing.errorMessage());

        // Schema CHECK enforced server-side (400, never a 503).
        Api badSeverity = postJson(findingsUri, auditor,
                "{\"title\":\"x\",\"description\":\"y\",\"severity\":\"SEVERE\"}");
        assertEquals(400, badSeverity.status(), badSeverity.body().toString());
        assertEquals("VALIDATION_ERROR", badSeverity.errorCode());

        // Update severity + resolve through PUT with status stamping.
        Api update = putJson(findingsUri + "/" + findingId, auditor,
                "{\"severity\":\"HIGH\",\"status\":\"RESOLVED\"}");
        assertEquals(200, update.status(), update.body().toString());
        assertEquals("HIGH", update.body().path("data").path("severity").asText());
        assertEquals(SeedIds.USER_ACME_AUDITOR,
                update.body().path("data").path("resolvedBy").asText());

        Api badStatus = putJson(findingsUri + "/" + findingId, auditor,
                "{\"status\":\"CLOSED\"}");
        assertEquals(400, badStatus.status(), badStatus.body().toString());
        assertEquals("VALIDATION_ERROR", badStatus.errorCode());

        // Resolution endpoint mirrors Node's message.
        JsonNode second = postJson(findingsUri, auditor,
                        "{\"title\":\"Second gap\",\"description\":\"Meter log gap\"}")
                .body().path("data");
        Api resolve = postJson(findingsUri + "/" + second.path("id").asText()
                + "/resolve", auditor, "{}");
        assertEquals(200, resolve.status(), resolve.body().toString());
        assertEquals("Finding marked as resolved.",
                resolve.body().path("message").asText());
        assertEquals("RESOLVED", resolve.body().path("data").path("status").asText());

        // List read is open to audits.read holders.
        Api list = getJson(findingsUri, loginToken(ACME_MANAGER, PASSWORD));
        assertEquals(200, list.status(), list.body().toString());
        assertTrue(list.body().path("data").size() >= 2);
    }

    @Test
    void findingActivityLinkIsValidatedForOwnership() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);

        JsonNode acmeAudit = freshAudit(admin, "P5 FndAct");
        String auditId = acmeAudit.get("id").asText();

        JsonNode apexPeriod = createPeriod(apex, "P5 FndApex " + suffix());
        JsonNode apexFacility = createFacility(apex, "Apex Plant " + suffix(),
                "AX" + suffix());
        String foreignActivity = insertActivityData(SeedIds.ORG_APEX,
                apexPeriod.get("id").asText(), apexFacility.get("id").asText());

        Api crossTenant = postJson("/api/v1/audits/" + auditId + "/findings", auditor,
                "{\"title\":\"Linked\",\"description\":\"Linking a foreign record\","
                        + "\"activityDataId\":\"" + foreignActivity + "\"}");
        assertEquals(404, crossTenant.status(), crossTenant.body().toString());
        assertEquals("ACTIVITY_DATA_NOT_FOUND", crossTenant.errorCode());

        Api unknown = postJson("/api/v1/audits/" + auditId + "/findings", auditor,
                "{\"title\":\"Linked\",\"description\":\"Linking a missing record\","
                        + "\"activityDataId\":\"" + UUID.randomUUID() + "\"}");
        assertEquals(404, unknown.status(), unknown.body().toString());
        assertEquals("ACTIVITY_DATA_NOT_FOUND", unknown.errorCode(),
                "foreign and missing activity ids are indistinguishable");

        // Nothing was persisted for either attempt.
        Api list = getJson("/api/v1/audits/" + auditId + "/findings", auditor);
        assertEquals(200, list.status());
        assertEquals(0, list.body().path("data").size());
    }

    @Test
    void findingItemLevelIdorAndRbac() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        String manager = loginToken(ACME_MANAGER, PASSWORD);
        // Reviewer-grade foreign actor (RBAC precedes tenancy).
        String auditorApex = loginSwitchedToken(ACME_AUDITOR, SeedIds.ORG_APEX,
                "ASSURANCE_PROVIDER");

        JsonNode acmeAudit = freshAudit(admin, "P5 FndIdor");
        String auditId = acmeAudit.get("id").asText();
        JsonNode finding = postJson("/api/v1/audits/" + auditId + "/findings",
                        auditor, "{\"title\":\"Own\",\"description\":\"Own finding\"}")
                .body().path("data");
        String findingId = finding.path("id").asText();

        // The tenant's own audit still rejects a foreign child id (the
        // audit_id + id pair must match within the organization).
        JsonNode apexAudit = freshAudit(apex, "P5 FndIdorApex");
        // CA holds audits.create but not audits.review — the foreign finding
        // must be raised by a reviewer-grade APEX actor, and creation is
        // asserted so a silent 403 can never masquerade as an empty id.
        Api apexFindingResp = postJson(
                "/api/v1/audits/" + apexAudit.get("id").asText() + "/findings",
                auditorApex, "{\"title\":\"Foreign\",\"description\":\"Apex finding\"}");
        assertEquals(201, apexFindingResp.status(),
                apexFindingResp.body().toString());
        JsonNode apexFinding = apexFindingResp.body().path("data");

        Api foreignChild = putJson(
                "/api/v1/audits/" + auditId + "/findings/"
                        + apexFinding.path("id").asText(),
                auditor, "{\"status\":\"RESOLVED\"}");
        assertEquals(404, foreignChild.status(), foreignChild.body().toString());
        assertEquals("FINDING_NOT_FOUND", foreignChild.errorCode());

        Api unknownChild = putJson(
                "/api/v1/audits/" + auditId + "/findings/" + UUID.randomUUID(),
                auditor, "{\"status\":\"RESOLVED\"}");
        assertEquals(404, unknownChild.status(), unknownChild.body().toString());
        assertEquals("FINDING_NOT_FOUND", unknownChild.errorCode());
        assertEquals(unknownChild.errorMessage(), foreignChild.errorMessage());

        // Cross-tenant update through the audit scope.
        Api crossTenant = putJson(
                "/api/v1/audits/" + auditId + "/findings/" + findingId, auditorApex,
                "{\"status\":\"DISMISSED\"}");
        assertEquals(404, crossTenant.status(), crossTenant.body().toString());
        assertEquals("AUDIT_NOT_FOUND", crossTenant.errorCode());
        assertEquals("OPEN", getJson(
                        "/api/v1/audits/" + auditId + "/findings", auditor).body()
                .path("data").get(0).path("status").asText(),
                "the foreign edit must change nothing");

        // Writes require audits.review (SUSTAINABILITY_MANAGER lacks it).
        Api managerCreate = postJson("/api/v1/audits/" + auditId + "/findings",
                manager, "{\"title\":\"no\",\"description\":\"nope\"}");
        assertEquals(403, managerCreate.status(), managerCreate.body().toString());
        assertEquals("FORBIDDEN", managerCreate.errorCode());
        Api managerResolve = postJson(
                "/api/v1/audits/" + auditId + "/findings/" + findingId + "/resolve",
                manager, "{}");
        assertEquals(403, managerResolve.status(), managerResolve.body().toString());
    }

    // ------------------------------------------------------------------
    // Comments
    // ------------------------------------------------------------------

    @Test
    void commentsHydrateAuthorAndRejectBlankText() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Cmt");
        String auditId = audit.get("id").asText();
        String commentsUri = "/api/v1/audits/" + auditId + "/comments";

        Api blank = postJson(commentsUri, auditor, "{\"commentText\":\"   \"}");
        assertEquals(400, blank.status(), blank.body().toString());
        assertEquals("EMPTY_COMMENT", blank.errorCode());
        assertEquals("Comment text cannot be blank.", blank.errorMessage());

        Api missing = postJson(commentsUri, auditor, "{}");
        assertEquals(400, missing.status(), missing.body().toString());
        assertEquals("EMPTY_COMMENT", missing.errorCode());

        Api created = postJson(commentsUri, auditor,
                "{\"commentText\":\"  Boundary memo reviewed.  \"}");
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("Comment added.", created.body().path("message").asText());
        JsonNode comment = created.body().path("data");
        assertEquals("Boundary memo reviewed.", comment.path("commentText").asText(),
                "text is stored trimmed");
        assertEquals("auditor@ey-assurance.com", comment.path("userName").asText());
        assertEquals("ASSURANCE_PROVIDER", comment.path("userRole").asText(),
                "author role hydrates from the org membership");

        Api list = getJson(commentsUri, admin);
        assertEquals(200, list.status(), list.body().toString());
        assertTrue(list.body().path("data").size() >= 1);
    }

    @Test
    void commentWritesRequireAuditsReviewPerApiDoc() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String manager = loginToken(ACME_MANAGER, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 CmtRbac");
        String auditId = audit.get("id").asText();

        Api managerCreate = postJson("/api/v1/audits/" + auditId + "/comments",
                manager, "{\"commentText\":\"Should be refused\"}");
        assertEquals(403, managerCreate.status(), managerCreate.body().toString());
        assertEquals("FORBIDDEN", managerCreate.errorCode(),
                "API.md §2.6 gates comment creation on audits.review (Node was "
                        + "ungated — documented defect fix)");

        // Reads remain open to audits.read holders.
        assertEquals(200, getJson("/api/v1/audits/" + auditId + "/comments",
                manager).status());
    }

    @Test
    void onlyTheAuthorEditsOrDeletesTheirComment() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 CmtAuth");
        String auditId = audit.get("id").asText();
        String commentsUri = "/api/v1/audits/" + auditId + "/comments";

        JsonNode own = postJson(commentsUri, auditor,
                "{\"commentText\":\"Reviewer note\"}").body().path("data");
        String ownId = own.path("id").asText();

        // The transition log comment is written server-side under the
        // transitioning user — it is not the reviewer's to rewrite.
        assertEquals(200, transition(admin, auditId, "SUBMITTED", null).status());
        String systemCommentId = null;
        JsonNode comments = getJson(commentsUri, auditor).body().path("data");
        for (JsonNode comment : comments) {
            if (comment.path("commentText").asText()
                    .startsWith("Transitioned status from")) {
                systemCommentId = comment.path("id").asText();
            }
        }
        assertTrue(systemCommentId != null, "transition log comment exists");

        Api notAuthor = putJson(commentsUri + "/" + systemCommentId, auditor,
                "{\"commentText\":\"Rewriting history\"}");
        assertEquals(403, notAuthor.status(), notAuthor.body().toString());
        assertEquals("NOT_COMMENT_AUTHOR", notAuthor.errorCode());

        Api deleteNotAuthor = deleteJson(commentsUri + "/" + systemCommentId,
                auditor);
        assertEquals(403, deleteNotAuthor.status(), deleteNotAuthor.body().toString());
        assertEquals("NOT_COMMENT_AUTHOR", deleteNotAuthor.errorCode());

        // The author may edit and delete their own.
        Api edit = putJson(commentsUri + "/" + ownId, auditor,
                "{\"commentText\":\"Reviewer note (updated)\"}");
        assertEquals(200, edit.status(), edit.body().toString());
        assertEquals("Comment updated.", edit.body().path("message").asText());
        assertEquals("Reviewer note (updated)",
                edit.body().path("data").path("commentText").asText());

        Api deleteOwn = deleteJson(commentsUri + "/" + ownId, auditor);
        assertEquals(200, deleteOwn.status(), deleteOwn.body().toString());
        assertEquals("Comment removed.", deleteOwn.body().path("message").asText());

        JsonNode after = getJson(commentsUri, auditor).body().path("data");
        for (JsonNode comment : after) {
            assertFalse(ownId.equals(comment.path("id").asText()),
                    "deleted comment must be gone");
        }
    }

    @Test
    void commentItemLevelIdor() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        // Reviewer-grade foreign actor (RBAC precedes tenancy).
        String auditorApex = loginSwitchedToken(ACME_AUDITOR, SeedIds.ORG_APEX,
                "ASSURANCE_PROVIDER");
        JsonNode audit = freshAudit(admin, "P5 CmtTen");
        String auditId = audit.get("id").asText();
        String commentsUri = "/api/v1/audits/" + auditId + "/comments";

        JsonNode comment = postJson(commentsUri, auditor,
                "{\"commentText\":\"Tenant-scoped note\"}").body().path("data");

        Api foreignEdit = putJson(
                commentsUri + "/" + comment.path("id").asText(), auditorApex,
                "{\"commentText\":\"cross-tenant edit\"}");
        assertEquals(404, foreignEdit.status(), foreignEdit.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreignEdit.errorCode());

        Api foreignDelete = deleteJson(
                commentsUri + "/" + comment.path("id").asText(), auditorApex);
        assertEquals(404, foreignDelete.status(), foreignDelete.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreignDelete.errorCode());

        // Unknown comment under the caller's own audit -> uniform 404.
        Api unknown = deleteJson(commentsUri + "/" + UUID.randomUUID(), auditor);
        assertEquals(404, unknown.status(), unknown.body().toString());
        assertEquals("COMMENT_NOT_FOUND", unknown.errorCode());

        // Still there.
        assertEquals(200, getJson(commentsUri, auditor).status());
    }

    // ------------------------------------------------------------------
    // Correction requests
    // ------------------------------------------------------------------

    @Test
    void correctionRequestsFollowTheWorkflowWindowAndOwnTheirActivity()
            throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        String manager = loginToken(ACME_MANAGER, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);

        JsonNode period = createPeriod(admin, "P5 Cor " + suffix());
        JsonNode facility = createFacility(admin, "P5 Cor Plant " + suffix(),
                "PC" + suffix());
        JsonNode audit = createAudit(admin, period.get("id").asText());
        String auditId = audit.get("id").asText();
        String correctionsUri = "/api/v1/audits/" + auditId + "/corrections";

        // Outside the correction window.
        Api early = postJson(correctionsUri, auditor,
                "{\"activityDataId\":\"" + UUID.randomUUID() + "\",\"reason\":\"too early\"}");
        assertEquals(409, early.status(), early.body().toString());
        assertEquals("AUDIT_NOT_IN_CORRECTION_WINDOW", early.errorCode());

        driveToReview(admin, auditId);
        String activityId = insertActivityData(SeedIds.ORG_ACME,
                period.get("id").asText(), facility.get("id").asText());

        Api created = postJson(correctionsUri, auditor,
                "{\"activityDataId\":\"" + activityId + "\","
                        + "\"reason\":\"July gas invoice missing\"}");
        assertEquals(201, created.status(), created.body().toString());
        assertEquals("Correction request created.",
                created.body().path("message").asText());
        JsonNode correction = created.body().path("data");
        assertEquals(activityId, correction.path("activityDataId").asText());
        assertEquals(SeedIds.USER_ACME_AUDITOR, correction.path("requestedBy").asText());
        assertFalse(correction.path("isResolved").asBoolean());
        String correctionId = correction.path("id").asText();

        // Required fields.
        Api noReason = postJson(correctionsUri, auditor,
                "{\"activityDataId\":\"" + activityId + "\"}");
        assertEquals(400, noReason.status(), noReason.body().toString());
        assertEquals("VALIDATION_ERROR", noReason.errorCode());
        Api noActivity = postJson(correctionsUri, auditor,
                "{\"reason\":\"missing target\"}");
        assertEquals(400, noActivity.status(), noActivity.body().toString());
        assertEquals("VALIDATION_ERROR", noActivity.errorCode());

        // Cross-tenant activity -> uniform 404.
        JsonNode apexPeriod = createPeriod(apex, "P5 CorApex " + suffix());
        JsonNode apexFacility = createFacility(apex, "Apex Cor Plant " + suffix(),
                "AC" + suffix());
        String foreignActivity = insertActivityData(SeedIds.ORG_APEX,
                apexPeriod.get("id").asText(), apexFacility.get("id").asText());
        Api crossTenant = postJson(correctionsUri, auditor,
                "{\"activityDataId\":\"" + foreignActivity + "\",\"reason\":\"nope\"}");
        assertEquals(404, crossTenant.status(), crossTenant.body().toString());
        assertEquals("ACTIVITY_DATA_NOT_FOUND", crossTenant.errorCode());

        // RBAC: SUSTAINABILITY_MANAGER lacks audits.review.
        Api managerCreate = postJson(correctionsUri, manager,
                "{\"activityDataId\":\"" + activityId + "\",\"reason\":\"nope\"}");
        assertEquals(403, managerCreate.status(), managerCreate.body().toString());
        assertEquals("FORBIDDEN", managerCreate.errorCode());

        // Workflow integration: the correction survives the round trip.
        postJson("/api/v1/audits/" + auditId + "/findings", auditor,
                "{\"title\":\"Gap\",\"description\":\"Correction needed\"}");
        assertEquals(200, transition(auditor, auditId, "CORRECTION_REQUESTED",
                null).status());
        Api during = getJson(correctionsUri, auditor);
        assertEquals(200, during.status());
        assertTrue(during.body().path("data").size() >= 1);

        assertEquals(200, transition(admin, auditId, "DATA_COLLECTION", null).status());

        // Resolve flag.
        Api resolve = putJson(correctionsUri + "/" + correctionId, auditor,
                "{\"isResolved\":true}");
        assertEquals(200, resolve.status(), resolve.body().toString());
        assertTrue(resolve.body().path("data").path("isResolved").asBoolean());

        Api missingFlag = putJson(correctionsUri + "/" + correctionId, auditor, "{}");
        assertEquals(400, missingFlag.status(), missingFlag.body().toString());
        assertEquals("VALIDATION_ERROR", missingFlag.errorCode());

        // Cross-tenant read/write through the audit scope.
        Api foreignList = getJson(correctionsUri, apex);
        assertEquals(404, foreignList.status(), foreignList.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreignList.errorCode());
        Api foreignUpdate = putJson(correctionsUri + "/" + correctionId,
                loginSwitchedToken(ACME_AUDITOR, SeedIds.ORG_APEX,
                        "ASSURANCE_PROVIDER"),
                "{\"isResolved\":false}");
        assertEquals(404, foreignUpdate.status(), foreignUpdate.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreignUpdate.errorCode());
    }

    // ------------------------------------------------------------------
    // Approvals
    // ------------------------------------------------------------------

    @Test
    void approvalsHaveNoCreateEndpointAndStayTransitionDriven() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String manager = loginToken(ACME_MANAGER, PASSWORD);
        String apex = loginToken(APEX_ADMIN, PASSWORD);
        JsonNode audit = freshAudit(admin, "P5 Appr");
        String auditId = audit.get("id").asText();
        String approvalsUri = "/api/v1/audits/" + auditId + "/approvals";

        // No POST/PUT surface exists — the approval row is written only by
        // the REVIEW -> APPROVED transition.
        Api post = postJson(approvalsUri, admin, "{\"role\":\"COMPANY_ADMIN\"}");
        assertEquals(405, post.status(), post.body().toString());
        Api put = putJson(approvalsUri, admin, "{\"role\":\"COMPANY_ADMIN\"}");
        assertEquals(405, put.status(), put.body().toString());

        // Empty while the audit has not been approved.
        driveToReview(admin, auditId);
        Api before = getJson(approvalsUri, admin);
        assertEquals(200, before.status(), before.body().toString());
        assertEquals(0, before.body().path("data").size());

        // Reads open to audits.read, closed cross-tenant.
        assertEquals(200, getJson(approvalsUri, manager).status());
        Api foreign = getJson(approvalsUri, apex);
        assertEquals(404, foreign.status(), foreign.body().toString());
        assertEquals("AUDIT_NOT_FOUND", foreign.errorCode());
    }
}
