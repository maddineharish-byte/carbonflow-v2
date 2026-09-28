package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.ReportingRequests.ReductionProjectRequest;
import com.carbonflow.model.ReductionProject;
import com.carbonflow.service.ReductionProjectService;
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
 * Reduction project endpoints (docs/API.md §2.8): list
 * ({@code reduction_projects.read}), create ({@code reduction_projects.create},
 * Node parity message) and PUT partial update
 * ({@code reduction_projects.update} — greenfield justified by the frozen
 * permission code). No DELETE exists — the frozen matrix has no delete code.
 * The organization always comes from the authenticated tenant context.
 */
@RestController
@RequestMapping("/api/v1/reduction-projects")
public class ReductionProjectController {

    private final ReductionProjectService projectService;

    public ReductionProjectController(ReductionProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_reduction_projects.read')")
    public ResponseEntity<ApiResponse<List<ReductionProject>>> getReductionProjects() {
        return ResponseEntity.ok(ApiResponse.ok(
                projectService.list(TenantContext.get().getOrganizationId())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_reduction_projects.create')")
    public ResponseEntity<ApiResponse<ReductionProject>> createReductionProject(
            @RequestBody(required = false) ReductionProjectRequest request) {
        TenantContext ctx = TenantContext.get();
        ReductionProject created = projectService.create(
                ctx.getOrganizationId(), ctx.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Reduction project created."));
    }

    @PutMapping("/{projectId}")
    @PreAuthorize("hasAuthority('PERMISSION_reduction_projects.update')")
    public ResponseEntity<ApiResponse<ReductionProject>> updateReductionProject(
            @PathVariable String projectId,
            @RequestBody(required = false) ReductionProjectRequest request) {
        ReductionProject updated = projectService.update(
                TenantContext.get().getOrganizationId(), projectId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Reduction project updated."));
    }
}
