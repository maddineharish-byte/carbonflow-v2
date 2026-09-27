package com.carbonflow.service;

import com.carbonflow.dto.GovernanceRequests;
import com.carbonflow.model.CarbonAudit;
import com.carbonflow.model.CorrectionRequest;
import com.carbonflow.model.ReviewComment;
import com.carbonflow.model.ReviewFinding;
import com.carbonflow.repository.AuditRepository;
import com.carbonflow.repository.CommentRepository;
import com.carbonflow.repository.CorrectionRepository;
import com.carbonflow.repository.FindingRepository;
import com.carbonflow.service.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Review-desk sub-resources of an audit: findings, comments, correction
 * requests and approval reads.
 *
 * <p><b>Tenancy:</b> every method starts with
 * {@code ScopeService.requireAudit} — child ids are never trusted without
 * resolving the parent audit under the caller's organization, and each child
 * statement re-joins the audit predicate (IDOR defense in depth).
 *
 * <p><b>Authorization</b> (frozen matrix, endpoint-level in the
 * controllers): reads → {@code audits.read}; writes → {@code audits.review}.
 * Node left comment creation ungated (Phase 1 defect); API.md §2.6 specifies
 * {@code audits.review} and Java enforces it — documented deviation.
 *
 * <p><b>Governed lock:</b> all writes call
 * {@code AuditService.requireUnlocked} first ({@code 409 AUDIT_LOCKED}).
 *
 * <p><b>Greenfield governance rules</b> (no Node counterpart exists —
 * ADR-016): comments are author-editable/author-deletable only; correction
 * requests can only be raised while the audit is in the correction window
 * (REVIEW or CORRECTION_REQUESTED) and always name an org-owned activity
 * record. Approvals have <i>no</i> create endpoint at all — they are written
 * exclusively by the REVIEW → APPROVED transition.
 */
@Service
public class ReviewService {

    private static final Set<String> SEVERITIES =
            Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> FINDING_STATUSES =
            Set.of("OPEN", "IN_REVIEW", "RESOLVED", "DISMISSED");

    private final ScopeService scope;
    private final AuditService auditService;
    private final FindingRepository findings;
    private final CommentRepository comments;
    private final CorrectionRepository corrections;
    private final AuditRepository audits;

    public ReviewService(ScopeService scope, AuditService auditService,
                         FindingRepository findings, CommentRepository comments,
                         CorrectionRepository corrections, AuditRepository audits) {
        this.scope = scope;
        this.auditService = auditService;
        this.findings = findings;
        this.comments = comments;
        this.corrections = corrections;
        this.audits = audits;
    }

    // ------------------------------------------------------------------
    // Review findings
    // ------------------------------------------------------------------

    public List<ReviewFinding> listFindings(String organizationId, String auditId) {
        scope.requireAudit(organizationId, auditId);
        return findings.listByAudit(organizationId, auditId);
    }

    @Transactional
    public ReviewFinding createFinding(String organizationId, String auditId,
                                       GovernanceRequests.FindingCreateRequest request,
                                       String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);

        if (request == null || isBlank(request.getTitle())
                || isBlank(request.getDescription())) {
            // Node's exact message (routes.ts line 906).
            throw new AuthException("VALIDATION_ERROR", "Title and description required.",
                    HttpStatus.BAD_REQUEST);
        }
        String title = request.getTitle().trim();
        String description = request.getDescription().trim();
        if (title.length() > 255) {
            throw new AuthException("VALIDATION_ERROR",
                    "title must be at most 255 characters.", HttpStatus.BAD_REQUEST);
        }

        String severity = isBlank(request.getSeverity()) ? "MEDIUM"
                : request.getSeverity().trim().toUpperCase();
        if (!SEVERITIES.contains(severity)) {
            // Schema CHECK — validated here so the client gets 400, not a 503.
            throw new AuthException("VALIDATION_ERROR",
                    "severity must be one of LOW, MEDIUM, HIGH, CRITICAL.",
                    HttpStatus.BAD_REQUEST);
        }
        String activityDataId = null;
        if (!isBlank(request.getActivityDataId())) {
            activityDataId =
                    scope.requireActivityData(organizationId, request.getActivityDataId());
        }

