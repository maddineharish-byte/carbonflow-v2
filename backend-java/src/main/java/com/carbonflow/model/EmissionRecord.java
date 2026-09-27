package com.carbonflow.model;

import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;
import com.carbonflow.dto.PlainBigDecimalSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code emission_records} row in the Node reference's wire shape
 * ({@code mapEmission}) — the ledger entry produced by a calculation.
 *
 * <p><b>Scope 2 dual reporting:</b> each record carries exactly one
 * {@code scope2Type} ({@code LOCATION_BASED} or {@code MARKET_BASED}) or none
 * at all for Scope 1/3. Location- and market-based results are separate rows
 * that are never summed together anywhere in the pipeline.
 *
 * <p>{@code status} is {@code ACTIVE} until a later calculation of the same
 * activity supersedes it ({@code SUPERSEDED}); the V1 CHECK also permits
 * {@code VOIDED}. Only {@code ACTIVE} rows feed the ledger summary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmissionRecord {

    private String id;
    private String organizationId;
    private String reportingPeriodId;
    private String facilityId;
    private String calculationId;
    private GHGScope scope;
    private String category;
    private Scope2Method scope2Type;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal co2eTonnes;
    private String status;
    private Instant createdAt;

    public EmissionRecord() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getReportingPeriodId() { return reportingPeriodId; }
    public void setReportingPeriodId(String reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }

    public String getFacilityId() { return facilityId; }
    public void setFacilityId(String facilityId) { this.facilityId = facilityId; }

    public String getCalculationId() { return calculationId; }
    public void setCalculationId(String calculationId) { this.calculationId = calculationId; }

    public GHGScope getScope() { return scope; }
    public void setScope(GHGScope scope) { this.scope = scope; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Scope2Method getScope2Type() { return scope2Type; }
    public void setScope2Type(Scope2Method scope2Type) { this.scope2Type = scope2Type; }

    public BigDecimal getCo2eTonnes() { return co2eTonnes; }
    public void setCo2eTonnes(BigDecimal co2eTonnes) { this.co2eTonnes = co2eTonnes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
