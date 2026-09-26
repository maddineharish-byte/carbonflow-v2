package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.LegalEntity;
import com.carbonflow.service.LegalEntityService;
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
 * Legal-entity endpoints (docs/API.md §2.2): list is the Node contract
 * ({@code organization.read}), create/update/delete follow the contract's
 * {@code organization.update} mapping (ADR-015 — the frozen 44-code matrix
 * has no {@code legal_entities.*} codes, and the Node backend never
 * implemented the write side). Organization scope comes exclusively from the
 * authenticated tenant context.
 */
@RestController
@RequestMapping("/api/v1/legal-entities")
public class LegalEntityController {

    private final LegalEntityService legalEntityService;

    public LegalEntityController(LegalEntityService legalEntityService) {
        this.legalEntityService = legalEntityService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_organization.read')")
    public ResponseEntity<ApiResponse<List<LegalEntity>>> getLegalEntities() {
        return ResponseEntity.ok(ApiResponse.ok(
                legalEntityService.list(TenantContext.get().getOrganizationId())));
    }

    @GetMapping("/{legalEntityId}")
    @PreAuthorize("hasAuthority('PERMISSION_organization.read')")
    public ResponseEntity<ApiResponse<LegalEntity>> getLegalEntity(
            @PathVariable String legalEntityId) {
        return ResponseEntity.ok(ApiResponse.ok(
                legalEntityService.get(TenantContext.get().getOrganizationId(), legalEntityId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_organization.update')")
    public ResponseEntity<ApiResponse<LegalEntity>> createLegalEntity(
            @RequestBody ScopeRequests.LegalEntityRequest request) {
        LegalEntity created =
                legalEntityService.create(TenantContext.get().getOrganizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Legal entity created."));
    }

    @PutMapping("/{legalEntityId}")
    @PreAuthorize("hasAuthority('PERMISSION_organization.update')")
    public ResponseEntity<ApiResponse<LegalEntity>> updateLegalEntity(
            @PathVariable String legalEntityId,
            @RequestBody ScopeRequests.LegalEntityRequest request) {
        LegalEntity updated = legalEntityService.update(
                TenantContext.get().getOrganizationId(), legalEntityId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Legal entity updated."));
    }

    @DeleteMapping("/{legalEntityId}")
    @PreAuthorize("hasAuthority('PERMISSION_organization.update')")
    public ResponseEntity<ApiResponse<Void>> deleteLegalEntity(
            @PathVariable String legalEntityId) {
        legalEntityService.delete(TenantContext.get().getOrganizationId(), legalEntityId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Legal entity deleted."));
    }
}
