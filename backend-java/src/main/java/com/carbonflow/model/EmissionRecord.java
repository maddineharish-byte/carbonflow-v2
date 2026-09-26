package com.carbonflow.model;

import com.carbonflow.model.enums.EmissionCategory;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;

import java.math.BigDecimal;
import java.time.Instant;

public class EmissionRecord {
    private String id;
    private String organizationId;
    private String facilityId;
    private String reportingPeriodId;
    private String calculationId;
    private GHGScope scope;
    private EmissionCategory category;
    private Scope2Method scope2Type;
    private BigDecimal co2eTonnes;
    private String status; // ACTIVE, SUPERSEDED, AUDIT_LOCKED
    private Instant createdAt;

    public EmissionRecord() {}

    public EmissionRecord(String id, String organizationId, String facilityId, String reportingPeriodId,
                          String calculationId, GHGScope scope, EmissionCategory category,
                          Scope2Method scope2Type, BigDecimal co2eTonnes, String status, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.facilityId = facilityId;
        this.reportingPeriodId = reportingPeriodId;
        this.calculationId = calculationId;
        this.scope = scope;
        this.category = category;
        this.scope2Type = scope2Type;
        this.co2eTonnes = co2eTonnes;
        this.status = status;
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

    public String getCalculationId() { return calculationId; }
    public void setCalculationId(String calculationId) { this.calculationId = calculationId; }

    public GHGScope getScope() { return scope; }
    public void setScope(GHGScope scope) { this.scope = scope; }

    public EmissionCategory getCategory() { return category; }
    public void setCategory(EmissionCategory category) { this.category = category; }

    public Scope2Method getScope2Type() { return scope2Type; }
    public void setScope2Type(Scope2Method scope2Type) { this.scope2Type = scope2Type; }

    public BigDecimal getCo2eTonnes() { return co2eTonnes; }
    public void setCo2eTonnes(BigDecimal co2eTonnes) { this.co2eTonnes = co2eTonnes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
