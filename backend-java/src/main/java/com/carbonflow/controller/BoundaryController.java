package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.OrganizationalBoundary;
import com.carbonflow.service.BoundaryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Organizational-boundary endpoints (docs/API.md §2.2 {@code GET/POST
 * /boundaries}; get/update/delete and the membership verbs are greenfield —
 * ADR-015). Boundary membership lives in {@code boundary_facilities} and is
 * written only through tenant-validated statements; an Organization A
 * boundary can never gain an Organization B facility.
 *
 * <p>Permissions: reads reuse {@code reporting_periods.read} (a boundary is
 * the consolidation structure <i>of</i> a reporting period — its V1
 * {@code reporting_period_id} is {@code NOT NULL}); writes reuse
 * {@code reporting_periods.update} (the frozen 44-code matrix has no
 * boundary-specific codes; both codes resolve to the same two managing
 * roles as period management — ADR-015).
 */
@RestController
@RequestMapping("/api/v1/boundaries")
public class BoundaryController {

    private final BoundaryService boundaryService;

    public BoundaryController(BoundaryService boundaryService) {
        this.boundaryService = boundaryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.read')")
    public ResponseEntity<ApiResponse<List<OrganizationalBoundary>>> getBoundaries() {
        return ResponseEntity.ok(ApiResponse.ok(
                boundaryService.list(TenantContext.get().getOrganizationId())));
    }

    @GetMapping("/{boundaryId}")
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.read')")
    public ResponseEntity<ApiResponse<OrganizationalBoundary>> getBoundary(
            @PathVariable String boundaryId) {
        return ResponseEntity.ok(ApiResponse.ok(
                boundaryService.get(TenantContext.get().getOrganizationId(), boundaryId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.update')")
    public ResponseEntity<ApiResponse<OrganizationalBoundary>> createBoundary(
            @RequestBody ScopeRequests.BoundaryRequest request) {
        OrganizationalBoundary created =
                boundaryService.create(TenantContext.get().getOrganizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Organizational boundary created."));
    }

    @PutMapping("/{boundaryId}")
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.update')")
    public ResponseEntity<ApiResponse<OrganizationalBoundary>> updateBoundary(
            @PathVariable String boundaryId,
            @RequestBody ScopeRequests.BoundaryUpdateRequest request) {
        OrganizationalBoundary updated = boundaryService.update(
                TenantContext.get().getOrganizationId(), boundaryId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Organizational boundary updated."));
    }

    @DeleteMapping("/{boundaryId}")
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.update')")
    public ResponseEntity<ApiResponse<Void>> deleteBoundary(@PathVariable String boundaryId) {
        boundaryService.delete(TenantContext.get().getOrganizationId(), boundaryId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Organizational boundary deleted."));
    }

    @PostMapping("/{boundaryId}/facilities")
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.update')")
    public ResponseEntity<ApiResponse<OrganizationalBoundary>> attachFacility(
            @PathVariable String boundaryId,
            @RequestBody ScopeRequests.BoundaryFacilityRequest request) {
        OrganizationalBoundary updated = boundaryService.attach(
                TenantContext.get().getOrganizationId(), boundaryId, request.getFacilityId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(updated, "Facility attached to boundary."));
    }

    @DeleteMapping("/{boundaryId}/facilities/{facilityId}")
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.update')")
    public ResponseEntity<ApiResponse<Void>> detachFacility(
            @PathVariable String boundaryId, @PathVariable String facilityId) {
        boundaryService.detach(TenantContext.get().getOrganizationId(), boundaryId, facilityId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Facility detached from boundary."));
    }
}
