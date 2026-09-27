package com.carbonflow.controller;

import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.GovernanceRequests;
import com.carbonflow.model.AuditChecklistItem;
import com.carbonflow.model.CarbonAudit;
import com.carbonflow.service.AuditService;
import com.carbonflow.config.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Carbon audit resource + governed state-machine endpoint (API.md §2.6).
 *
 * <p>Replaces the legacy {@code /audit-rooms} controller and its six-state
 * prototype enum — the canonical ten-state machine
 * ({@link com.carbonflow.service.AuditStateMachine}) is the only audit-state
 * model in this backend (Phase 5 module 2).
 *
 * <p>Authorization is server-side at two layers: the controller's
 * {@code @PreAuthorize} enforces the §2.6 permission(s) of each route, and
 * the service enforces the <i>per-transition</i> permission plus legality,
 * prerequisites and locked-state rules. Frontend visibility is not security.
 */
@RestController
@RequestMapping("/api/v1/audits")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    private static String orgId() {
        return TenantContext.get().getOrganizationId();
    }

    private static String userId() {
        return TenantContext.get().getUserId();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_audits.read')")
    public ResponseEntity<ApiResponse<List<CarbonAudit>>> listAudits() {
        return ResponseEntity.ok(ApiResponse.ok(auditService.list(orgId())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_audits.create')")
    public ResponseEntity<ApiResponse<CarbonAudit>> createAudit(
            @RequestBody GovernanceRequests.AuditCreateRequest request) {
        CarbonAudit created = auditService.create(orgId(), userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Audit initiated."));
    }

    @GetMapping("/{auditId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.read')")
    public ResponseEntity<ApiResponse<CarbonAudit>> getAudit(
            @PathVariable String auditId) {
        return ResponseEntity.ok(ApiResponse.ok(auditService.detail(orgId(), auditId)));
    }

    @PutMapping("/{auditId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.create')")
    public ResponseEntity<ApiResponse<CarbonAudit>> updateAudit(
            @PathVariable String auditId,
            @RequestBody GovernanceRequests.AuditUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                auditService.updateDraft(orgId(), auditId, request),
                "Audit updated."));
    }

    /**
     * The client proposes a target state; the backend decides. Endpoint
     * gate = API.md §2.6's union of the four audit permissions; the service
     * then narrows to the transition's specific frozen-matrix permission.
     */
    @PostMapping("/{auditId}/transition")
    @PreAuthorize("hasAnyAuthority('PERMISSION_audits.submit', "
            + "'PERMISSION_audits.review', 'PERMISSION_audits.approve', "
            + "'PERMISSION_audits.lock')")
    public ResponseEntity<ApiResponse<CarbonAudit>> transition(
            @PathVariable String auditId,
            @RequestBody GovernanceRequests.TransitionRequest request) {
        CarbonAudit audit = auditService.transition(orgId(), auditId, request, userId());
        return ResponseEntity.ok(ApiResponse.ok(audit,
                "Audit successfully transitioned to " + audit.getStatus() + "."));
    }

    @PostMapping("/{auditId}/checklist")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<AuditChecklistItem>> createChecklistItem(
            @PathVariable String auditId,
            @RequestBody GovernanceRequests.ChecklistCreateRequest request) {
        AuditChecklistItem item = auditService.createChecklistItem(orgId(), auditId,
                request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(item, "Checklist item created."));
    }

    @PutMapping("/{auditId}/checklist/{itemId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<AuditChecklistItem>> updateChecklistItem(
            @PathVariable String auditId,
            @PathVariable String itemId,
            @RequestBody GovernanceRequests.ChecklistUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                auditService.updateChecklistItem(orgId(), auditId, itemId, request),
                "Checklist item updated."));
    }

    @PostMapping("/{auditId}/checklist/{itemId}/verify")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<AuditChecklistItem>> verifyChecklistItem(
            @PathVariable String auditId,
            @PathVariable String itemId,
            @RequestBody GovernanceRequests.ChecklistVerifyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                auditService.verifyChecklistItem(orgId(), auditId, itemId, request,
                        userId()),
                "Checklist item status updated."));
    }
}
