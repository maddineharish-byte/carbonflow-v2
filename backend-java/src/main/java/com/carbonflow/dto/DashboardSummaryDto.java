package com.carbonflow.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Executive dashboard payload ({@code GET /analytics/dashboard}).
 *
 * <p>Field names follow the frontend {@code DashboardSummary} contract
 * ({@code src/types.ts}) so the React dashboard binds directly. All tonnes are
 * presentation-rounded to 2dp HALF_UP <b>after</b> full-precision aggregation
 * (Node parity: {@code toFixed(2)}).
 *
 * <p><b>Scope 2 dual reporting (ADR-002):</b> location-based and market-based
 * values are reported side by side and never summed together. The single
 * {@code scope2Tonnes} / {@code totalTonnes} chart fields carry the
 * <b>location-based</b> perspective (the additive {@code scope2MarketTonnes} /
 * {@code totalMarketBasedTonnes} fields carry the market perspective). This
 * deliberately differs from the Node oracle, whose facility/category breakdown
 * adds both perspectives together and double-counts dual-reported rows
 * (Phase 7 deviation, ADR-018).
 */
public class DashboardSummaryDto {
    private EmissionsTotals emissions;
    private String auditStatus;
    private AuditHealth auditHealth;
    private int activityCount;
    private int targetsCount;
    private int reductionProjectsCount;
    private List<CategoryBreakdown> categories;
    private List<FacilitySummary> facilities;
    private List<PeriodTrend> periodTrends;

    /** Organization totals — both Scope 2 perspectives kept separate. */
    public static class EmissionsTotals {
        public BigDecimal scope1Tonnes = BigDecimal.ZERO;
        public BigDecimal scope2LocationTonnes = BigDecimal.ZERO;
        public BigDecimal scope2MarketTonnes = BigDecimal.ZERO;
        public BigDecimal scope3Tonnes = BigDecimal.ZERO;
        public BigDecimal totalLocationBasedTonnes = BigDecimal.ZERO;
        public BigDecimal totalMarketBasedTonnes = BigDecimal.ZERO;
    }

    /** Checklist / findings health of the organization's current audit. */
    public static class AuditHealth {
        public int checklistSatisfied;
        public int checklistTotal;
        public int openFindingsCount;
    }

    /**
     * Per-category totals. {@code tonnes} = location-based perspective (charted
     * by the pie); {@code tonnesMarketBased} = market-based perspective.
     */
    public static class CategoryBreakdown {
        public String category;
        public BigDecimal tonnes = BigDecimal.ZERO;
        public BigDecimal tonnesMarketBased = BigDecimal.ZERO;

        public CategoryBreakdown(String category, BigDecimal tonnes, BigDecimal tonnesMarketBased) {
            this.category = category;
            this.tonnes = tonnes;
            this.tonnesMarketBased = tonnesMarketBased;
        }
    }

    /**
     * Per-facility totals. {@code scope2Tonnes}/{@code totalTonnes} are the
     * location-based perspective; the market perspective is additive.
     */
    public static class FacilitySummary {
        public String id;
        public String name;
        public String code;
        public BigDecimal scope1Tonnes = BigDecimal.ZERO;
        public BigDecimal scope2Tonnes = BigDecimal.ZERO;
        public BigDecimal scope2LocationTonnes = BigDecimal.ZERO;
        public BigDecimal scope2MarketTonnes = BigDecimal.ZERO;
        public BigDecimal totalTonnes = BigDecimal.ZERO;
        public BigDecimal totalMarketBasedTonnes = BigDecimal.ZERO;

        public FacilitySummary(String id, String name, String code) {
            this.id = id;
            this.name = name;
            this.code = code;
        }
    }

    /**
     * One real reporting period (last 12 by start date). Periods without
     * persisted emissions appear with zero totals — no synthetic months, no
     * benchmark values (Phase 7 de-fabrication, ADR-018).
     */
    public static class PeriodTrend {
        public String periodId;
        public String periodName;
        public String shortName;
        public String startDate;
        public String endDate;
        public BigDecimal scope1Tonnes = BigDecimal.ZERO;
        public BigDecimal scope2LocationTonnes = BigDecimal.ZERO;
        public BigDecimal scope2MarketTonnes = BigDecimal.ZERO;
        public BigDecimal scope3Tonnes = BigDecimal.ZERO;
        public BigDecimal totalLocationBasedTonnes = BigDecimal.ZERO;
        public BigDecimal totalMarketBasedTonnes = BigDecimal.ZERO;

        public PeriodTrend() {}

        public PeriodTrend(String periodId, String periodName, String shortName, String startDate, String endDate) {
            this.periodId = periodId;
            this.periodName = periodName;
            this.shortName = shortName;
            this.startDate = startDate;
            this.endDate = endDate;
        }
    }

    public EmissionsTotals getEmissions() { return emissions; }
    public void setEmissions(EmissionsTotals emissions) { this.emissions = emissions; }

    public String getAuditStatus() { return auditStatus; }
    public void setAuditStatus(String auditStatus) { this.auditStatus = auditStatus; }

    public AuditHealth getAuditHealth() { return auditHealth; }
    public void setAuditHealth(AuditHealth auditHealth) { this.auditHealth = auditHealth; }

    public int getActivityCount() { return activityCount; }
    public void setActivityCount(int activityCount) { this.activityCount = activityCount; }

    public int getTargetsCount() { return targetsCount; }
    public void setTargetsCount(int targetsCount) { this.targetsCount = targetsCount; }

    public int getReductionProjectsCount() { return reductionProjectsCount; }
    public void setReductionProjectsCount(int reductionProjectsCount) { this.reductionProjectsCount = reductionProjectsCount; }

    public List<CategoryBreakdown> getCategories() { return categories; }
    public void setCategories(List<CategoryBreakdown> categories) { this.categories = categories; }

    public List<FacilitySummary> getFacilities() { return facilities; }
    public void setFacilities(List<FacilitySummary> facilities) { this.facilities = facilities; }

    public List<PeriodTrend> getPeriodTrends() { return periodTrends; }
    public void setPeriodTrends(List<PeriodTrend> periodTrends) { this.periodTrends = periodTrends; }
}
