package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.Facility;
import com.carbonflow.service.FacilityService;
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
 * Facility endpoints (docs/API.md §2.2), now PostgreSQL-backed.
 *
 * <p>Replaces the Phase 2 prototype that stored facilities in the in-memory
 * {@code DataStore}; Node's {@code GET/POST /facilities} semantics are
 * preserved (org-scoped list ordered by name, {@code 201} on create,
 * {@code DUPLICATE_FACILITY_CODE} on a repeated tenant code), and the
 * get-by-id/update/delete verbs are greenfield (ADR-015). Every handler
 * derives the organization from the authenticated tenant context — the
 * client never supplies an {@code organizationId}.
 */
@RestController
@RequestMapping("/api/v1/facilities")
public class FacilityController {

    private final FacilityService facilityService;

    public FacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_facilities.read')")
    public ResponseEntity<ApiResponse<List<Facility>>> getFacilities() {
        return ResponseEntity.ok(
                ApiResponse.ok(facilityService.list(TenantContext.get().getOrganizationId())));
    }

    @GetMapping("/{facilityId}")
    @PreAuthorize("hasAuthority('PERMISSION_facilities.read')")
    public ResponseEntity<ApiResponse<Facility>> getFacility(@PathVariable String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(
                facilityService.get(TenantContext.get().getOrganizationId(), facilityId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_facilities.create')")
    public ResponseEntity<ApiResponse<Facility>> createFacility(
            @RequestBody ScopeRequests.FacilityRequest request) {
        Facility created = facilityService.create(TenantContext.get().getOrganizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Facility registered successfully."));
    }

    @PutMapping("/{facilityId}")
    @PreAuthorize("hasAuthority('PERMISSION_facilities.update')")
    public ResponseEntity<ApiResponse<Facility>> updateFacility(
            @PathVariable String facilityId,
            @RequestBody ScopeRequests.FacilityRequest request) {
        Facility updated = facilityService.update(
                TenantContext.get().getOrganizationId(), facilityId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Facility updated."));
    }

    @DeleteMapping("/{facilityId}")
    @PreAuthorize("hasAuthority('PERMISSION_facilities.delete')")
    public ResponseEntity<ApiResponse<Void>> deleteFacility(@PathVariable String facilityId) {
        facilityService.delete(TenantContext.get().getOrganizationId(), facilityId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Facility deleted."));
    }
}
