package com.carbonflow.model;

import com.carbonflow.dto.PlainBigDecimalSerializer;
import com.carbonflow.model.enums.GHGScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code activity_data} row in the exact wire shape produced by the Node
 * reference's {@code mapActivity} ({@code server/activity-repository.ts}):
 * DB-backed columns plus the response-only enrichment {@code facilityName},
 * {@code evidence} and {@code calculation}.
 *
 * <p>{@code evidence} and {@code calculation} are annotated {@code ALWAYS}
 * because the Node payload always carries both keys — {@code null} when the
 * activity has no linked evidence / has never been calculated — while every
 * other absent field is dropped ({@code NON_NULL}, matching Node's
 * {@code undefined} properties which {@code JSON.stringify} omits).
 *
 * <p>Scope 2 is intentionally <em>not</em> stored on the activity: the
 * location/market perspective is derived at calculation time from
 * {@code category}/{@code activityType} and persisted on the emission record
 * (see {@link EmissionRecord#getScope2Type()}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityData {

    private String id;
    private String organizationId;
    private String reportingPeriodId;
    private String facilityId;
    private String departmentId;
    private GHGScope scope;
    private String category;
    private String activityType;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal quantity;
    private String unit;
    private LocalDate startDate;
    private LocalDate endDate;
    private String source;
    private String status;
    private String notes;
    private String submittedBy;
    private Instant createdAt;
    private Instant updatedAt;

    // Response-only enrichment (never persisted on this table).
    private String facilityName;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private ActivityEvidence evidence;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Calculation calculation;

    public ActivityData() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getReportingPeriodId() { return reportingPeriodId; }
    public void setReportingPeriodId(String reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }

    public String getFacilityId() { return facilityId; }
    public void setFacilityId(String facilityId) { this.facilityId = facilityId; }

    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

    public GHGScope getScope() { return scope; }
    public void setScope(GHGScope scope) { this.scope = scope; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String submittedBy) { this.submittedBy = submittedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getFacilityName() { return facilityName; }
    public void setFacilityName(String facilityName) { this.facilityName = facilityName; }

    public ActivityEvidence getEvidence() { return evidence; }
    public void setEvidence(ActivityEvidence evidence) { this.evidence = evidence; }

    public Calculation getCalculation() { return calculation; }
    public void setCalculation(Calculation calculation) { this.calculation = calculation; }
}
