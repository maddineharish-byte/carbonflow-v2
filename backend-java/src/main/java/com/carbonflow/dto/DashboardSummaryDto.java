package com.carbonflow.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class DashboardSummaryDto {
    private EmissionsTotals emissions;
    private AuditStatusSummary auditStatus;
    private AuditHealthSummary auditHealth;
    private List<CategoryBreakdown> categories;
    private List<FacilitySummary> facilities;
    private List<PeriodTrend> periodTrends;

    public static class PeriodTrend {
        public String periodId;
        public String periodName;
        public String shortName;
        public String startDate;
        public String endDate;
        public BigDecimal scope1Tonnes = BigDecimal.ZERO;
        public BigDecimal scope2LocationTonnes = BigDecimal.ZERO;
        public BigDecimal scope2MarketTonnes = BigDecimal.ZERO;
        public BigDecimal totalLocationBasedTonnes = BigDecimal.ZERO;
        public BigDecimal totalMarketBasedTonnes = BigDecimal.ZERO;

        public PeriodTrend() {}

        public PeriodTrend(String periodId, String periodName, String shortName, String startDate, String endDate,
                           BigDecimal s1, BigDecimal s2Loc, BigDecimal s2Mkt, BigDecimal totLoc, BigDecimal totMkt) {
            this.periodId = periodId;
            this.periodName = periodName;
            this.shortName = shortName;
            this.startDate = startDate;
            this.endDate = endDate;
            this.scope1Tonnes = s1;
            this.scope2LocationTonnes = s2Loc;
            this.scope2MarketTonnes = s2Mkt;
            this.totalLocationBasedTonnes = totLoc;
            this.totalMarketBasedTonnes = totMkt;
        }
    }

    public static class EmissionsTotals {
        public BigDecimal scope1 = BigDecimal.ZERO;
        public BigDecimal scope2Location = BigDecimal.ZERO;
        public BigDecimal scope2Market = BigDecimal.ZERO;
        public BigDecimal scope3 = BigDecimal.ZERO;
        public BigDecimal totalLocationBased = BigDecimal.ZERO;
        public BigDecimal totalMarketBased = BigDecimal.ZERO;
    }

    public static class AuditStatusSummary {
        public String status = "READY_FOR_VERIFICATION";
        public int checklistCompleted = 3;
        public int checklistTotal = 4;
        public int openFindings = 0;
        public boolean isVerificationReady = false;
    }

    public static class AuditHealthSummary {
        public int score = 98;
        public int totalRecords = 6;
        public int fullyEvidencedRecords = 6;
        public int verifiedCalculations = 6;
        public int unlinkedActivities = 0;
    }

    public static class CategoryBreakdown {
        public String name;
        public BigDecimal tonnes;

        public CategoryBreakdown(String name, BigDecimal tonnes) {
            this.name = name;
            this.tonnes = tonnes;
        }
    }

    public static class FacilitySummary {
        public String id;
        public String name;
        public String code;
        public BigDecimal scope1;
        public BigDecimal scope2;
        public BigDecimal total;

        public FacilitySummary(String id, String name, String code, BigDecimal scope1, BigDecimal scope2, BigDecimal total) {
            this.id = id;
            this.name = name;
            this.code = code;
            this.scope1 = scope1;
            this.scope2 = scope2;
            this.total = total;
        }
    }

    public EmissionsTotals getEmissions() { return emissions; }
    public void setEmissions(EmissionsTotals emissions) { this.emissions = emissions; }

    public AuditStatusSummary getAuditStatus() { return auditStatus; }
    public void setAuditStatus(AuditStatusSummary auditStatus) { this.auditStatus = auditStatus; }

    public AuditHealthSummary getAuditHealth() { return auditHealth; }
    public void setAuditHealth(AuditHealthSummary auditHealth) { this.auditHealth = auditHealth; }

    public List<CategoryBreakdown> getCategories() { return categories; }
    public void setCategories(List<CategoryBreakdown> categories) { this.categories = categories; }

    public List<FacilitySummary> getFacilities() { return facilities; }
    public void setFacilities(List<FacilitySummary> facilities) { this.facilities = facilities; }

    public List<PeriodTrend> getPeriodTrends() { return periodTrends; }
    public void setPeriodTrends(List<PeriodTrend> periodTrends) { this.periodTrends = periodTrends; }
}
