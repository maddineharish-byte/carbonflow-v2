package com.carbonflow.service;

import com.carbonflow.dto.GovernanceRequests;
import com.carbonflow.model.AuditChecklistItem;
import com.carbonflow.model.CarbonAudit;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.repository.AuditRepository;
import com.carbonflow.repository.ChecklistRepository;
import com.carbonflow.repository.CommentRepository;
import com.carbonflow.repository.CorrectionRepository;
import com.carbonflow.repository.FindingRepository;
import com.carbonflow.repository.ReportingPeriodRepository;
import com.carbonflow.service.AuthException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Carbon audit lifecycle: create (seeding the canonical checklist), read,
 * draft updates, and — centrally — the governed state-machine transition.
 *
 * <p><b>Server decides, client proposes:</b> {@code POST /audits/:id/transition}
 * validates in this order, all server-side: target known to the ten-state
 * machine → legal from the current state → the caller holds the transition's
 * specific frozen-matrix permission → prerequisites (mandatory checklist,
 * unresolved high-severity findings, logged finding for corrections, reason
 * for rejection). Any failure aborts with no state change.
 *
 * <p><b>Permissions</b> map onto the frozen 44-code matrix only (API.md §2.6
 * endpoint union at the controller, per-transition code here): operational
 * forward/resume transitions → {@code audits.submit}; reviewer outcomes →
 * {@code audits.review}; approval → {@code audits.approve}; readiness/lock →
 * {@code audits.lock} (see {@link AuditStateMachine}).
 *
 * <p><b>Governed lock:</b> {@code LOCKED} rejects all governed mutation with
 * {@code 409 AUDIT_LOCKED} through {@link #requireUnlocked} — enforcement
 * lives in the service/repository layer, never in the UI. The lock writes an
 * {@code audit_lock_events} row (who/when + SHA-256 governance-state hash),
 * stamps {@code locked_at}, freezes the reporting period and leaves the
 * transition log comment. CarbonFlow describes this as a <b>governed audit
 * lock</b> — an integrity checksum, not cryptographic immutability, and
 * internal approval is never external certification (product positioning).
 */
@Service
public class AuditService {

    /** docs/AUDIT_WORKFLOW.md §3 — seeded for every audit on creation. */
    public static final List<String[]> CANONICAL_CHECKLIST = List.of(
            new String[] {"CHK-BND-01", "Organizational Boundary Confirmation"},
            new String[] {"CHK-FAC-02", "Facility Completeness"},
            new String[] {"CHK-DAT-03", "Activity Data Ingestion"},
            new String[] {"CHK-EVD-04", "Primary Evidence Reconciliation"},
            new String[] {"CHK-FAC-05", "Emission Factor Integrity"},
            new String[] {"CHK-GWP-06", "GWP Reference Consistency"},
            new String[] {"CHK-S2D-07", "Scope 2 Dual-Reporting Verification"},
            new String[] {"CHK-FIN-08", "Finding Resolution"});

    private final ScopeService scope;
    private final AuditRepository audits;
    private final ChecklistRepository checklist;
    private final FindingRepository findings;
    private final CommentRepository comments;
    private final CorrectionRepository corrections;
    private final ReportingPeriodRepository reportingPeriods;

    public AuditService(ScopeService scope,
                        AuditRepository audits,
                        ChecklistRepository checklist,
                        FindingRepository findings,
                        CommentRepository comments,
                        CorrectionRepository corrections,
                        ReportingPeriodRepository reportingPeriods) {
        this.scope = scope;
        this.audits = audits;
        this.checklist = checklist;
        this.findings = findings;
        this.comments = comments;
        this.corrections = corrections;
        this.reportingPeriods = reportingPeriods;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public List<CarbonAudit> list(String organizationId) {
        return audits.list(organizationId);
    }

    public CarbonAudit detail(String organizationId, String auditId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        ReportingPeriod period =
                scope.requireReportingPeriod(organizationId, audit.getReportingPeriodId());
        audit.setPeriod(period);
        audit.setChecklist(checklist.listByAudit(organizationId, auditId));
        audit.setFindings(findings.listByAudit(organizationId, auditId));
        audit.setComments(comments.listByAudit(organizationId, auditId));
        audit.setCorrections(corrections.listByAudit(organizationId, auditId));
        audit.setApprovals(audits.listApprovals(organizationId, auditId));
        audit.setLockEvent(audits.findLockEvent(organizationId, auditId).orElse(null));
        // Node enriches the LIST route with checklistSummary/openFindingsCount
        // (routes.ts:792); the detail view derives the same figures from the
        // rows it just loaded so both views answer the same keys.
        audit.setChecklistSummary(new CarbonAudit.ChecklistSummary(
                audit.getChecklist().size(),
                (int) audit.getChecklist().stream()
                        .filter(AuditChecklistItem::isSatisfied).count()));
        audit.setOpenFindingsCount((int) audit.getFindings().stream()
                .filter(f -> "OPEN".equals(f.getStatus())).count());
        return audit;
    }

    // ------------------------------------------------------------------
    // Create / draft update
    // ------------------------------------------------------------------

    @Transactional
    public CarbonAudit create(String organizationId, String userId,
                              GovernanceRequests.AuditCreateRequest request) {
        if (request == null || request.getReportingPeriodId() == null
                || request.getReportingPeriodId().isBlank()) {
            throw new AuthException("VALIDATION_ERROR", "reportingPeriodId is required.",
                    HttpStatus.BAD_REQUEST);
        }
        ReportingPeriod period =
                scope.requireReportingPeriod(organizationId, request.getReportingPeriodId());
        if (audits.periodHasAudit(organizationId, period.getId())) {
            throw new AuthException("AUDIT_ALREADY_EXISTS",
                    "An audit already exists for this reporting period.",
                    HttpStatus.CONFLICT);
        }

        CarbonAudit audit;
        try {
            audit = audits.insert(organizationId, period.getId(), userId,
                    trimToNull(request.getNotes()));
        } catch (DuplicateKeyException e) {
            // uq_audit_period racing insert — same governed outcome.
            throw new AuthException("AUDIT_ALREADY_EXISTS",
                    "An audit already exists for this reporting period.",
                    HttpStatus.CONFLICT);
        }

        for (String[] seed : CANONICAL_CHECKLIST) {
            checklist.insert(audit.getId(), seed[0], seed[1], true);
        }
        return audit;
    }

    @Transactional
    public CarbonAudit updateDraft(String organizationId, String auditId,
                                   GovernanceRequests.AuditUpdateRequest request) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        requireUnlocked(audit);
        if (!"DRAFT".equals(audit.getStatus())) {
            throw new AuthException("AUDIT_NOT_DRAFT",
                    "Only a draft audit can be updated.",
                    HttpStatus.CONFLICT);
        }
        if (request == null || request.getNotes() == null) {
            throw new AuthException("VALIDATION_ERROR", "notes is required.",
                    HttpStatus.BAD_REQUEST);
        }
        audits.updateNotes(organizationId, auditId, request.getNotes());
        return scope.requireAudit(organizationId, auditId);
    }

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    /**
     * The single transition path. Runs under a row lock
     * ({@code SELECT … FOR UPDATE}) so two concurrent transitions cannot both
     * pass validation; every side effect commits atomically or not at all.
     */
    @Transactional
    public CarbonAudit transition(String organizationId, String auditId,
                                  GovernanceRequests.TransitionRequest request, String userId) {
        if (request == null || request.getTargetState() == null
                || request.getTargetState().isBlank()) {
            throw new AuthException("VALIDATION_ERROR", "targetState is required.",
                    HttpStatus.BAD_REQUEST);
        }
        String target = request.getTargetState().trim();
        String reason = trimToNull(request.getReason());

        CarbonAudit audit = audits.findByIdForUpdate(organizationId, auditId)
                .orElseThrow(() -> ScopeService.notFound("Audit"));
        String current = audit.getStatus();

        // 1. The server decides legality — never the client.
        if (!AuditStateMachine.isAllowed(current, target)) {
            throw new AuthException("INVALID_TRANSITION",
                    AuditStateMachine.invalidTransitionMessage(current, target),
                    HttpStatus.BAD_REQUEST);
        }

        // 2. Per-transition permission from the frozen matrix.
        assertTransitionPermission(current, target);

        // 3. Prerequisites (governed gates — server-side, never frontend).
        assertPrerequisites(organizationId, auditId, current, target, reason);

        // 4. Side effects, transactional.
        String approvedBy = null;
        OffsetDateTime lockedAt = null;
        if ("APPROVED".equals(target)) {
            approvedBy = userId;
            audits.insertApproval(audit.getId(), userId, roleOf(),
                    approvalSignatureHash(audit.getId(), userId, roleOf()));
        }
        if ("LOCKED".equals(target)) {
            lockedAt = OffsetDateTime.now();
            String governanceHash = governanceStateHash(organizationId, auditId, audit);
            audits.insertLockEvent(audit.getId(), userId, governanceHash);
            reportingPeriods.updateStatus(organizationId, audit.getReportingPeriodId(),
                    "LOCKED");
        }

        audits.updateTransition(organizationId, auditId, target, approvedBy, lockedAt);
        comments.insert(organizationId, auditId, userId,
                transitionComment(current, target, reason));

        return audits.findById(organizationId, auditId)
                .orElseThrow(() -> ScopeService.notFound("Audit"));
    }

    /**
     * Frozen-matrix authority required for this specific transition; the
     * controller's {@code @PreAuthorize} only grants the §2.6 union of the
     * four audit permissions — the narrower per-transition code is checked
     * here (fail closed when no authentication is bound).
     */
    private void assertTransitionPermission(String current, String target) {
        String required = AuditStateMachine.requiredPermission(current, target);
        if (required == null) {
            throw new AuthException("INVALID_TRANSITION",
                    AuditStateMachine.invalidTransitionMessage(current, target),
                    HttpStatus.BAD_REQUEST);
        }
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        boolean granted = authentication != null
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                        .anyMatch(a -> ("PERMISSION_" + required).equals(a.getAuthority()));
        if (!granted) {
            throw new AuthException("FORBIDDEN",
                    "Your role cannot perform the '" + current + " -> " + target
                            + "' transition.",
                    HttpStatus.FORBIDDEN);
        }
    }

    private void assertPrerequisites(String organizationId, String auditId,
                                     String current, String target, String reason) {
        if (AuditStateMachine.requiresGovernedPrerequisites(target)) {
            List<String> unsatisfied =
                    checklist.unsatisfiedMandatoryCodes(organizationId, auditId);
            if (!unsatisfied.isEmpty()) {
                throw new AuthException("CHECKLIST_INCOMPLETE",
                        "Cannot transition to '" + target + "'. " + unsatisfied.size()
                                + " mandatory checklist item(s) are unsatisfied: "
                                + String.join(", ", unsatisfied) + ".",
                        HttpStatus.BAD_REQUEST);
            }
            List<String> unresolved =
                    findings.unresolvedHighSeverityTitles(organizationId, auditId);
            if (!unresolved.isEmpty()) {
                throw new AuthException("UNRESOLVED_FINDINGS",
                        "Cannot transition to '" + target + "'. " + unresolved.size()
                                + " high-severity finding(s) are unresolved: "
                                + String.join(", ", unresolved) + ".",
                        HttpStatus.BAD_REQUEST);
            }
        }
        if ("CORRECTION_REQUESTED".equals(target)
                && findings.activeFindingCount(organizationId, auditId) == 0) {
            throw new AuthException("NO_REVIEW_FINDING",
                    "Log a review finding before requesting corrections.",
                    HttpStatus.BAD_REQUEST);
        }
        if ("REJECTED".equals(target) && reason == null) {
            throw new AuthException("VALIDATION_ERROR",
                    "reason is required when rejecting an audit.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** Node's exact transition log text ({@code routes.ts} line 878). */
    static String transitionComment(String current, String target, String reason) {
        return "Transitioned status from [" + current + "] to [" + target + "]. "
                + (reason != null ? "Reason: " + reason : "");
    }

    /**
     * SHA-256 governance-state hash stored in {@code audit_lock_events
     * .inventory_hash} at lock time: a deterministic checksum over the audit
     * identity, target state, every checklist item and every finding as
     * frozen at the lock — verifiable by re-reading the locked record. Until
     * Phase 7 provides inventory snapshots this covers the audit's governed
     * state, not emissions totals; it is an integrity checksum, not a
     * cryptographic immutability claim (ADR-016).
     *
     * <p>Both child lists are read through their org-predicated mappers with
     * deterministic ORDER BY, so the same locked record always yields the
     * same hash.
     */
    String governanceStateHash(String organizationId, String auditId, CarbonAudit audit) {
        StringBuilder payload = new StringBuilder();
        payload.append(auditId).append('|')
                .append(audit.getReportingPeriodId()).append('|')
                .append("LOCKED").append("|checklist[");
        for (AuditChecklistItem item : checklist.listByAudit(organizationId, auditId)) {
            payload.append(item.getCode()).append('=').append(item.isSatisfied()).append(',');
        }
        payload.append("]|findings[");
        for (var finding : findings.listByAudit(organizationId, auditId)) {
            payload.append(finding.getId()).append('=')
                    .append(finding.getSeverity()).append('/')
                    .append(finding.getStatus()).append(',');
        }
        payload.append(']');
        return EvidenceStorageService.sha256Hex(
                payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 integrity hash of the approval act (canonical payload:
     * {@code auditId|approverId|role|REVIEW->APPROVED}). A reproducible
     * audit-trail checksum — not a digital signature, not certification.
     */
    static String approvalSignatureHash(String auditId, String approverId, String role) {
        return EvidenceStorageService.sha256Hex(
                (auditId + "|" + approverId + "|" + role + "|REVIEW->APPROVED")
                        .getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Checklist
    // ------------------------------------------------------------------

    @Transactional
    public AuditChecklistItem createChecklistItem(String organizationId, String auditId,
            GovernanceRequests.ChecklistCreateRequest request) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        requireUnlocked(audit);
        if (request == null || request.getCode() == null || request.getCode().isBlank()
                || request.getTitle() == null || request.getTitle().isBlank()) {
            throw new AuthException("VALIDATION_ERROR", "code and title are required.",
                    HttpStatus.BAD_REQUEST);
        }
        boolean mandatory = request.getIsMandatory() == null
                || request.getIsMandatory();
        try {
            return checklist.insert(auditId, request.getCode().trim(),
                    request.getTitle().trim(), mandatory);
        } catch (DuplicateKeyException e) {
            throw new AuthException("DUPLICATE_CHECKLIST_ITEM",
                    "Checklist item code already exists for this audit.",
                    HttpStatus.CONFLICT);
        }
    }

    @Transactional
    public AuditChecklistItem updateChecklistItem(String organizationId, String auditId,
            String itemId, GovernanceRequests.ChecklistUpdateRequest request) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        requireUnlocked(audit);
        itemId = ScopeService.requireUuid(itemId, "Checklist item");
        if (request == null || (request.getTitle() == null
                && request.getIsMandatory() == null && request.getNotes() == null)) {
            throw new AuthException("VALIDATION_ERROR",
                    "No updatable fields supplied.", HttpStatus.BAD_REQUEST);
        }
        checklist.findById(organizationId, auditId, itemId)
                .orElseThrow(() -> ScopeService.notFound("Checklist item"));
        checklist.updateStructure(organizationId, auditId, itemId,
                trimToNull(request.getTitle()), request.getIsMandatory(),
                trimToNull(request.getNotes()));
        return checklist.findById(organizationId, auditId, itemId)
                .orElseThrow(() -> ScopeService.notFound("Checklist item"));
    }

    @Transactional
    public AuditChecklistItem verifyChecklistItem(String organizationId, String auditId,
            String itemId, GovernanceRequests.ChecklistVerifyRequest request, String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        requireUnlocked(audit);
        itemId = ScopeService.requireUuid(itemId, "Checklist item");
        checklist.findById(organizationId, auditId, itemId)
                .orElseThrow(() -> ScopeService.notFound("Checklist item"));
        if (request == null || request.getIsSatisfied() == null) {
            // Node coerced a missing value to false; Java rejects the
            // ambiguous request instead (ADR-016).
            throw new AuthException("VALIDATION_ERROR", "isSatisfied is required.",
                    HttpStatus.BAD_REQUEST);
        }
        checklist.verify(organizationId, auditId, itemId, request.getIsSatisfied(),
                userId, trimToNull(request.getNotes()));
        return checklist.findById(organizationId, auditId, itemId)
                .orElseThrow(() -> ScopeService.notFound("Checklist item"));
    }

    // ------------------------------------------------------------------
    // Shared guards
    // ------------------------------------------------------------------

    /**
     * Locked-state enforcement (module 12): once {@code LOCKED}, every
     * governed mutation — audit metadata, checklist, findings, comments,
     * corrections, approvals, evidence links/versions — is rejected with
     * {@code 409 AUDIT_LOCKED}. Reads remain available.
     */
    public void requireUnlocked(CarbonAudit audit) {
        if (audit != null && "LOCKED".equals(audit.getStatus())) {
            throw new AuthException("AUDIT_LOCKED",
                    "Audit is locked and cannot be modified.",
                    HttpStatus.CONFLICT);
        }
    }

    private String roleOf() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities() != null) {
            for (var authority : authentication.getAuthorities()) {
                String value = authority.getAuthority();
                if (value != null
                        && value.startsWith(com.carbonflow.security.Authorities.ROLE_PREFIX)) {
                    return value.substring(
                            com.carbonflow.security.Authorities.ROLE_PREFIX.length());
                }
            }
        }
        return "UNKNOWN";
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
