package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.OrganizationRequests.PlatformNoteRequest;
import com.carbonflow.model.Organization;
import com.carbonflow.model.enums.OrganizationStatus;
import com.carbonflow.service.PlatformTenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform tenant administration (greenfield, ADR-014) — the registration /
 * approval / activation lifecycle gated by {@code platform.tenants.read} and
 * {@code platform.tenants.manage} (PLATFORM_ADMIN only). These endpoints are
 * deliberately cross-tenant: authorization is the platform permission, not a
 * tenant predicate.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
public class PlatformTenantController {

    private final PlatformTenantService platformTenantService;

    public PlatformTenantController(PlatformTenantService platformTenantService) {
        this.platformTenantService = platformTenantService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_platform.tenants.read')")
    public ResponseEntity<ApiResponse<List<Organization>>> list(
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok(platformTenantService.list(status)));
    }

    @GetMapping("/{organizationId}")
    @PreAuthorize("hasAuthority('PERMISSION_platform.tenants.read')")
    public ResponseEntity<ApiResponse<Organization>> get(
            @PathVariable String organizationId) {
        return ResponseEntity.ok(ApiResponse.ok(platformTenantService.get(organizationId)));
    }

    @PostMapping("/{organizationId}/approve")
    @PreAuthorize("hasAuthority('PERMISSION_platform.tenants.manage')")
    public ResponseEntity<ApiResponse<Organization>> approve(@PathVariable String organizationId) {
        return ResponseEntity.ok(ApiResponse.ok(
                platformTenantService.transition(organizationId, OrganizationStatus.ACTIVE,
                        TenantContext.get(), null),
                "Organization approved."));
    }

    @PostMapping("/{organizationId}/reject")
    @PreAuthorize("hasAuthority('PERMISSION_platform.tenants.manage')")
    public ResponseEntity<ApiResponse<Organization>> reject(
            @PathVariable String organizationId,
            @RequestBody(required = false) PlatformNoteRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(
                platformTenantService.transition(organizationId, OrganizationStatus.REJECTED,
                        TenantContext.get(), body == null ? null : body.getNote()),
                "Organization rejected."));
    }

    @PostMapping("/{organizationId}/suspend")
    @PreAuthorize("hasAuthority('PERMISSION_platform.tenants.manage')")
    public ResponseEntity<ApiResponse<Organization>> suspend(
            @PathVariable String organizationId,
            @RequestBody(required = false) PlatformNoteRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(
                platformTenantService.transition(organizationId, OrganizationStatus.SUSPENDED,
                        TenantContext.get(), body == null ? null : body.getNote()),
                "Organization suspended."));
    }
}
