package com.carbonflow.dto;

import com.carbonflow.model.CarbonTarget;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code carbon_targets} wire shape (Node parity fields) plus the Phase 7
 * <b>computed</b> progress block (API.md §2.8 gap matrix row 8).
 *
 * <p>Progress is derived at read time from persisted {@code ACTIVE} emission
 * records of {@code targetPeriodId} — never stored, never invented
 * (ADR-018/019):
 * <ul>
 *   <li>{@code currentLocationBasedT}/{@code currentMarketBasedT}: the target
 *       period's totals on each Scope 2 perspective (Scope 1 + the basis'
 *       Scope 2 + Scope 3) — the two bases never merge;</li>
 *   <li>{@code progressLocationPct}/{@code progressMarketPct} (2dp): share of
 *       the planned reduction ({@code baseline − target}) already achieved
 *       ({@code (baseline − current) / planned × 100}), {@code null} when the
 *       target period has no ACTIVE records or the planned reduction is not
 *       positive (division undefined — reported, not guessed);</li>
 *   <li>{@code hasPersistedEmissions}: {@code true} only when the target
 *       period actually contains emission records.</li>
 * </ul>
 * Status is stored/user-set — progress never rewrites it (no auto-ACHIEVED).
 */
public class CarbonTargetDto {

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

    private BigDecimal plannedReductionT;
    private BigDecimal currentLocationBasedT;
    private BigDecimal currentMarketBasedT;
    private BigDecimal progressLocationPct;
    private BigDecimal progressMarketPct;
    private boolean hasPersistedEmissions;

    public static CarbonTargetDto from(CarbonTarget target) {
        CarbonTargetDto dto = new CarbonTargetDto();
        dto.id = target.getId();
        dto.organizationId = target.getOrganizationId();
        dto.name = target.getName();
        dto.baselinePeriodId = target.getBaselinePeriodId();
        dto.targetPeriodId = target.getTargetPeriodId();
        dto.baselineValueT = target.getBaselineValueT();
        dto.targetValueT = target.getTargetValueT();
        dto.reductionPercentage = target.getReductionPercentage();
        dto.status = target.getStatus();
        dto.ownerId = target.getOwnerId();
        dto.notes = target.getNotes();
        dto.createdAt = target.getCreatedAt();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaselinePeriodId() { return baselinePeriodId; }
    public void setBaselinePeriodId(String baselinePeriodId) { this.baselinePeriodId = baselinePeriodId; }

    public String getTargetPeriodId() { return targetPeriodId; }
    public void setTargetPeriodId(String targetPeriodId) { this.targetPeriodId = targetPeriodId; }

    public BigDecimal getBaselineValueT() { return baselineValueT; }
    public void setBaselineValueT(BigDecimal baselineValueT) { this.baselineValueT = baselineValueT; }

    public BigDecimal getTargetValueT() { return targetValueT; }
    public void setTargetValueT(BigDecimal targetValueT) { this.targetValueT = targetValueT; }

    public BigDecimal getReductionPercentage() { return reductionPercentage; }
    public void setReductionPercentage(BigDecimal reductionPercentage) { this.reductionPercentage = reductionPercentage; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public BigDecimal getPlannedReductionT() { return plannedReductionT; }
    public void setPlannedReductionT(BigDecimal plannedReductionT) { this.plannedReductionT = plannedReductionT; }

    public BigDecimal getCurrentLocationBasedT() { return currentLocationBasedT; }
    public void setCurrentLocationBasedT(BigDecimal currentLocationBasedT) { this.currentLocationBasedT = currentLocationBasedT; }

    public BigDecimal getCurrentMarketBasedT() { return currentMarketBasedT; }
    public void setCurrentMarketBasedT(BigDecimal currentMarketBasedT) { this.currentMarketBasedT = currentMarketBasedT; }

    public BigDecimal getProgressLocationPct() { return progressLocationPct; }
    public void setProgressLocationPct(BigDecimal progressLocationPct) { this.progressLocationPct = progressLocationPct; }

    public BigDecimal getProgressMarketPct() { return progressMarketPct; }
    public void setProgressMarketPct(BigDecimal progressMarketPct) { this.progressMarketPct = progressMarketPct; }

    public boolean isHasPersistedEmissions() { return hasPersistedEmissions; }
    public void setHasPersistedEmissions(boolean hasPersistedEmissions) { this.hasPersistedEmissions = hasPersistedEmissions; }
}
