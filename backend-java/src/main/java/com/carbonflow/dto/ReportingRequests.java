package com.carbonflow.dto;

import java.math.BigDecimal;

/**
 * Phase 7 request bodies (inventory snapshots, carbon targets, reduction
 * projects). Kept separate from {@link ScopeRequests} (Phase 3 scope
 * management) so each phase's wire contracts stay discoverable.
 */
public final class ReportingRequests {

    private ReportingRequests() {
    }

    /** {@code POST /inventory/snapshot} — Node parity: single field body. */
    public static class InventorySnapshotRequest {

        private String reportingPeriodId;

        public String getReportingPeriodId() {
            return reportingPeriodId;
        }

        public void setReportingPeriodId(String reportingPeriodId) {
            this.reportingPeriodId = reportingPeriodId;
        }
    }

    /**
     * {@code POST/PUT /targets} — Node parity fields plus {@code status} for
     * the greenfield PUT (create forces {@code ON_TRACK}, Node parity). Null
     * on PUT means "leave unchanged"; {@code notes} blank clears the notes.
     */
    public static class CarbonTargetRequest {

        private String name;
        private String baselinePeriodId;
        private String targetPeriodId;
        private BigDecimal baselineValueT;
        private BigDecimal targetValueT;
        private BigDecimal reductionPercentage;
        private String status;
        private String notes;

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

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    /**
     * {@code POST/PUT /reduction-projects} — Node parity fields. Null
     * numbers default to 0 on create (Node {@code Number(x || 0)} parity);
     * null on PUT means "leave unchanged". {@code description} blank and a
     * blank {@code facilityId}/{@code targetId} clear the respective field.
     */
    public static class ReductionProjectRequest {

        private String name;
        private String description;
        private String facilityId;
        private String targetId;
        private BigDecimal baselineT;
        private BigDecimal expectedReductionT;
        private BigDecimal actualReductionT;
        private String startDate;
        private String endDate;
        private String status;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getFacilityId() { return facilityId; }
        public void setFacilityId(String facilityId) { this.facilityId = facilityId; }

        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }

        public BigDecimal getBaselineT() { return baselineT; }
        public void setBaselineT(BigDecimal baselineT) { this.baselineT = baselineT; }

        public BigDecimal getExpectedReductionT() { return expectedReductionT; }
        public void setExpectedReductionT(BigDecimal expectedReductionT) { this.expectedReductionT = expectedReductionT; }

        public BigDecimal getActualReductionT() { return actualReductionT; }
        public void setActualReductionT(BigDecimal actualReductionT) { this.actualReductionT = actualReductionT; }

        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }

        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
