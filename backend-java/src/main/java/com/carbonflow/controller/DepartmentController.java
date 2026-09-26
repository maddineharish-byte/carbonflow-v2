package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.Department;
import com.carbonflow.service.DepartmentService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Department endpoints (greenfield — no Node counterpart; ADR-015 maps
 * departments onto the {@code facilities.*} permission codes because a
 * department is a sub-facility resource in the V1 schema). Tenant scope is
 * derived from the authenticated context; the optional {@code facilityId}
 * filter is validated against that tenant and silently narrows the list.
 */
@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_facilities.read')")
    public ResponseEntity<ApiResponse<List<Department>>> getDepartments(
            @RequestParam(required = false) String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(
                departmentService.list(TenantContext.get().getOrganizationId(), facilityId)));
    }

    @GetMapping("/{departmentId}")
    @PreAuthorize("hasAuthority('PERMISSION_facilities.read')")
    public ResponseEntity<ApiResponse<Department>> getDepartment(
            @PathVariable String departmentId) {
        return ResponseEntity.ok(ApiResponse.ok(
                departmentService.get(TenantContext.get().getOrganizationId(), departmentId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_facilities.create')")
    public ResponseEntity<ApiResponse<Department>> createDepartment(
            @RequestBody ScopeRequests.DepartmentRequest request) {
        Department created =
                departmentService.create(TenantContext.get().getOrganizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Department created."));
    }

    @PutMapping("/{departmentId}")
    @PreAuthorize("hasAuthority('PERMISSION_facilities.update')")
    public ResponseEntity<ApiResponse<Department>> updateDepartment(
            @PathVariable String departmentId,
            @RequestBody ScopeRequests.DepartmentRequest request) {
        Department updated = departmentService.update(
                TenantContext.get().getOrganizationId(), departmentId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Department updated."));
    }

    @DeleteMapping("/{departmentId}")
    @PreAuthorize("hasAuthority('PERMISSION_facilities.delete')")
    public ResponseEntity<ApiResponse<Void>> deleteDepartment(@PathVariable String departmentId) {
        departmentService.delete(TenantContext.get().getOrganizationId(), departmentId);
        return ResponseEntity.ok(ApiResponse.ok(null, "Department deleted."));
    }
}
