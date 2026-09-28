package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code reduction_projects} row (V1: nullable {@code target_id}/
 * {@code facility_id} FKs with SET NULL, NOT NULL NUMERIC(18,4) baselines,
 * {@code start_date}/{@code end_date} DATE NOT NULL, {@code status IN
 * ('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED')} default {@code PLANNED},
 * nullable {@code owner_id}, {@code created_at}).
 *
 * <p>Planning records only — they never touch emission totals and no derived
 * carbon value is stored here (the frozen table has no notes/updated_at
 * column; {@code description} carries the narrative).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReductionProject {

    private String id;
    private String organizationId;
    private String targetId;
    private String facilityId;
    private String name;
    private String description;
    private BigDecimal baselineT;
    private BigDecimal expectedReductionT;
    private BigDecimal actualReductionT;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private String ownerId;
    private Instant createdAt;

    public ReductionProject() {
    }

    public ReductionProject(String id, String organizationId, String targetId,
                            String facilityId, String name, String description,
                            BigDecimal baselineT, BigDecimal expectedReductionT,
                            BigDecimal actualReductionT, LocalDate startDate,
                            LocalDate endDate, String status, String ownerId,
                            Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.targetId = targetId;
        this.facilityId = facilityId;
        this.name = name;
        this.description = description;
        this.baselineT = baselineT;
        this.expectedReductionT = expectedReductionT;
        this.actualReductionT = actualReductionT;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.ownerId = ownerId;
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

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getFacilityId() {
        return facilityId;
    }

    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getBaselineT() {
        return baselineT;
    }

    public void setBaselineT(BigDecimal baselineT) {
        this.baselineT = baselineT;
    }

    public BigDecimal getExpectedReductionT() {
        return expectedReductionT;
    }

    public void setExpectedReductionT(BigDecimal expectedReductionT) {
        this.expectedReductionT = expectedReductionT;
    }

    public BigDecimal getActualReductionT() {
        return actualReductionT;
    }

    public void setActualReductionT(BigDecimal actualReductionT) {
        this.actualReductionT = actualReductionT;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
