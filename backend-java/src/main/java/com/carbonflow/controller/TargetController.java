package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.CarbonTargetDto;
import com.carbonflow.dto.ReportingRequests.CarbonTargetRequest;
import com.carbonflow.service.CarbonTargetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Carbon target endpoints (docs/API.md §2.8): list ({@code targets.read}),
 * create ({@code targets.create}, Node parity message) and PUT partial update
 * ({@code targets.update} — greenfield justified by the frozen permission
 * code; Node never implemented the update side). No DELETE exists — the
 * frozen matrix has no {@code targets.delete} code. Responses carry the
 * computed progress block; the organization always comes from the
 * authenticated tenant context.
 */
@RestController
@RequestMapping("/api/v1/targets")
public class TargetController {

    private final CarbonTargetService targetService;

    public TargetController(CarbonTargetService targetService) {
        this.targetService = targetService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_targets.read')")
    public ResponseEntity<ApiResponse<List<CarbonTargetDto>>> getTargets() {
        return ResponseEntity.ok(ApiResponse.ok(
                targetService.list(TenantContext.get().getOrganizationId())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_targets.create')")
    public ResponseEntity<ApiResponse<CarbonTargetDto>> createTarget(
            @RequestBody(required = false) CarbonTargetRequest request) {
        TenantContext ctx = TenantContext.get();
        CarbonTargetDto created = targetService.create(
                ctx.getOrganizationId(), ctx.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Carbon target created."));
    }

    @PutMapping("/{targetId}")
    @PreAuthorize("hasAuthority('PERMISSION_targets.update')")
    public ResponseEntity<ApiResponse<CarbonTargetDto>> updateTarget(
            @PathVariable String targetId,
            @RequestBody(required = false) CarbonTargetRequest request) {
        CarbonTargetDto updated = targetService.update(
                TenantContext.get().getOrganizationId(), targetId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Carbon target updated."));
    }
}
