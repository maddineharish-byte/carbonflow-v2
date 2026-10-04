package com.carbonflow.perf;

/**
 * The three controlled synthetic dataset scales evaluated in Phase 10.10.
 *
 * <h2>Why these three and not a continuous sweep</h2>
 * <p>A performance phase needs <em>repeatable, documented</em> data points, not a
 * continuum of guesses. Each scale is a complete, internally consistent tenant
 * that exercises every measured code path: tenant-filtered list reads, Scope 2
 * dual reporting, period/facility/category aggregation, CSV export, evidence
 * reads and the analytics aggregations. The cardinalities are declared here as
 * constants so a reader can reproduce the exact row counts without reading the
 * seeding code, and the harness additionally records the counts PostgreSQL
 * actually holds after seeding — the declared numbers are <em>asserted against
 * the database</em>, not merely claimed.
 *
 * <h2>Shape of the data</h2>
 * <p>The shape is chosen to mirror the shape a real multi-facility corporate
 * inventory has, because performance depends on shape and not only on volume:
 * <ul>
 *   <li>reporting periods are real calendar periods (quarterly for SMALL,
 *       annual for MEDIUM and LARGE) so the trend window logic has genuine
 *       periods to rank and truncate;</li>
 *   <li>Scope 2 activities produce <em>two</em> emission records each (one
 *       {@code LOCATION_BASED}, one {@code MARKET_BASED}) because CarbonFlow
 *       dual-reports Scope 2 and stores the perspectives as separate rows
 *       (ADR-002). That is why {@code emissionRecords} exceeds
 *       {@code activityData};</li>
 *   <li>activities are spread evenly across every period and facility, so no
 *       single period or facility holds a disproportionate share. Lopsided data
 *       would flatter the aggregation queries and hide the sort cost.</li>
 * </ul>
 *
 * <h2>Scale semantics</h2>
 * <p>These are <b>not</b> production capacity claims. They are three points on a
 * curve measured on one developer laptop; see the Phase 10.10 report for the
 * host, the PostgreSQL build and the limitations that bound them.
 */
public enum PerfScale {

    /** Small: a single-site tenant, a few periods, a few thousand ledger rows. */
    SMALL("small", 1, 5, 10, 4, 1_000, 1_400, 20, 1, 8, 5, 10, 2, 3),

    /**
     * Medium: a mid-size multi-site tenant, one annual period per year over a
     * decade, tens of thousands of ledger rows — the shape where aggregation
     * starts to dominate request latency.
     */
    MEDIUM("medium", 3, 50, 200, 12, 20_000, 28_000, 500, 4, 32, 100, 400, 10, 30),

    /**
     * Large: a large multi-site tenant, a quarter-century of periods, six-figure
     * ledger-row volume — the point at which unbounded list reads are expected
     * to stop being free.
     */
    LARGE("large", 10, 250, 1_000, 24, 100_000, 140_000, 2_000, 12, 96, 500, 2_000, 40, 150);

    /** Short label used in report tables and artefact file names. */
    public final String label;

    /** Legal entities in the tenant. */
    public final int legalEntities;

    /** Facilities in the tenant. */
    public final int facilities;

    /** Departments in the tenant (>= facilities; at most one per facility here). */
    public final int departments;

    /** Reporting periods in the tenant. */
    public final int reportingPeriods;

    /** Activity data rows. */
    public final int activityData;

    /**
     * Emission ledger rows. Derived, not independent: one per activity, plus a
     * second row for the market perspective of every third activity (Scope 2).
     */
    public final int emissionRecords;

    /** Evidence records (vault files are represented by their metadata rows). */
    public final int evidenceRecords;

    /** Carbon audits. */
    public final int audits;

    /** Checklist items across all audits. */
    public final int checklistItems;

    /** Review findings across all audits. */
    public final int reviewFindings;

    /** Review comments across all audits. */
    public final int reviewComments;

    /** Reduction targets. */
    public final int targets;

    /** Reduction projects. */
    public final int reductionProjects;

    PerfScale(String label, int legalEntities, int facilities, int departments,
              int reportingPeriods, int activityData, int emissionRecords,
              int evidenceRecords, int audits, int checklistItems, int reviewFindings,
              int reviewComments, int targets, int reductionProjects) {
        this.label = label;
        this.legalEntities = legalEntities;
        this.facilities = facilities;
        this.departments = departments;
        this.reportingPeriods = reportingPeriods;
        this.activityData = activityData;
        this.emissionRecords = emissionRecords;
        this.evidenceRecords = evidenceRecords;
        this.audits = audits;
        this.checklistItems = checklistItems;
        this.reviewFindings = reviewFindings;
        this.reviewComments = reviewComments;
        this.targets = targets;
        this.reductionProjects = reductionProjects;
    }

    /** Calculations: exactly one per activity (a re-calculation supersedes rather than adds). */
    public int calculations() {
        return activityData;
    }

    /** Gas-level calculation results: CO2 plus N2O on a third of rows, plus CH4 on a third. */
    public int calculationGasResults() {
        return activityData + (activityData / 3) + (activityData / 3);
    }
}