        return findings.insert(auditId, activityDataId, severity, title, description,
                userId);
    }

    @Transactional
    public ReviewFinding updateFinding(String organizationId, String auditId,
                                       String findingId,
                                       GovernanceRequests.FindingUpdateRequest request,
                                       String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);
        findingId = ScopeService.requireUuid(findingId, "Finding");

        if (request == null || (isBlank(request.getTitle())
                && isBlank(request.getDescription()) && isBlank(request.getSeverity())
                && isBlank(request.getStatus()))) {
            throw new AuthException("VALIDATION_ERROR", "No updatable fields supplied.",
                    HttpStatus.BAD_REQUEST);
        }
        findings.findById(organizationId, auditId, findingId)
                .orElseThrow(() -> ScopeService.notFound("Finding"));

        String severity = null;
        if (!isBlank(request.getSeverity())) {
            severity = request.getSeverity().trim().toUpperCase();
            if (!SEVERITIES.contains(severity)) {
                throw new AuthException("VALIDATION_ERROR",
                        "severity must be one of LOW, MEDIUM, HIGH, CRITICAL.",
                        HttpStatus.BAD_REQUEST);
            }
        }
        String status = null;
        if (!isBlank(request.getStatus())) {
            status = request.getStatus().trim().toUpperCase();
            if (!FINDING_STATUSES.contains(status)) {
                throw new AuthException("VALIDATION_ERROR",
                        "status must be one of OPEN, IN_REVIEW, RESOLVED, DISMISSED.",
                        HttpStatus.BAD_REQUEST);
            }
        }
        String resolvedBy = "RESOLVED".equals(status) ? userId : null;
        String title = isBlank(request.getTitle()) ? null : request.getTitle().trim();
        if (title != null && title.length() > 255) {
            throw new AuthException("VALIDATION_ERROR",
                    "title must be at most 255 characters.", HttpStatus.BAD_REQUEST);
        }

        findings.update(organizationId, auditId, findingId, severity, title,
                isBlank(request.getDescription()) ? null : request.getDescription().trim(),
                status, resolvedBy);
        return findings.findById(organizationId, auditId, findingId)
                .orElseThrow(() -> ScopeService.notFound("Finding"));
    }

    @Transactional
    public ReviewFinding resolveFinding(String organizationId, String auditId,
                                        String findingId, String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);
        findingId = ScopeService.requireUuid(findingId, "Finding");
        findings.findById(organizationId, auditId, findingId)
                .orElseThrow(() -> ScopeService.notFound("Finding"));
        findings.resolve(organizationId, auditId, findingId, userId);
        return findings.findById(organizationId, auditId, findingId)
                .orElseThrow(() -> ScopeService.notFound("Finding"));
    }

    // ------------------------------------------------------------------
    // Review comments
    // ------------------------------------------------------------------

    public List<ReviewComment> listComments(String organizationId, String auditId) {
        scope.requireAudit(organizationId, auditId);
        return comments.listByAudit(organizationId, auditId);
    }

    @Transactional
    public ReviewComment createComment(String organizationId, String auditId,
                                       GovernanceRequests.CommentCreateRequest request,
                                       String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);
        if (request == null || isBlank(request.getCommentText())) {
            // Node's exact message (routes.ts line 942).
            throw new AuthException("EMPTY_COMMENT", "Comment text cannot be blank.",
                    HttpStatus.BAD_REQUEST);
        }
        return comments.insert(organizationId, auditId, userId,
                request.getCommentText().trim());
    }

    @Transactional
    public ReviewComment updateComment(String organizationId, String auditId,
                                       String commentId,
                                       GovernanceRequests.CommentUpdateRequest request,
                                       String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);
        commentId = ScopeService.requireUuid(commentId, "Comment");
        ReviewComment comment = comments.findById(organizationId, auditId, commentId)
                .orElseThrow(() -> ScopeService.notFound("Comment"));
        assertCommentAuthor(comment, userId);
        if (request == null || isBlank(request.getCommentText())) {
            throw new AuthException("EMPTY_COMMENT", "Comment text cannot be blank.",
                    HttpStatus.BAD_REQUEST);
        }
        comments.updateText(organizationId, auditId, commentId,
                request.getCommentText().trim());
        return comments.findById(organizationId, auditId, commentId)
                .orElseThrow(() -> ScopeService.notFound("Comment"));
    }

    @Transactional
    public ReviewComment deleteComment(String organizationId, String auditId,
                                       String commentId, String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);
        commentId = ScopeService.requireUuid(commentId, "Comment");
        ReviewComment comment = comments.findById(organizationId, auditId, commentId)
                .orElseThrow(() -> ScopeService.notFound("Comment"));
        assertCommentAuthor(comment, userId);
        comments.delete(organizationId, auditId, commentId);
        return comment;
    }

    /**
     * Author-only mutation — even a reviewer may not rewrite another user's
     * words inside the audit record (governance-history integrity).
     */
    private void assertCommentAuthor(ReviewComment comment, String userId) {
        if (comment.getUserId() == null || !comment.getUserId().equals(userId)) {
            throw new AuthException("NOT_COMMENT_AUTHOR",
                    "Only the comment author can modify this comment.",
                    HttpStatus.FORBIDDEN);
        }
    }

    // ------------------------------------------------------------------
    // Correction requests
    // ------------------------------------------------------------------

    public List<CorrectionRequest> listCorrections(String organizationId, String auditId) {
        scope.requireAudit(organizationId, auditId);
        return corrections.listByAudit(organizationId, auditId);
    }

    @Transactional
    public CorrectionRequest createCorrection(String organizationId, String auditId,
                                              GovernanceRequests.CorrectionCreateRequest request,
                                              String userId) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);

        String status = audit.getStatus();
        if (!"REVIEW".equals(status) && !"CORRECTION_REQUESTED".equals(status)) {
            throw new AuthException("AUDIT_NOT_IN_CORRECTION_WINDOW",
                    "Correction requests can only be raised while the audit is in "
                            + "REVIEW or CORRECTION_REQUESTED.",
                    HttpStatus.CONFLICT);
        }
        if (request == null || isBlank(request.getActivityDataId())) {
            throw new AuthException("VALIDATION_ERROR", "activityDataId is required.",
                    HttpStatus.BAD_REQUEST);
        }
        if (isBlank(request.getReason())) {
            throw new AuthException("VALIDATION_ERROR", "reason is required.",
                    HttpStatus.BAD_REQUEST);
        }
        // Both sides tenant-resolved: the activity record belongs to the org.
        String activityDataId =
                scope.requireActivityData(organizationId, request.getActivityDataId());

        return corrections.insert(auditId, activityDataId, request.getReason().trim(),
                userId);
    }

    @Transactional
    public CorrectionRequest updateCorrection(String organizationId, String auditId,
                                              String correctionId,
                                              GovernanceRequests.CorrectionUpdateRequest request) {
        CarbonAudit audit = scope.requireAudit(organizationId, auditId);
        auditService.requireUnlocked(audit);
        correctionId = ScopeService.requireUuid(correctionId, "Correction request");
        if (request == null || request.getIsResolved() == null) {
            throw new AuthException("VALIDATION_ERROR", "isResolved is required.",
                    HttpStatus.BAD_REQUEST);
        }
        corrections.findById(organizationId, auditId, correctionId)
                .orElseThrow(() -> ScopeService.notFound("Correction request"));
        corrections.updateResolved(organizationId, auditId, correctionId,
                request.getIsResolved());
        return corrections.findById(organizationId, auditId, correctionId)
                .orElseThrow(() -> ScopeService.notFound("Correction request"));
    }

    // ------------------------------------------------------------------
    // Approvals (read-only — creation belongs to the transition)
    // ------------------------------------------------------------------

    public List<?> listApprovals(String organizationId, String auditId) {
        scope.requireAudit(organizationId, auditId);
        return audits.listApprovals(organizationId, auditId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
