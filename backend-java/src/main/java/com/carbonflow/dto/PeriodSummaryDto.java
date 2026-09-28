package com.carbonflow.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reporting-period summary ({@code GET /analytics/periods/{periodId}/summary},
 * Phase 7 Workstream B): identity, ledger totals, source coverage and
 * governance state for one period, all derived from persisted rows.
 *
 * <p>Totals are aggregated in full precision and rounded to 4dp HALF_UP at the
 * presentation boundary (Node {@code toFixed(4)} parity with the emission
 * ledger summary). Scope 2 stays dual-reported: {@code scope2LocationTonnes}
 * and {@code scope2MarketTonnes} are separate perspectives that are never
 * summed together; the two {@code total*} fields are the per-basis totals.
 *
 * <p>{@code governance.locked} mirrors {@link
 * com.carbonflow.service.AccountingLockGuard} — the same lock signal that
 * protects accounting writes ({@code 409 AUDIT_LOCKED}); reporting reads are
 * always allowed, locked or not.
 */
public class PeriodSummaryDto {

    private String organizationId;
    private PeriodInfo period;
    private Totals totals;
    private Counts counts;
    private Coverage coverage;
    private Governance governance;

    /** The reporting period this summary describes. */
    public static class PeriodInfo {
        public String id;
        public String name;
        public String startDate;
        public String endDate;
        public String status;

        public PeriodInfo() {}

        public PeriodInfo(String id, String name, String startDate, String endDate, String status) {
            this.id = id;
            this.name = name;
            this.startDate = startDate;
            this.endDate = endDate;
            this.status = status;
        }
    }

    /** Emission totals for the period, both Scope 2 perspectives separate. */
    public static class Totals {
        public BigDecimal scope1Tonnes = BigDecimal.ZERO;
        public BigDecimal scope2LocationTonnes = BigDecimal.ZERO;
        public BigDecimal scope2MarketTonnes = BigDecimal.ZERO;
        public BigDecimal scope3Tonnes = BigDecimal.ZERO;
        public BigDecimal totalLocationBasedTonnes = BigDecimal.ZERO;
        public BigDecimal totalMarketBasedTonnes = BigDecimal.ZERO;
    }

    /** Source-record counts: activity rows, calculations and emission records. */
    public static class Counts {
        public int activityData;
        public int calculations;
        public int emissionRecords;
    }

    /**
     * Facility coverage: how many of the tenant's facilities reported
     * emissions in this period (records without a facility are counted in the
     * totals but not in the coverage numerator).
     */
    public static class Coverage {
        public int facilitiesTotal;
        public int facilitiesWithEmissions;
    }

    /**
     * Governance state: the accounting lock signal plus the period's current
     * audit (created_at DESC — the organization's most recent audit row for
     * this period), {@code null} audit fields when no audit exists.
     */
    public static class Governance {
        public boolean locked;
        public String auditId;
        public String auditStatus;
    }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public PeriodInfo getPeriod() { return period; }
    public void setPeriod(PeriodInfo period) { this.period = period; }

    public Totals getTotals() { return totals; }
    public void setTotals(Totals totals) { this.totals = totals; }

    public Counts getCounts() { return counts; }
    public void setCounts(Counts counts) { this.counts = counts; }

    public Coverage getCoverage() { return coverage; }
    public void setCoverage(Coverage coverage) { this.coverage = coverage; }

    public Governance getGovernance() { return governance; }
    public void setGovernance(Governance governance) { this.governance = governance; }
}
