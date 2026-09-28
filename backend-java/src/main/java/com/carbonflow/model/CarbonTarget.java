package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code carbon_targets} row (V1: {@code NUMERIC(18,4)} value columns,
 * {@code reduction_percentage} NUMERIC(5,2), {@code status IN ('ON_TRACK',
 * 'BEHIND','ACHIEVED','EXPIRED')} default {@code ON_TRACK}, nullable
 * {@code owner_id}, {@code notes} TEXT, {@code created_at}).
 *
 * <p>The frozen schema has no {@code updated_at}, no scope/unit column and no
 * stored progress column: tonnes are metric (t CO2e, the column's unit), and
 * progress is computed from persisted emissions at read time (never stored,
 * never invented) — see {@code CarbonTargetService} and ADR-018/019 notes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CarbonTarget {

    private String id;
    private String organizationId;
    private String name;
    private String baselinePeriodId;
    private String targetPeriodId;
    private BigDecimal baselineValueT;
    private BigDecimal targetValueT;
    private BigDecimal reductionPercentage;
    private String status;
    private String ownerId;
    private String notes;
    private Instant createdAt;

    public CarbonTarget() {
    }

    public CarbonTarget(String id, String organizationId, String name,
                        String baselinePeriodId, String targetPeriodId,
                        BigDecimal baselineValueT, BigDecimal targetValueT,
                        BigDecimal reductionPercentage, String status, String ownerId,
                        String notes, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.baselinePeriodId = baselinePeriodId;
        this.targetPeriodId = targetPeriodId;
        this.baselineValueT = baselineValueT;
        this.targetValueT = targetValueT;
        this.reductionPercentage = reductionPercentage;
        this.status = status;
        this.ownerId = ownerId;
        this.notes = notes;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaselinePeriodId() {
        return baselinePeriodId;
    }

    public void setBaselinePeriodId(String baselinePeriodId) {
        this.baselinePeriodId = baselinePeriodId;
    }

    public String getTargetPeriodId() {
        return targetPeriodId;
    }

    public void setTargetPeriodId(String targetPeriodId) {
        this.targetPeriodId = targetPeriodId;
    }

    public BigDecimal getBaselineValueT() {
        return baselineValueT;
    }

    public void setBaselineValueT(BigDecimal baselineValueT) {
        this.baselineValueT = baselineValueT;
    }

    public BigDecimal getTargetValueT() {
        return targetValueT;
    }

    public void setTargetValueT(BigDecimal targetValueT) {
        this.targetValueT = targetValueT;
    }

    public BigDecimal getReductionPercentage() {
        return reductionPercentage;
    }

    public void setReductionPercentage(BigDecimal reductionPercentage) {
        this.reductionPercentage = reductionPercentage;
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

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
