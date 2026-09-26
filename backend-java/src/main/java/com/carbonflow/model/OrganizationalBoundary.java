package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * {@code organizational_boundaries} row plus its persisted membership from
 * {@code boundary_facilities} (V1). The distinct hierarchy the product
 * requires is preserved:
 *
 * <ul>
 *   <li>Organization → Legal Entity → Facility → Department (reporting
 *       structure), and</li>
 *   <li>Organization → Organizational Boundary → Boundary Facilities
 *       (consolidation structure — a boundary always belongs to exactly one
 *       reporting period, {@code reporting_period_id NOT NULL}).</li>
 * </ul>
 *
 * <p>Membership is server-persisted, never inferred from frontend state:
 * {@code facilityIds} is read from {@code boundary_facilities} and written
 * only through tenant-validated statements.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrganizationalBoundary {

    private String id;
    private String organizationId;
    private String reportingPeriodId;
    private String consolidationApproach;
    private String notes;
    private List<String> facilityIds;
    private Instant createdAt;

    public OrganizationalBoundary() {
    }

    public OrganizationalBoundary(String id, String organizationId, String reportingPeriodId,
                                  String consolidationApproach, String notes,
                                  List<String> facilityIds, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.reportingPeriodId = reportingPeriodId;
        this.consolidationApproach = consolidationApproach;
        this.notes = notes;
        this.facilityIds = facilityIds;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getReportingPeriodId() {
        return reportingPeriodId;
    }

    public void setReportingPeriodId(String reportingPeriodId) {
        this.reportingPeriodId = reportingPeriodId;
    }

    public String getConsolidationApproach() {
        return consolidationApproach;
    }

    public void setConsolidationApproach(String consolidationApproach) {
        this.consolidationApproach = consolidationApproach;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getFacilityIds() {
        return facilityIds;
    }

    public void setFacilityIds(List<String> facilityIds) {
        this.facilityIds = facilityIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
