package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.ReportingRequests;
import com.carbonflow.model.InventorySnapshot;
import com.carbonflow.service.InventorySnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Inventory snapshot endpoints (docs/API.md §2.8): list
 * ({@code inventory.read}), create ({@code inventory.create}, Node parity
 * message) and lock ({@code inventory.lock}, Java-only per the contract).
 * No DELETE and no amount edits exist — snapshots are append-only rows whose
 * only mutable field is status (ACTIVE → REVERTED/LOCKED). Organization scope
 * comes exclusively from the authenticated tenant context.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventorySnapshotService inventoryService;

    public InventoryController(InventorySnapshotService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_inventory.read')")
    public ResponseEntity<ApiResponse<List<InventorySnapshot>>> getSnapshots() {
        return ResponseEntity.ok(ApiResponse.ok(
                inventoryService.list(TenantContext.get().getOrganizationId())));
    }

    @PostMapping("/snapshot")
    @PreAuthorize("hasAuthority('PERMISSION_inventory.create')")
    public ResponseEntity<ApiResponse<InventorySnapshot>> createSnapshot(
            @RequestBody(required = false) ReportingRequests.InventorySnapshotRequest request) {
        InventorySnapshot created =
                inventoryService.create(TenantContext.get().getOrganizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Immutable inventory snapshot created."));
    }

    @PostMapping("/{snapshotId}/lock")
    @PreAuthorize("hasAuthority('PERMISSION_inventory.lock')")
    public ResponseEntity<ApiResponse<InventorySnapshot>> lockSnapshot(
            @PathVariable String snapshotId) {
        InventorySnapshot locked =
                inventoryService.lock(TenantContext.get().getOrganizationId(), snapshotId);
        return ResponseEntity.ok(ApiResponse.ok(locked, "Inventory snapshot locked."));
    }
}
