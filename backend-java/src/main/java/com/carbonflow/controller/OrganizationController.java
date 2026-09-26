package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.OrganizationRequests.UpdateOrganizationRequest;
import com.carbonflow.model.Organization;
import com.carbonflow.service.OrganizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current tenant profile — contract-compatible port of Node's
 * {@code GET/PUT /organizations/current} (permissions
 * {@code organization.read|organization.update}).
 */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('PERMISSION_organization.read')")
    public ResponseEntity<ApiResponse<Organization>> current() {
        return ResponseEntity.ok(ApiResponse.ok(
                organizationService.current(TenantContext.get())));
    }

    @PutMapping("/current")
    @PreAuthorize("hasAuthority('PERMISSION_organization.update')")
    public ResponseEntity<ApiResponse<Organization>> update(@RequestBody UpdateOrganizationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                organizationService.update(TenantContext.get(), request),
                "Organization updated successfully."));
    }
}
