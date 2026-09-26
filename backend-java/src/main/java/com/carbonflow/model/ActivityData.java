package com.carbonflow.model;

import com.carbonflow.model.enums.EmissionCategory;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public class ActivityData {
    private String id;
    private String organizationId;
    private String facilityId;
    private String reportingPeriodId;
    private GHGScope scope;
    private EmissionCategory category;
    private Scope2Method scope2Type;
    private String activityType;
    private BigDecimal quantity;
    private String unit;
    private LocalDate startDate;
    private LocalDate endDate;
    private String description;
    private String status; // RAW, CALCULATED, AUDITED, FLAGGED
    private String evidenceId;
    private String calculationId;
    private Instant createdAt;

    public ActivityData() {}

    public ActivityData(String id, String organizationId, String facilityId, String reportingPeriodId,
                        GHGScope scope, EmissionCategory category, Scope2Method scope2Type,
                        String activityType, BigDecimal quantity, String unit,
                        LocalDate startDate, LocalDate endDate, String description,
                        String status, String evidenceId, String calculationId, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.facilityId = facilityId;
        this.reportingPeriodId = reportingPeriodId;
        this.scope = scope;
        this.category = category;
        this.scope2Type = scope2Type;
        this.activityType = activityType;
        this.quantity = quantity;
        this.unit = unit;
        this.startDate = startDate;
        this.endDate = endDate;
        this.description = description;
        this.status = status;
        this.evidenceId = evidenceId;
        this.calculationId = calculationId;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getFacilityId() { return facilityId; }
    public void setFacilityId(String facilityId) { this.facilityId = facilityId; }

    public String getReportingPeriodId() { return reportingPeriodId; }
    public void setReportingPeriodId(String reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }

    public GHGScope getScope() { return scope; }
    public void setScope(GHGScope scope) { this.scope = scope; }

    public EmissionCategory getCategory() { return category; }
    public void setCategory(EmissionCategory category) { this.category = category; }

    public Scope2Method getScope2Type() { return scope2Type; }
    public void setScope2Type(Scope2Method scope2Type) { this.scope2Type = scope2Type; }

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

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEvidenceId() { return evidenceId; }
    public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }

    public String getCalculationId() { return calculationId; }
    public void setCalculationId(String calculationId) { this.calculationId = calculationId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
