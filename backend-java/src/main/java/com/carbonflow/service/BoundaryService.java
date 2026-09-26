package com.carbonflow.service;

import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.OrganizationalBoundary;
import com.carbonflow.repository.BoundaryRepository;
import com.carbonflow.service.AuthException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Organizational-boundary management: Organization → Boundary → Boundary
 * Facilities (the consolidation structure, distinct from the reporting
 * structure Organization → Legal Entity → Facility → Department).
 *
 * <p>Greenfield semantics (docs/API.md §2.2 defines {@code GET/POST
 * /boundaries} but the Node backend never implemented them — ADR-015):
 * resource-CRUD over {@code organizational_boundaries}, one boundary per
 * created row, membership persisted server-side in {@code boundary_facilities}
 * and never inferred from client state.
 *
 * <p><b>CRITICAL tenant rule:</b> attaching a facility first resolves the
 * boundary and the facility under the caller's organization, and the insert
 * itself joins {@code facilities.organization_id = boundaries.organization_id}
 * — an Organization A boundary plus an Organization B facility matches zero
 * rows and is rejected as {@code 404 FACILITY_NOT_FOUND} with nothing
 * persisted, even when both UUIDs are valid. Writes are transactional: a
 * boundary and its membership either both persist or neither does.
 */
@Service
public class BoundaryService {

    static final Set<String> APPROACHES =
            Set.of("OPERATIONAL_CONTROL", "FINANCIAL_CONTROL", "EQUITY_SHARE");

    private final BoundaryRepository repository;
    private final ScopeService scope;

    public BoundaryService(BoundaryRepository repository, ScopeService scope) {
        this.repository = repository;
        this.scope = scope;
    }

    public List<OrganizationalBoundary> list(String organizationId) {
        return repository.list(organizationId);
    }

    public OrganizationalBoundary get(String organizationId, String boundaryId) {
        return scope.requireBoundary(organizationId, boundaryId);
    }

    @Transactional
    public OrganizationalBoundary create(String organizationId,
                                         ScopeRequests.BoundaryRequest request) {
        if (request.getReportingPeriodId() == null || request.getReportingPeriodId().isBlank()) {
            throw validation("reportingPeriodId is required.");
        }
        com.carbonflow.model.ReportingPeriod period =
                scope.requireReportingPeriod(organizationId, request.getReportingPeriodId());

        String approach = request.getConsolidationApproach() == null
                || request.getConsolidationApproach().isBlank()
                ? "OPERATIONAL_CONTROL" : request.getConsolidationApproach().trim();
        if (!APPROACHES.contains(approach)) {
            throw validation("consolidationApproach must be one of "
                    + String.join(", ", APPROACHES.stream().sorted().toList()) + ".");
        }
        String notes = trimmedOrNull(request.getNotes());

        // Resolve every membership under this tenant BEFORE writing anything:
        // a cross-tenant or unknown facility id fails with 404 and no row.
        List<String> facilityIds = resolveFacilityIds(organizationId, request.getFacilityIds());

        OrganizationalBoundary boundary = repository.insert(
                organizationId, period.getId(), approach, notes);
        for (String facilityId : facilityIds) {
            int attached = repository.attach(organizationId, boundary.getId(), facilityId);
            if (attached == 0) {
                throw new AuthException("FACILITY_NOT_FOUND",
                        "Facility does not exist or access denied.", HttpStatus.NOT_FOUND);
            }
        }
        return scope.requireBoundary(organizationId, boundary.getId());
    }

    @Transactional
    public OrganizationalBoundary update(String organizationId, String boundaryId,
                                         ScopeRequests.BoundaryUpdateRequest request) {
        OrganizationalBoundary existing = scope.requireBoundary(organizationId, boundaryId);

        String approach = existing.getConsolidationApproach();
        if (request.getConsolidationApproach() != null
                && !request.getConsolidationApproach().isBlank()) {
            approach = request.getConsolidationApproach().trim();
            if (!APPROACHES.contains(approach)) {
                throw validation("consolidationApproach must be one of "
                        + String.join(", ", APPROACHES.stream().sorted().toList()) + ".");
            }
        }
        String notes = request.getNotes() == null ? existing.getNotes()
                : trimmedOrNull(request.getNotes());
        repository.update(organizationId, existing.getId(), approach, notes);
        return scope.requireBoundary(organizationId, existing.getId());
    }

    @Transactional
    public void delete(String organizationId, String boundaryId) {
        OrganizationalBoundary existing = scope.requireBoundary(organizationId, boundaryId);
        repository.delete(organizationId, existing.getId());
    }

    @Transactional
    public OrganizationalBoundary attach(String organizationId, String boundaryId,
                                         String facilityId) {
        OrganizationalBoundary boundary = scope.requireBoundary(organizationId, boundaryId);
        if (facilityId == null || facilityId.isBlank()) {
            throw validation("facilityId is required.");
        }
        // Cross-tenant or unknown facility → 404 before any insert attempt.
        com.carbonflow.model.Facility facility = scope.requireFacility(organizationId, facilityId);
        try {
            int attached = repository.attach(organizationId, boundary.getId(), facility.getId());
            if (attached == 0) {
                throw new AuthException("FACILITY_NOT_FOUND",
                        "Facility does not exist or access denied.", HttpStatus.NOT_FOUND);
            }
        } catch (DuplicateKeyException e) {
            throw new AuthException("DUPLICATE_BOUNDARY_FACILITY",
                    "Facility is already attached to this boundary.", HttpStatus.CONFLICT);
        }
        return scope.requireBoundary(organizationId, boundary.getId());
    }

    @Transactional
    public void detach(String organizationId, String boundaryId, String facilityId) {
        OrganizationalBoundary boundary = scope.requireBoundary(organizationId, boundaryId);
        if (facilityId == null || facilityId.isBlank()) {
            throw validation("facilityId is required.");
        }
        com.carbonflow.model.Facility facility = scope.requireFacility(organizationId, facilityId);
        int detached = repository.detach(organizationId, boundary.getId(), facility.getId());
        if (detached == 0) {
            throw new AuthException("BOUNDARY_FACILITY_NOT_FOUND",
                    "Facility is not attached to this boundary.", HttpStatus.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------

    private List<String> resolveFacilityIds(String organizationId, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String facilityId : requested) {
            if (facilityId == null || facilityId.isBlank()) {
                throw validation("facilityIds must not contain blank values.");
            }
            unique.add(scope.requireFacility(organizationId, facilityId).getId());
        }
        return new ArrayList<>(unique);
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}
