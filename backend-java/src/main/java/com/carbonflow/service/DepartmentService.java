package com.carbonflow.service;

import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.Department;
import com.carbonflow.repository.DepartmentRepository;
import com.carbonflow.service.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Department management (organization scoped, tenant isolated).
 *
 * <p>Greenfield domain: the Node reference backend has no department
 * endpoints, so semantics derive strictly from the V1 schema — a department
 * belongs to a facility ({@code facility_id NOT NULL}) inside an
 * organization. No relationship beyond that exists or is invented; no
 * status column exists, so lifecycle is delete-only (V1 cascades department
 * deletion from a facility, and {@code activity_data.department_id} is
 * {@code ON DELETE SET NULL}, so hard deletion is always safe).
 *
 * <p>Permission mapping (frozen 44-code matrix, ADR-015): departments reuse
 * the {@code facilities.*} codes because they are sub-facility resources.
 */
@Service
public class DepartmentService {

    private static final int MAX_NAME = 150;

    private final DepartmentRepository repository;
    private final ScopeService scope;

    public DepartmentService(DepartmentRepository repository, ScopeService scope) {
        this.repository = repository;
        this.scope = scope;
    }

    /** With {@code facilityId}: tenant-guarded filter; foreign/unknown filter yields []. */
    public List<Department> list(String organizationId, String facilityId) {
        if (facilityId == null || facilityId.isBlank()) {
            return repository.list(organizationId);
        }
        String trimmed = facilityId.trim();
        try {
            UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new AuthException("INVALID_ARGUMENT",
                    "Invalid value for parameter 'facilityId'.", HttpStatus.BAD_REQUEST);
        }
        return repository.listByFacility(organizationId, trimmed);
    }

    public Department get(String organizationId, String departmentId) {
        return scope.requireDepartment(organizationId, departmentId);
    }

    public Department create(String organizationId, ScopeRequests.DepartmentRequest request) {
        String name = requireName(request.getName());
        if (request.getFacilityId() == null || request.getFacilityId().isBlank()) {
            throw validation("facilityId is required.");
        }
        com.carbonflow.model.Facility parent =
                scope.requireFacility(organizationId, request.getFacilityId());
        return repository.insert(organizationId, parent.getId(), name);
    }

    /** Partial update: {@code name} and/or {@code facilityId} (re-parent validated). */
    public Department update(String organizationId, String departmentId,
                             ScopeRequests.DepartmentRequest request) {
        Department existing = scope.requireDepartment(organizationId, departmentId);

        String name = existing.getName();
        if (request.getName() != null) {
            name = requireName(request.getName());
        }
        String facilityId = existing.getFacilityId();
        if (request.getFacilityId() != null) {
            if (request.getFacilityId().isBlank()) {
                throw validation("facilityId is required.");
            }
            facilityId = scope.requireFacility(organizationId, request.getFacilityId()).getId();
        }
        repository.update(organizationId, existing.getId(), facilityId, name);
        return scope.requireDepartment(organizationId, existing.getId());
    }

    public void delete(String organizationId, String departmentId) {
        Department existing = scope.requireDepartment(organizationId, departmentId);
        repository.delete(organizationId, existing.getId());
    }

    // ------------------------------------------------------------------

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw validation("Department name is required.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw validation("Department name must be " + MAX_NAME + " characters or fewer.");
        }
        return trimmed;
    }

    private AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}
