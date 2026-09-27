package com.carbonflow.service;

import com.carbonflow.model.AuditLockEvent;
import com.carbonflow.model.CarbonAudit;
import com.carbonflow.model.Department;
import com.carbonflow.model.EvidenceRecord;
import com.carbonflow.model.Facility;
import com.carbonflow.model.LegalEntity;
import com.carbonflow.model.OrganizationalBoundary;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.repository.ActivityDataRepository;
import com.carbonflow.repository.AuditRepository;
import com.carbonflow.repository.BoundaryRepository;
import com.carbonflow.repository.DepartmentRepository;
import com.carbonflow.repository.EvidenceRepository;
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
    private final AuditRepository audits;
    private final ActivityDataRepository activityData;
    private final EvidenceRepository evidence;

    public ScopeService(LegalEntityRepository legalEntities,
                        FacilityRepository facilities,
                        DepartmentRepository departments,
                        ReportingPeriodRepository reportingPeriods,
                        BoundaryRepository boundaries,
                        AuditRepository audits,
                        ActivityDataRepository activityData,
                        EvidenceRepository evidence) {
        this.legalEntities = legalEntities;
        this.facilities = facilities;
        this.departments = departments;
        this.reportingPeriods = reportingPeriods;
        this.boundaries = boundaries;
        this.audits = audits;
        this.activityData = activityData;
        this.evidence = evidence;
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
     * Phase 5: every audit, correction target and evidence-link endpoint
     * resolves its audit through this choke point — a foreign or unknown
     * audit id is indistinguishable (404 {@code AUDIT_NOT_FOUND}).
     */
    public CarbonAudit requireAudit(String organizationId, String auditId) {
        return audits.findById(organizationId, requireUuid(auditId, "Audit"))
                .orElseThrow(() -> notFound("Audit"));
    }

    /** Phase 5: activity-data ids (correction requests, evidence links). */
    public String requireActivityData(String organizationId, String activityDataId) {
        String id = requireUuid(activityDataId, "Activity data");
        if (!activityData.exists(organizationId, id)) {
            throw notFound("Activity data");
        }
        return id;
    }

    /**
     * Phase 5: evidence ids (versions, links, download, delete).
     *
     * <p>Malformed, unknown and cross-tenant ids all collapse into the Node
     * reference's exact response ({@code routes.ts} line 1077): code
     * {@code EVIDENCE_NOT_FOUND}, message “Evidence document not found or
     * cross-tenant access prohibited.” — deliberately <i>not</i> routed
     * through {@link #notFound(String)}, whose label-derived code would leak
     * the malformed-vs-missing distinction.
     */
    public EvidenceRecord requireEvidence(String organizationId, String evidenceId) {
        String id = null;
        if (evidenceId != null && !evidenceId.isBlank()) {
            try {
                id = UUID.fromString(evidenceId.trim()).toString();
            } catch (IllegalArgumentException e) {
                id = null;
            }
        }
        if (id == null) {
            throw evidenceNotFound();
        }
        return evidence.findById(organizationId, id)
                .orElseThrow(ScopeService::evidenceNotFound);
    }

    private static AuthException evidenceNotFound() {
        return new AuthException("EVIDENCE_NOT_FOUND",
                "Evidence document not found or cross-tenant access prohibited.",
                HttpStatus.NOT_FOUND);
    }

    /**
     * Malformed ids are treated exactly like missing ones (never passed to
     * SQL, where a non-UUID string would raise a cast error).
     */
    static String requireUuid(String id, String label) {
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

    static AuthException notFound(String label) {
        String code = label.toUpperCase().replace(' ', '_') + "_NOT_FOUND";
        return new AuthException(code,
                label + " does not exist or access denied.", HttpStatus.NOT_FOUND);
    }
}
