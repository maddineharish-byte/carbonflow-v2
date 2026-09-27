package com.carbonflow.controller;

import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.GovernanceRequests;
import com.carbonflow.model.CorrectionRequest;
import com.carbonflow.model.ReviewComment;
import com.carbonflow.model.ReviewFinding;
import com.carbonflow.config.TenantContext;
import com.carbonflow.service.ReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Review desk: findings, comments, correction requests and approval reads,
 * all nested under an audit id (API.md §2.6).
 *
 * <p>Node left comment creation ungated and had no endpoints at all for
 * corrections or approvals (Phase 1 findings). Java gates every write on
 * {@code audits.review} per API.md §2.6 and resolves the parent audit for
 * tenancy before touching any child row — a foreign audit, finding, comment
 * or correction id answers the uniform 404 and changes nothing.
 *
 * <p>Approvals deliberately expose no POST: the approval record is written
 * only by the governed REVIEW → APPROVED transition
 * ({@code audits.approve} + prerequisites).
 */
@RestController
@RequestMapping("/api/v1/audits/{auditId}")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    private static String orgId() {
        return TenantContext.get().getOrganizationId();
    }

    private static String userId() {
        return TenantContext.get().getUserId();
    }

    // ------------------------------------------------------------------
    // Findings
    // ------------------------------------------------------------------

    @GetMapping("/findings")
    @PreAuthorize("hasAuthority('PERMISSION_audits.read')")
    public ResponseEntity<ApiResponse<List<ReviewFinding>>> listFindings(
            @PathVariable String auditId) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.listFindings(orgId(), auditId)));
    }

    @PostMapping("/findings")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<ReviewFinding>> createFinding(
            @PathVariable String auditId,
            @RequestBody GovernanceRequests.FindingCreateRequest request) {
        ReviewFinding finding =
                reviewService.createFinding(orgId(), auditId, request, userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(finding, "Review finding recorded."));
    }

    @PutMapping("/findings/{findingId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<ReviewFinding>> updateFinding(
            @PathVariable String auditId,
            @PathVariable String findingId,
            @RequestBody GovernanceRequests.FindingUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.updateFinding(orgId(), auditId, findingId, request,
                        userId()),
                "Review finding updated."));
    }

    @PostMapping("/findings/{findingId}/resolve")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<ReviewFinding>> resolveFinding(
            @PathVariable String auditId,
            @PathVariable String findingId) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.resolveFinding(orgId(), auditId, findingId, userId()),
                "Finding marked as resolved."));
    }

    // ------------------------------------------------------------------
    // Comments
    // ------------------------------------------------------------------

    @GetMapping("/comments")
    @PreAuthorize("hasAuthority('PERMISSION_audits.read')")
    public ResponseEntity<ApiResponse<List<ReviewComment>>> listComments(
            @PathVariable String auditId) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.listComments(orgId(), auditId)));
    }

    @PostMapping("/comments")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<ReviewComment>> createComment(
            @PathVariable String auditId,
            @RequestBody GovernanceRequests.CommentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                reviewService.createComment(orgId(), auditId, request, userId()),
                "Comment added."));
    }

    @PutMapping("/comments/{commentId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<ReviewComment>> updateComment(
            @PathVariable String auditId,
            @PathVariable String commentId,
            @RequestBody GovernanceRequests.CommentUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.updateComment(orgId(), auditId, commentId, request,
                        userId()),
                "Comment updated."));
    }

    @DeleteMapping("/comments/{commentId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<ReviewComment>> deleteComment(
            @PathVariable String auditId,
            @PathVariable String commentId) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.deleteComment(orgId(), auditId, commentId, userId()),
                "Comment removed."));
    }

    // ------------------------------------------------------------------
    // Correction requests
    // ------------------------------------------------------------------

    @GetMapping("/corrections")
    @PreAuthorize("hasAuthority('PERMISSION_audits.read')")
    public ResponseEntity<ApiResponse<List<CorrectionRequest>>> listCorrections(
            @PathVariable String auditId) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.listCorrections(orgId(), auditId)));
    }

    @PostMapping("/corrections")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<CorrectionRequest>> createCorrection(
            @PathVariable String auditId,
            @RequestBody GovernanceRequests.CorrectionCreateRequest request) {
        CorrectionRequest correction =
                reviewService.createCorrection(orgId(), auditId, request, userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(correction, "Correction request created."));
    }

    @PutMapping("/corrections/{correctionId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<CorrectionRequest>> updateCorrection(
            @PathVariable String auditId,
            @PathVariable String correctionId,
            @RequestBody GovernanceRequests.CorrectionUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.updateCorrection(orgId(), auditId, correctionId, request),
                "Correction request updated."));
    }

    // ------------------------------------------------------------------
    // Approvals (read-only)
    // ------------------------------------------------------------------

    @GetMapping("/approvals")
    @PreAuthorize("hasAuthority('PERMISSION_audits.read')")
    public ResponseEntity<ApiResponse<List<?>>> listApprovals(
            @PathVariable String auditId) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.listApprovals(orgId(), auditId)));
    }
}
