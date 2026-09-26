package com.carbonflow.service;

import com.carbonflow.model.Department;
import com.carbonflow.model.Facility;
import com.carbonflow.model.LegalEntity;
import com.carbonflow.model.OrganizationalBoundary;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.repository.BoundaryRepository;
import com.carbonflow.repository.DepartmentRepository;
import com.carbonflow.repository.FacilityRepository;
import com.carbonflow.repository.LegalEntityRepository;
import com.carbonflow.repository.ReportingPeriodRepository;
import com.carbonflow.service.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Scope-aware authorization foundation (Phase 4, module 7).
 *
 * <p>Every Phase 4 endpoint resolves client-supplied identifiers through this
 * service instead of trusting them. Two authorization dimensions are
 * enforced:
 *
 * <ol>
 *   <li><b>Organization scope</b> — the caller's organization id comes from
 *       the authenticated tenant context; a lookup that cannot see the row
 *       under that predicate fails identically to a nonexistent id.</li>
 *   <li><b>Resource scope</b> — facility / department / legal entity /
 *       reporting period / boundary references are validated here before any
 *       read or write uses them, so later phases (activity data, evidence,
 *       audits) reuse the same choke point.</li>
 * </ol>
 *
 * <p><b>Anti-enumeration rule:</b> missing, malformed and cross-tenant ids
 * all answer {@code 404 *_NOT_FOUND} with the Phase 3 message shape
 * “does not exist or access denied.” A caller can never distinguish another
 * tenant's resource from a nonexistent one — a cross-tenant probe with two
 * valid UUIDs therefore fails safely (see the Phase 4 cross-tenant test
 * matrix).
 *
 * <p>The RBAC matrix itself is untouched: permissions are enforced by
 * {@code @PreAuthorize} on the controllers, this class enforces <i>scope</i>.
 */
@Service
public class ScopeService {

    private final LegalEntityRepository legalEntities;
    private final FacilityRepository facilities;
    private final DepartmentRepository departments;
    private final ReportingPeriodRepository reportingPeriods;
    private final BoundaryRepository boundaries;

    public ScopeService(LegalEntityRepository legalEntities,
                        FacilityRepository facilities,
                        DepartmentRepository departments,
                        ReportingPeriodRepository reportingPeriods,
                        BoundaryRepository boundaries) {
        this.legalEntities = legalEntities;
        this.facilities = facilities;
        this.departments = departments;
        this.reportingPeriods = reportingPeriods;
        this.boundaries = boundaries;
    }

    public LegalEntity requireLegalEntity(String organizationId, String legalEntityId) {
        return legalEntities.findById(organizationId, requireUuid(legalEntityId, "Legal entity"))
                .orElseThrow(() -> notFound("Legal entity"));
    }

    public Facility requireFacility(String organizationId, String facilityId) {
        return facilities.findById(organizationId, requireUuid(facilityId, "Facility"))
                .orElseThrow(() -> notFound("Facility"));
    }

    public Department requireDepartment(String organizationId, String departmentId) {
        return departments.findById(organizationId, requireUuid(departmentId, "Department"))
                .orElseThrow(() -> notFound("Department"));
    }

    public ReportingPeriod requireReportingPeriod(String organizationId, String periodId) {
        return reportingPeriods.findById(organizationId, requireUuid(periodId, "Reporting period"))
                .orElseThrow(() -> notFound("Reporting period"));
    }

    public OrganizationalBoundary requireBoundary(String organizationId, String boundaryId) {
        return boundaries.findById(organizationId, requireUuid(boundaryId, "Boundary"))
                .orElseThrow(() -> notFound("Boundary"));
    }

    /**
     * Malformed ids are treated exactly like missing ones (never passed to
     * SQL, where a non-UUID string would raise a cast error).
     */
    private String requireUuid(String id, String label) {
        if (id == null || id.isBlank()) {
            throw notFound(label);
        }
        try {
            UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            throw notFound(label);
        }
        return id.trim();
    }

    private AuthException notFound(String label) {
        String code = label.toUpperCase().replace(' ', '_') + "_NOT_FOUND";
        return new AuthException(code,
                label + " does not exist or access denied.", HttpStatus.NOT_FOUND);
    }
}
