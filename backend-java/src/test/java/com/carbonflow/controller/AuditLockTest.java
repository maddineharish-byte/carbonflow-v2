package com.carbonflow.controller;

import com.carbonflow.repository.SeedIds;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 modules 12 + 13: the governed audit lock.
 *
 * <p>{@code AUDIT_READY → LOCKED} persists the lock event (who/when +
 * SHA-256 governance-state hash), freezes the reporting period and makes
 * LOCKED terminal. Afterwards every governed mutation — audit metadata,
 * checklist, findings, comments, corrections, evidence links/versions of
 * audit-linked records — is rejected with {@code 409 AUDIT_LOCKED} while all
 * reads stay open. Unauthenticated/unauthorized locking is refused and
 * persists nothing. The lock is a <b>governed integrity lock</b>, not a
 * cryptographic immutability claim.
 */
class AuditLockTest extends AuditTestBase {

    private static final String ACME_ADMIN = "admin@acmeglobal.com";
    private static final String ACME_MANAGER = "manager@acmeglobal.com";
    private static final String ACME_AUDITOR = "auditor@ey-assurance.com";

    private static final byte[] VALID_PDF =
            ("%PDF-1.4\nlock fixture\n%%EOF\n").getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII);

    @Test
    void lockPersistsEventHashPeriodFreezeAndRefusesFurtherTransitions()
            throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);

        JsonNode period = createPeriod(admin, "P5 Lk " + suffix());
        JsonNode audit = createAudit(admin, period.get("id").asText());
        String auditId = audit.get("id").asText();
        driveToReview(admin, auditId);
        verifyAllChecklist(auditor, auditId);
        assertEquals(200, transition(admin, auditId, "APPROVED", null).status());
        assertEquals(200, transition(admin, auditId, "AUDIT_READY", null).status());
        assertEquals(200, transition(admin, auditId, "LOCKED", null).status());

        // Lock event row: who, when, governance-state checksum.
        JsonNode event = readLockEvent(auditId);
        assertEquals(SeedIds.USER_ACME_ADMIN, event.path("lockedBy").asText());
        assertTrue(event.path("inventoryHash").asText().matches("[0-9a-f]{64}"),
                "governance-state SHA-256 hash stored");
        assertFalse(event.path("lockedAt").isNull(), "lock timestamp stored");

        // Audit row stamped.
        String status = jdbc.queryForObject(
                "SELECT status FROM carbon_audits WHERE id = ?::uuid",
                String.class, auditId);
        assertEquals("LOCKED", status);
        Boolean lockedAt = jdbc.queryForObject(
                "SELECT locked_at IS NOT NULL FROM carbon_audits WHERE id = ?::uuid",
                Boolean.class, auditId);
        assertTrue(lockedAt, "locked_at stamped at lock time");

        // Reporting period frozen.
        String periodStatus = jdbc.queryForObject(
                "SELECT status FROM reporting_periods WHERE id = ?::uuid",
                String.class, period.get("id").asText());
        assertEquals("LOCKED", periodStatus);

        // Terminal: the machine answers every refusal identically.
        Api attempt = transition(admin, auditId, "SUBMITTED", null);
        assertEquals(400, attempt.status(), attempt.body().toString());
        assertEquals("INVALID_TRANSITION", attempt.errorCode());
        assertEquals("Cannot transition audit from 'LOCKED' to 'SUBMITTED'. "
                + "Valid targets: .", attempt.errorMessage());
        assertEquals("LOCKED", getJson("/api/v1/audits/" + auditId, admin)
                .body().path("data").path("status").asText());
    }

    private JsonNode readLockEvent(String auditId) throws Exception {
        String json = jdbc.queryForObject(
                "SELECT json_build_object("
                        + "'lockedBy', l.locked_by, "
                        + "'inventoryHash', l.inventory_hash, "
                        + "'lockedAt', l.locked_at)::text "
                        + "FROM audit_lock_events l WHERE l.audit_id = ?::uuid",
                String.class, auditId);
        return objectMapper().readTree(json);
    }

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @Test
    void lockRequiresAuditsLockPermission() throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);
        String manager = loginToken(ACME_MANAGER, PASSWORD);

        JsonNode period = createPeriod(admin, "P5 LkPerm " + suffix());
        JsonNode audit = createAudit(admin, period.get("id").asText());
        String auditId = audit.get("id").asText();
        driveToReview(admin, auditId);
        verifyAllChecklist(auditor, auditId);
        assertEquals(200, transition(admin, auditId, "APPROVED", null).status());
        assertEquals(200, transition(admin, auditId, "AUDIT_READY", null).status());

        // ASSURANCE_PROVIDER passes the endpoint union (audits.review) but
        // lacks audits.lock — refused server-side, state untouched.
        Api auditorLock = transition(auditor, auditId, "LOCKED", null);
        assertEquals(403, auditorLock.status(), auditorLock.body().toString());
        assertEquals("FORBIDDEN", auditorLock.errorCode());
        assertEquals("Your role cannot perform the 'AUDIT_READY -> LOCKED' "
                + "transition.", auditorLock.errorMessage());
        assertEquals("AUDIT_READY", getJson("/api/v1/audits/" + auditId, admin)
                .body().path("data").path("status").asText(),
                "a refused lock must not mutate state");
        assertEquals(0, lockEventCount(auditId),
                "a refused lock must not write a lock event");

        // SUSTAINABILITY_MANAGER holds audits.lock in the frozen matrix.
        assertEquals(200, transition(manager, auditId, "LOCKED", null).status());
        assertEquals(1, lockEventCount(auditId));
        JsonNode event = readLockEvent(auditId);
        assertEquals(SeedIds.USER_ACME_MANAGER, event.path("lockedBy").asText(),
                "lock event records the actual locking identity");
    }

    private int lockEventCount(String auditId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_lock_events WHERE audit_id = ?::uuid",
                Integer.class, auditId);
        return count == null ? 0 : count;
    }

    @Test
    void lockedAuditRejectsEveryGovernedMutationButKeepsReadsOpen()
            throws Exception {
        String admin = loginToken(ACME_ADMIN, PASSWORD);
        String auditor = loginToken(ACME_AUDITOR, PASSWORD);

        // Evidence linked before the lock freezes with it; standalone
        // evidence stays mutable (freeze is linkage-scoped).
        JsonNode period = createPeriod(admin, "P5 LkMut " + suffix());
        JsonNode facility = createFacility(admin, "P5 LkMut Plant " + suffix(),
                "LM" + suffix());
        JsonNode audit = createAudit(admin, period.get("id").asText());
        String auditId = audit.get("id").asText();
        String activityId = insertActivityData(SeedIds.ORG_ACME,
                period.get("id").asText(), facility.get("id").asText());
        Api linkedEvidence = uploadEvidence(admin, "pre-lock.pdf");
        String linkedId = linkedEvidence.body().path("data").path("id").asText();
        assertEquals(201, postJson("/api/v1/evidence/" + linkedId + "/link", admin,
                "{\"entityType\":\"AUDIT\",\"entityId\":\"" + auditId + "\"}").status());
        Api standaloneEvidence = uploadEvidence(admin, "standalone.pdf");
        String standaloneId =
                standaloneEvidence.body().path("data").path("id").asText();

        driveToReview(admin, auditId);
        JsonNode comment = postJson("/api/v1/audits/" + auditId + "/comments",
                        auditor, "{\"commentText\":\"To be frozen\"}").body()
                .path("data");
        postJson("/api/v1/audits/" + auditId + "/findings", auditor,
                "{\"title\":\"Frozen finding\",\"description\":\"Medium note\"}");
        JsonNode correction = postJson("/api/v1/audits/" + auditId + "/corrections",
                        auditor,
                        "{\"activityDataId\":\"" + activityId + "\","
                                + "\"reason\":\"Frozen correction\"}").body()
                .path("data");
        verifyAllChecklist(auditor, auditId);
        assertEquals(200, transition(admin, auditId, "APPROVED", null).status());
        assertEquals(200, transition(admin, auditId, "AUDIT_READY", null).status());
        assertEquals(200, transition(admin, auditId, "LOCKED", null).status());

        JsonNode locked = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data");
        String itemId = locked.path("checklist").get(0).path("id").asText();
        String findingId = locked.path("findings").get(0).path("id").asText();

        // a) Audit metadata.
        Api auditEdit = putJson("/api/v1/audits/" + auditId, admin,
                "{\"notes\":\"post-lock edit\"}");
        assertEquals(409, auditEdit.status(), auditEdit.body().toString());
        assertEquals("AUDIT_LOCKED", auditEdit.errorCode());

        // b) Checklist: create, structural update, verify.
        Api chkCreate = postJson("/api/v1/audits/" + auditId + "/checklist", auditor,
                "{\"code\":\"CHK-LATE\",\"title\":\"Too late\"}");
        assertEquals(409, chkCreate.status(), chkCreate.body().toString());
        assertEquals("AUDIT_LOCKED", chkCreate.errorCode());
        Api chkUpdate = putJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId, auditor,
                "{\"title\":\"post-lock rename\"}");
        assertEquals(409, chkUpdate.status(), chkUpdate.body().toString());
        assertEquals("AUDIT_LOCKED", chkUpdate.errorCode());
        Api chkVerify = postJson(
                "/api/v1/audits/" + auditId + "/checklist/" + itemId + "/verify",
                auditor, "{\"isSatisfied\":false}");
        assertEquals(409, chkVerify.status(), chkVerify.body().toString());
        assertEquals("AUDIT_LOCKED", chkVerify.errorCode());

        // c) Findings: create, update, resolve.
        Api fndCreate = postJson("/api/v1/audits/" + auditId + "/findings", auditor,
                "{\"title\":\"post-lock\",\"description\":\"post-lock\"}");
        assertEquals(409, fndCreate.status(), fndCreate.body().toString());
        assertEquals("AUDIT_LOCKED", fndCreate.errorCode());
        Api fndUpdate = putJson(
                "/api/v1/audits/" + auditId + "/findings/" + findingId, auditor,
                "{\"severity\":\"CRITICAL\"}");
        assertEquals(409, fndUpdate.status(), fndUpdate.body().toString());
        Api fndResolve = postJson(
                "/api/v1/audits/" + auditId + "/findings/" + findingId + "/resolve",
                auditor, "{}");
        assertEquals(409, fndResolve.status(), fndResolve.body().toString());
        assertEquals("AUDIT_LOCKED", fndResolve.errorCode());

        // d) Comments: create, edit, delete (pre-lock comment).
        Api cmtCreate = postJson("/api/v1/audits/" + auditId + "/comments", auditor,
                "{\"commentText\":\"post-lock\"}");
        assertEquals(409, cmtCreate.status(), cmtCreate.body().toString());
        assertEquals("AUDIT_LOCKED", cmtCreate.errorCode());
        Api cmtEdit = putJson("/api/v1/audits/" + auditId + "/comments/"
                        + comment.path("id").asText(), auditor,
                "{\"commentText\":\"rewritten\"}");
        assertEquals(409, cmtEdit.status(), cmtEdit.body().toString());
        Api cmtDelete = deleteJson("/api/v1/audits/" + auditId + "/comments/"
                + comment.path("id").asText(), auditor);
        assertEquals(409, cmtDelete.status(), cmtDelete.body().toString());

        // e) Corrections: create and resolve-flag update.
        Api corCreate = postJson("/api/v1/audits/" + auditId + "/corrections",
                auditor,
                "{\"activityDataId\":\"" + activityId + "\",\"reason\":\"post-lock\"}");
        assertEquals(409, corCreate.status(), corCreate.body().toString());
        assertEquals("AUDIT_LOCKED", corCreate.errorCode());
        Api corUpdate = putJson(
                "/api/v1/audits/" + auditId + "/corrections/"
                        + correction.path("id").asText(), auditor,
                "{\"isResolved\":true}");
        assertEquals(409, corUpdate.status(), corUpdate.body().toString());

        // f) Evidence: linking to a locked audit and versioning of
        //    audit-linked evidence are frozen; standalone stays usable.
        Api lateLink = postJson("/api/v1/evidence/" + standaloneId + "/link", admin,
                "{\"entityType\":\"AUDIT\",\"entityId\":\"" + auditId + "\"}");
        assertEquals(409, lateLink.status(), lateLink.body().toString());
        assertEquals("AUDIT_LOCKED", lateLink.errorCode());
        Api frozenVersion = postMultipart(
                "/api/v1/evidence/" + linkedId + "/versions", admin, null,
                "post-lock.pdf", "application/pdf", VALID_PDF);
        assertEquals(409, frozenVersion.status(), frozenVersion.body().toString());
        assertEquals("AUDIT_LOCKED", frozenVersion.errorCode());
        Api freeVersion = postMultipart(
                "/api/v1/evidence/" + standaloneId + "/versions", admin, null,
                "still-free.pdf", "application/pdf", VALID_PDF);
        assertEquals(201, freeVersion.status(),
                freeVersion.body().toString()
                        + " — evidence not linked to the locked audit remains mutable");

        // g) No approval endpoint materializes after the lock.
        Api approvalPost = postJson(
                "/api/v1/audits/" + auditId + "/approvals", admin,
                "{\"role\":\"COMPANY_ADMIN\"}");
        assertEquals(405, approvalPost.status(), approvalPost.body().toString());

        // Reads all stay open (governance record must be reviewable).
        assertEquals(200, getJson("/api/v1/audits/" + auditId, admin).status());
        assertEquals(200, getJson("/api/v1/audits/" + auditId + "/findings",
                auditor).status());
        assertEquals(200, getJson("/api/v1/audits/" + auditId + "/comments",
                auditor).status());
        assertEquals(200, getJson("/api/v1/audits/" + auditId + "/corrections",
                auditor).status());
        assertEquals(200, getJson("/api/v1/audits/" + auditId + "/approvals",
                auditor).status());

        // Nothing above changed the frozen rows.
        JsonNode after = getJson("/api/v1/audits/" + auditId, admin).body()
                .path("data");
        assertEquals("LOCKED", after.path("status").asText());
        assertTrue(after.path("checklist").get(0).path("isSatisfied").asBoolean(),
                "checklist verification did not flip");
        assertEquals("OPEN", after.path("findings").get(0).path("status").asText(),
                "finding status unchanged");
        String frozenCommentId = comment.path("id").asText();
        boolean commentIntact = false;
        for (JsonNode row : after.path("comments")) {
            if (frozenCommentId.equals(row.path("id").asText())) {
                commentIntact = true;
                assertEquals("To be frozen", row.path("commentText").asText(),
                        "comment text unchanged");
            }
        }
        assertTrue(commentIntact, "pre-lock comment still present");
        assertFalse(after.path("corrections").get(0).path("isResolved").asBoolean(),
                "correction flag unchanged");
        assertTrue(after.path("lockEvent").path("inventoryHash").asText()
                .matches("[0-9a-f]{64}"));
    }

    private Api uploadEvidence(String token, String fileName) throws Exception {
        Api api = postMultipart("/api/v1/evidence/upload", token, null, fileName,
                "application/pdf", VALID_PDF);
        assertEquals(201, api.status(), api.body().toString());
        return api;
    }
}
