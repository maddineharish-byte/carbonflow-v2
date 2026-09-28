package com.carbonflow.service;

import com.carbonflow.dto.BreakdownDto;
import com.carbonflow.dto.DashboardSummaryDto;
import com.carbonflow.dto.PeriodSummaryDto;
import com.carbonflow.dto.TrendInsightsDto;
import com.carbonflow.model.AuditChecklistItem;
import com.carbonflow.model.CarbonAudit;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.model.Facility;
import com.carbonflow.model.LegalEntity;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;
import com.carbonflow.repository.ActivityDataRepository;
import com.carbonflow.repository.AuditRepository;
import com.carbonflow.repository.CalculationRepository;
import com.carbonflow.repository.CarbonTargetRepository;
import com.carbonflow.repository.ChecklistRepository;
import com.carbonflow.repository.EmissionRecordRepository;
import com.carbonflow.repository.FacilityRepository;
import com.carbonflow.repository.FindingRepository;
import com.carbonflow.repository.LegalEntityRepository;
import com.carbonflow.repository.ReductionProjectRepository;
import com.carbonflow.repository.ReportingPeriodRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repository-backed portfolio analytics (Phase 7, Workstreams A/B).
 *
 * <p><b>Accounting is the source of truth.</b> Every number here is aggregated
 * from persisted {@code emission_records} rows in full BigDecimal precision and
 * rounded only at the presentation boundary (2dp HALF_UP — Node parity
 * {@code toFixed(2)}). No second calculation engine, no demo/benchmark values,
 * no hardcoded geography, currency or timezone (ADR-018).
 *
 * <p><b>Scope 2 dual reporting (ADR-002):</b> location-based and market-based
 * perspectives are accumulated separately and never summed together; totals are
 * reported per basis side by side. The Node oracle's facility/category
 * breakdowns add both perspectives into one number (double-counting
 * dual-reported rows) — this service deliberately does not reproduce that
 * defect.
 *
 * <p><b>Determinism:</b> list orderings are explicitly sorted (categories by
 * tonnes desc then name; facilities by name then id; period trends by start
 * date); two identical databases always produce byte-identical payloads.
 */
@Service
public class AnalyticsService {

    /** Presentation rounding after aggregation (Node: {@code toFixed(2)}). */
    private static final int PRESENTATION_SCALE = 2;
    /** Trend window: the last {@value #TREND_WINDOW} reporting periods by start date. */
    private static final int TREND_WINDOW = 12;

    /**
     * Documented, deterministic thresholds for the computed trend insights
     * (ADR-018) — all derived values are compared against these constants, no
     * narrative content is ever invented.
     */
    static final BigDecimal ANOMALY_THRESHOLD_PCT = new BigDecimal("10");
    static final BigDecimal HIGH_SEVERITY_PCT = new BigDecimal("25");
    static final BigDecimal TRAJECTORY_BAND_PCT = new BigDecimal("1");
    static final BigDecimal VOLATILE_MIN_SWING_PCT = new BigDecimal("10");
    static final BigDecimal OPPORTUNITY_HIGH_TONNES = new BigDecimal("10");

    private static final Pattern PAREN_GROUP = Pattern.compile("\\(([^)]+)\\)");
    private static final String UNCATEGORIZED = "Uncategorized";

    private final EmissionRecordRepository emissionRecords;
    private final FacilityRepository facilityRepository;
    private final ReportingPeriodRepository reportingPeriods;
    private final AuditRepository auditRepository;
    private final ChecklistRepository checklistRepository;
    private final FindingRepository findingRepository;
    private final ActivityDataRepository activityDataRepository;
    private final CarbonTargetRepository carbonTargetRepository;
    private final ReductionProjectRepository reductionProjectRepository;
    private final CalculationRepository calculationRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final ScopeService scope;
    private final AccountingLockGuard lockGuard;

    public AnalyticsService(EmissionRecordRepository emissionRecords,
                            FacilityRepository facilityRepository,
                            ReportingPeriodRepository reportingPeriods,
                            AuditRepository auditRepository,
                            ChecklistRepository checklistRepository,
                            FindingRepository findingRepository,
                            ActivityDataRepository activityDataRepository,
                            CarbonTargetRepository carbonTargetRepository,
                            ReductionProjectRepository reductionProjectRepository,
                            CalculationRepository calculationRepository,
                            LegalEntityRepository legalEntityRepository,
                            ScopeService scope,
                            AccountingLockGuard lockGuard) {
        this.emissionRecords = emissionRecords;
        this.facilityRepository = facilityRepository;
        this.reportingPeriods = reportingPeriods;
        this.auditRepository = auditRepository;
        this.checklistRepository = checklistRepository;
        this.findingRepository = findingRepository;
        this.activityDataRepository = activityDataRepository;
        this.carbonTargetRepository = carbonTargetRepository;
        this.reductionProjectRepository = reductionProjectRepository;
        this.calculationRepository = calculationRepository;
        this.legalEntityRepository = legalEntityRepository;
        this.scope = scope;
        this.lockGuard = lockGuard;
    }

    // ================================================================
    // GET /analytics/dashboard
    // ================================================================

    public DashboardSummaryDto dashboard(String organizationId) {
        List<EmissionRecord> records = emissionRecords.list(organizationId, null, null, "ACTIVE", null);

        Basis total = new Basis();
        Map<String, Basis> byCategory = new HashMap<>();
        Map<String, Basis> byFacility = new HashMap<>();
        Map<String, Basis> byPeriod = new HashMap<>();
        for (EmissionRecord r : records) {
            total.add(r);
            String category = r.getCategory() == null ? UNCATEGORIZED : r.getCategory();
            byCategory.computeIfAbsent(category, k -> new Basis()).add(r);
            if (r.getFacilityId() != null) {
                byFacility.computeIfAbsent(r.getFacilityId(), k -> new Basis()).add(r);
            }
            if (r.getReportingPeriodId() != null) {
                byPeriod.computeIfAbsent(r.getReportingPeriodId(), k -> new Basis()).add(r);
            }
        }

        DashboardSummaryDto dto = new DashboardSummaryDto();

        DashboardSummaryDto.EmissionsTotals emissions = new DashboardSummaryDto.EmissionsTotals();
        emissions.scope1Tonnes = r2(total.scope1);
        emissions.scope2LocationTonnes = r2(total.location);
        emissions.scope2MarketTonnes = r2(total.market);
        emissions.scope3Tonnes = r2(total.scope3);
        emissions.totalLocationBasedTonnes = r2(total.locationBasedTotal());
        emissions.totalMarketBasedTonnes = r2(total.marketBasedTotal());
        dto.setEmissions(emissions);

        // Categories: tonnes = location perspective (charted), market additive.
        List<DashboardSummaryDto.CategoryBreakdown> categories = new ArrayList<>();
        for (Map.Entry<String, Basis> e : byCategory.entrySet()) {
            Basis b = e.getValue();
            categories.add(new DashboardSummaryDto.CategoryBreakdown(
                    e.getKey(), r2(b.locationBasedTotal()), r2(b.marketBasedTotal())));
        }
        categories.sort(Comparator
                .comparing((DashboardSummaryDto.CategoryBreakdown c) -> c.tonnes).reversed()
                .thenComparing(c -> c.category));
        dto.setCategories(categories);

        // Facilities: scope2Tonnes/totalTonnes = location perspective (see class javadoc).
        List<DashboardSummaryDto.FacilitySummary> facilities = new ArrayList<>();
        for (Facility fac : facilityRepository.list(organizationId)) {
            Basis b = byFacility.getOrDefault(fac.getId(), new Basis());
            DashboardSummaryDto.FacilitySummary fs =
                    new DashboardSummaryDto.FacilitySummary(fac.getId(), fac.getName(), fac.getFacilityCode());
            fs.scope1Tonnes = r2(b.scope1);
            fs.scope2LocationTonnes = r2(b.location);
            fs.scope2Tonnes = fs.scope2LocationTonnes;
            fs.scope2MarketTonnes = r2(b.market);
            fs.totalTonnes = r2(b.scope1.add(b.location));
            fs.totalMarketBasedTonnes = r2(b.scope1.add(b.market));
            facilities.add(fs);
        }
        facilities.sort(Comparator
                .comparing((DashboardSummaryDto.FacilitySummary f) -> f.name, Comparator.nullsLast(String::compareTo))
                .thenComparing(f -> f.id));
        dto.setFacilities(facilities);

        // Audit state: latest audit (list is created_at DESC), Node's 'DRAFT' default.
        List<CarbonAudit> audits = auditRepository.list(organizationId);
        DashboardSummaryDto.AuditHealth health = new DashboardSummaryDto.AuditHealth();
        if (audits.isEmpty()) {
            dto.setAuditStatus("DRAFT");
        } else {
            CarbonAudit current = audits.get(0);
            dto.setAuditStatus(current.getStatus());
            List<AuditChecklistItem> checklist =
                    checklistRepository.listByAudit(organizationId, current.getId());
            health.checklistTotal = checklist.size();
            health.checklistSatisfied = (int) checklist.stream().filter(AuditChecklistItem::isSatisfied).count();
            health.openFindingsCount = findingRepository.openCount(organizationId, current.getId());
        }
        dto.setAuditHealth(health);

        dto.setActivityCount(activityDataRepository.countByOrganization(organizationId));
        dto.setTargetsCount(carbonTargetRepository.count(organizationId));
        dto.setReductionProjectsCount(reductionProjectRepository.count(organizationId));

        // Real reporting periods only — last 12 by start date, no synthetic months,
        // no benchmark fallback when a period has zero emissions (ADR-018).
        List<ReportingPeriod> window = trendWindow(organizationId);
        List<DashboardSummaryDto.PeriodTrend> trends = new ArrayList<>();
        for (ReportingPeriod p : window) {
            Basis b = byPeriod.getOrDefault(p.getId(), new Basis());
            DashboardSummaryDto.PeriodTrend t = new DashboardSummaryDto.PeriodTrend(
                    p.getId(), p.getName(), shortName(p.getName()),
                    p.getStartDate() == null ? null : p.getStartDate().toString(),
                    p.getEndDate() == null ? null : p.getEndDate().toString());
            t.scope1Tonnes = r2(b.scope1);
            t.scope2LocationTonnes = r2(b.location);
            t.scope2MarketTonnes = r2(b.market);
            t.scope3Tonnes = r2(b.scope3);
            t.totalLocationBasedTonnes = r2(b.locationBasedTotal());
            t.totalMarketBasedTonnes = r2(b.marketBasedTotal());
            trends.add(t);
        }
        dto.setPeriodTrends(trends);

        return dto;
    }

    // ================================================================
    // POST /analytics/trend-insights
    // ================================================================

    /**
     * Deterministic trend insights computed from persisted accounting data
     * (ADR-018). The Node oracle sends the same computed trends to a Gemini LLM
     * for narrative generation; this service never calls an AI model and never
     * fabricates values — every figure in the payload is derived from
     * {@code emission_records}. Response shape is unchanged
     * ({@code TrendInsightsResponse} in {@code src/types.ts}).
     */
    public TrendInsightsDto trendInsights(String organizationId) {
        List<EmissionRecord> records = emissionRecords.list(organizationId, null, null, "ACTIVE", null);
        Map<String, Basis> byPeriod = new HashMap<>();
        for (EmissionRecord r : records) {
            if (r.getReportingPeriodId() != null) {
                byPeriod.computeIfAbsent(r.getReportingPeriodId(), k -> new Basis()).add(r);
            }
        }

        List<ReportingPeriod> window = trendWindow(organizationId);
        List<PeriodPoint> points = new ArrayList<>();
        for (ReportingPeriod p : window) {
            Basis b = byPeriod.getOrDefault(p.getId(), new Basis());
            points.add(new PeriodPoint(p, b));
        }
        List<PeriodPoint> withData = points.stream().filter(pt -> pt.basis.records > 0).toList();

        int totalOrgPeriods = reportingPeriods.list(organizationId).size();
        BigDecimal coverage = totalOrgPeriods == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(withData.size())
                        .divide(BigDecimal.valueOf(totalOrgPeriods), 2, RoundingMode.HALF_UP);

        if (withData.size() < 2) {
            return insufficientData(coverage, window, withData.size(), totalOrgPeriods);
        }

        PeriodPoint first = withData.get(0);
        PeriodPoint last = withData.get(withData.size() - 1);

        BigDecimal locDeltaPct = pctChange(first.basis.locationBasedTotal(), last.basis.locationBasedTotal());
        BigDecimal mktDeltaPct = pctChange(first.basis.marketBasedTotal(), last.basis.marketBasedTotal());
        BigDecimal s1DeltaPct = pctChange(first.basis.scope1, last.basis.scope1);

        String trajectory = trajectory(withData);

        String headline = headline(first, last, locDeltaPct, mktDeltaPct);
        String periodRange = first.period.getName() + " \u2013 " + last.period.getName();

        List<String> observations = keyObservations(records, first, last, s1DeltaPct, withData, points.size());
        TrendInsightsDto.Summary summary = new TrendInsightsDto.Summary(
                headline, trajectory, coverage, periodRange, observations);

        List<TrendInsightsDto.Anomaly> anomalies = anomalies(withData);
        List<TrendInsightsDto.ReductionOpportunity> opportunities =
                opportunities(records, withData);

        return new TrendInsightsDto(summary, anomalies, opportunities,
                Instant.now().toString(), "carbonflow-deterministic-analytics");
    }

    // ----------------------------------------------------------------
    // Trend-insights building blocks (all values computed, none invented)
    // ----------------------------------------------------------------

    private TrendInsightsDto insufficientData(BigDecimal coverage, List<ReportingPeriod> window,
                                              int withDataCount, int totalOrgPeriods) {
        String range;
        if (window.isEmpty()) {
            range = "No reporting periods";
        } else if (window.size() == 1) {
            range = window.get(0).getName();
        } else {
            range = window.get(0).getName() + " \u2013 " + window.get(window.size() - 1).getName();
        }
        TrendInsightsDto.Summary summary = new TrendInsightsDto.Summary(
                "Not enough persisted data: trend analysis requires at least two reporting periods with emissions.",
                "PLATEAUING",
                coverage,
                range,
                List.of("Periods with persisted emissions: " + withDataCount
                        + " of " + totalOrgPeriods + "."));
        return new TrendInsightsDto(summary, List.of(), List.of(),
                Instant.now().toString(), "carbonflow-deterministic-analytics");
    }

    private String trajectory(List<PeriodPoint> withData) {
        int signFlips = 0;
        BigDecimal maxSwing = BigDecimal.ZERO;
        Integer previousSign = null;
        for (int i = 1; i < withData.size(); i++) {
            BigDecimal delta = pctChange(withData.get(i - 1).basis.locationBasedTotal(),
                    withData.get(i).basis.locationBasedTotal());
            if (delta == null) {
                continue;
            }
            int sign = delta.signum();
            if (sign != 0) {
                if (previousSign != null && sign != previousSign) {
                    signFlips++;
                }
                previousSign = sign;
            }
            if (delta.abs().compareTo(maxSwing) > 0) {
                maxSwing = delta.abs();
            }
        }
        if (signFlips >= 2 && maxSwing.compareTo(VOLATILE_MIN_SWING_PCT) >= 0) {
            return "VOLATILE";
        }
        BigDecimal overall = pctChange(withData.get(0).basis.locationBasedTotal(),
                withData.get(withData.size() - 1).basis.locationBasedTotal());
        if (overall == null) {
            return "PLATEAUING";
        }
        if (overall.compareTo(TRAJECTORY_BAND_PCT.negate()) <= 0) {
            return "DECLINING";
        }
        if (overall.compareTo(TRAJECTORY_BAND_PCT) >= 0) {
            return "INCREASING";
        }
        return "PLATEAUING";
    }

    private String headline(PeriodPoint first, PeriodPoint last,
                            BigDecimal locDeltaPct, BigDecimal mktDeltaPct) {
        return "Location-basis emissions " + movement(locDeltaPct)
                + " from " + first.period.getName() + " to " + last.period.getName()
                + "; market-basis emissions " + movement(mktDeltaPct) + ".";
    }

    private String movement(BigDecimal deltaPct) {
        if (deltaPct == null) {
            return "have no comparable baseline";
        }
        String pct = deltaPct.setScale(1, RoundingMode.HALF_UP).toPlainString();
        if (deltaPct.compareTo(BigDecimal.ZERO) < 0) {
            return "fell " + pct + "%";
        }
        if (deltaPct.compareTo(BigDecimal.ZERO) > 0) {
            return "rose " + pct + "%";
        }
        return "were unchanged";
    }

    private List<String> keyObservations(List<EmissionRecord> records, PeriodPoint first, PeriodPoint last,
                                         BigDecimal s1DeltaPct, List<PeriodPoint> withData, int windowSize) {
        List<String> observations = new ArrayList<>();

        observations.add("Scope 1: " + fmt(first.basis.scope1) + " tCO2e to " + fmt(last.basis.scope1)
                + " tCO2e (" + pctText(s1DeltaPct) + ") across the analysis window.");

        BigDecimal locDelta = last.basis.locationBasedTotal().subtract(last.basis.marketBasedTotal());
        observations.add("Scope 2 location-market delta in " + last.period.getName() + ": "
                + fmt(locDelta) + " tCO2e (location " + fmt(last.basis.locationBasedTotal())
                + " t, market " + fmt(last.basis.marketBasedTotal()) + " t).");

        Map<String, BigDecimal> categoryTotals = new HashMap<>();
        for (EmissionRecord r : records) {
            BigDecimal value = locationPerspective(r);
            String category = r.getCategory() == null ? UNCATEGORIZED : r.getCategory();
            categoryTotals.merge(category, value, BigDecimal::add);
        }
        categoryTotals.entrySet().stream()
                .max(Map.Entry.<String, BigDecimal>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .ifPresent(top -> observations.add("Top category by location-based emissions: "
                        + top.getKey() + " (" + fmt(top.getValue()) + " tCO2e)."));

        observations.add("Periods with persisted emissions: " + withData.size() + " of "
                + windowSize + " in the analysis window.");

        return observations;
    }

    private List<TrendInsightsDto.Anomaly> anomalies(List<PeriodPoint> withData) {
        List<TrendInsightsDto.Anomaly> anomalies = new ArrayList<>();
        for (int i = 1; i < withData.size() && anomalies.size() < 5; i++) {
            PeriodPoint prev = withData.get(i - 1);
            PeriodPoint cur = withData.get(i);
            int index = anomalies.size() + 1;

            BigDecimal prevLoc = prev.basis.locationBasedTotal();
            BigDecimal prevMkt = prev.basis.marketBasedTotal();
            BigDecimal dLoc = pctChange(prevLoc, cur.basis.locationBasedTotal());
            BigDecimal dMkt = pctChange(prevMkt, cur.basis.marketBasedTotal());

            boolean locHasBaseline = prevLoc.signum() > 0;
            boolean mktHasBaseline = prevMkt.signum() > 0;

            // DIVERGENCE takes precedence when both perspectives moved in
            // opposite directions beyond the threshold in the same pair.
            if (locHasBaseline && mktHasBaseline && dLoc != null && dMkt != null
                    && dLoc.signum() != dMkt.signum()
                    && dLoc.abs().compareTo(ANOMALY_THRESHOLD_PCT) >= 0
                    && dMkt.abs().compareTo(ANOMALY_THRESHOLD_PCT) >= 0) {
                BigDecimal shift = cur.basis.locationBasedTotal().subtract(cur.basis.marketBasedTotal())
                        .subtract(prevLoc.subtract(prevMkt));
                anomalies.add(new TrendInsightsDto.Anomaly(
                        "anom-" + index,
                        "DIVERGENCE",
                        "HIGH",
                        "Location and market bases diverged in " + cur.period.getName(),
                        "Between " + prev.period.getName() + " and " + cur.period.getName()
                                + " the location-based total changed " + pctText(dLoc)
                                + " while the market-based total changed " + pctText(dMkt)
                                + " (location " + fmt(prevLoc) + " to " + fmt(cur.basis.locationBasedTotal())
                                + " tCO2e, market " + fmt(prevMkt) + " to "
                                + fmt(cur.basis.marketBasedTotal()) + " tCO2e).",
                        prev.period.getName() + " \u2192 " + cur.period.getName(),
                        "Scope 2 (dual reporting)",
                        fmt(shift) + " tCO2e change in the location-market delta"));
                continue;
            }

            if (!locHasBaseline || dLoc == null || dLoc.abs().compareTo(ANOMALY_THRESHOLD_PCT) < 0) {
                continue;
            }
            boolean rising = dLoc.signum() > 0;
            String type = rising ? "SPIKE" : "UNUSUAL_PATTERN";
            String severity = dLoc.abs().compareTo(HIGH_SEVERITY_PCT) >= 0 ? "HIGH" : "MEDIUM";
            BigDecimal absolute = cur.basis.locationBasedTotal().subtract(prevLoc);
            anomalies.add(new TrendInsightsDto.Anomaly(
                    "anom-" + index,
                    type,
                    severity,
                    (rising ? "Location-basis total rose " : "Location-basis total fell ")
                            + dLoc.setScale(1, RoundingMode.HALF_UP).toPlainString()
                            + "% in " + cur.period.getName(),
                    "The location-based total (Scope 1 + Scope 2 location + Scope 3) changed from "
                            + fmt(prevLoc) + " tCO2e in " + prev.period.getName() + " to "
                            + fmt(cur.basis.locationBasedTotal()) + " tCO2e in " + cur.period.getName()
                            + " (" + fmt(absolute) + " tCO2e).",
                    prev.period.getName() + " \u2192 " + cur.period.getName(),
                    "Scope 1 + Scope 2 (location basis)",
                    fmt(absolute) + " tCO2e"));
        }
        return anomalies;
    }

    private List<TrendInsightsDto.ReductionOpportunity> opportunities(List<EmissionRecord> records,
                                                                      List<PeriodPoint> withData) {
        PeriodPoint prev = withData.get(withData.size() - 2);
        PeriodPoint cur = withData.get(withData.size() - 1);

        Map<String, BigDecimal> prevByCategory = new HashMap<>();
        Map<String, BigDecimal> curByCategory = new HashMap<>();
        for (EmissionRecord r : records) {
            String periodId = r.getReportingPeriodId();
            if (periodId == null) {
                continue;
            }
            String category = r.getCategory() == null ? UNCATEGORIZED : r.getCategory();
            if (periodId.equals(prev.period.getId())) {
                prevByCategory.merge(category, locationPerspective(r), BigDecimal::add);
            } else if (periodId.equals(cur.period.getId())) {
                curByCategory.merge(category, locationPerspective(r), BigDecimal::add);
            }
        }

        record Increase(String category, BigDecimal delta) {}
        List<Increase> increases = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : curByCategory.entrySet()) {
            BigDecimal before = prevByCategory.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal delta = e.getValue().subtract(before);
            if (delta.signum() > 0) {
                increases.add(new Increase(e.getKey(), delta));
            }
        }
        increases.sort(Comparator.comparing(Increase::delta).reversed()
                .thenComparing(Increase::category));

        List<TrendInsightsDto.ReductionOpportunity> opportunities = new ArrayList<>();
        int rank = 1;
        for (Increase increase : increases.subList(0, Math.min(3, increases.size()))) {
            BigDecimal before = prevByCategory.getOrDefault(increase.category(), BigDecimal.ZERO);
            BigDecimal now = curByCategory.get(increase.category());
            opportunities.add(new TrendInsightsDto.ReductionOpportunity(
                    "opp-" + rank++,
                    opportunityCategory(increase.category()),
                    increase.delta().compareTo(OPPORTUNITY_HIGH_TONNES) >= 0 ? "HIGH" : "MEDIUM",
                    increase.category() + ": +" + fmt(increase.delta())
                            + " tCO2e period-over-period",
                    "Persisted " + increase.category() + " emissions rose from " + fmt(before)
                            + " tCO2e in " + prev.period.getName() + " to " + fmt(now)
                            + " tCO2e in " + cur.period.getName()
                            + ". Reversing this observed increase would return the category to its "
                            + prev.period.getName() + " level; no efficiency assumption is applied.",
                    r2(increase.delta()),
                    "Not assessed",
                    "MEDIUM",
                    "Reduction potential must be validated against the organization's own activity data "
                            + "and applicable GHG Protocol guidance; payback and feasibility are not "
                            + "derived from accounting data."));
        }
        return opportunities;
    }

    /** Keyword mapping from the persisted emission category to the frozen UI enum. */
    static String opportunityCategory(String emissionCategory) {
        String c = emissionCategory.toLowerCase();
        if (c.contains("upstream") || c.contains("downstream") || c.contains("supply")
                || c.contains("waste") || c.contains("purchased goods") || c.contains("business travel")
                || c.contains("services")) {
            return "SUPPLY_CHAIN";
        }
        if (c.contains("transport") || c.contains("fleet") || c.contains("mobile")
                || c.contains("distribution") || c.contains("shipping")) {
            return "FLEET_ELECTRIFICATION";
        }
        if (c.contains("electricity") || c.contains("grid") || c.contains("renewable")
                || c.contains("purchased power")) {
            return "RENEWABLE_PROCUREMENT";
        }
        if (c.contains("fuel") || c.contains("energy") || c.contains("stationary")
                || c.contains("combustion") || c.contains("refrigerant") || c.contains("fugitive")) {
            return "ENERGY_EFFICIENCY";
        }
        return "PROCESS_OPTIMIZATION";
    }

    // ================================================================
    // GET /analytics/periods/{periodId}/summary
    // ================================================================

    /**
     * Reporting-period summary (Phase 7 Workstream B): identity, 4dp ledger
     * totals, source-record counts, facility coverage and governance state —
     * all resolved through the tenant-scoped {@link ScopeService} choke point,
     * so malformed, unknown and cross-tenant period ids are indistinguishable
     * ({@code 404 REPORTING_PERIOD_NOT_FOUND}).
     */
    public PeriodSummaryDto periodSummary(String organizationId, String periodId) {
        ReportingPeriod period = scope.requireReportingPeriod(organizationId, periodId);
        List<EmissionRecord> records =
                emissionRecords.list(organizationId, period.getId(), null, "ACTIVE", null);

        Basis totals = new Basis();
        Set<String> facilitiesWithEmissions = new HashSet<>();
        for (EmissionRecord r : records) {
            totals.add(r);
            if (r.getFacilityId() != null) {
                facilitiesWithEmissions.add(r.getFacilityId());
            }
        }

        PeriodSummaryDto dto = new PeriodSummaryDto();
        dto.setOrganizationId(organizationId);
        dto.setPeriod(new PeriodSummaryDto.PeriodInfo(period.getId(), period.getName(),
                period.getStartDate() == null ? null : period.getStartDate().toString(),
                period.getEndDate() == null ? null : period.getEndDate().toString(),
                period.getStatus()));

        PeriodSummaryDto.Totals t = new PeriodSummaryDto.Totals();
        t.scope1Tonnes = r4(totals.scope1);
        t.scope2LocationTonnes = r4(totals.location);
        t.scope2MarketTonnes = r4(totals.market);
        t.scope3Tonnes = r4(totals.scope3);
        t.totalLocationBasedTonnes = r4(totals.locationBasedTotal());
        t.totalMarketBasedTonnes = r4(totals.marketBasedTotal());
        dto.setTotals(t);

        PeriodSummaryDto.Counts counts = new PeriodSummaryDto.Counts();
        counts.activityData = activityDataRepository.countForPeriod(organizationId, period.getId());
        counts.calculations = calculationRepository.countForPeriod(organizationId, period.getId());
        counts.emissionRecords = records.size();
        dto.setCounts(counts);

        PeriodSummaryDto.Coverage coverage = new PeriodSummaryDto.Coverage();
        coverage.facilitiesTotal = facilityRepository.list(organizationId).size();
        coverage.facilitiesWithEmissions = facilitiesWithEmissions.size();
        dto.setCoverage(coverage);

        PeriodSummaryDto.Governance governance = new PeriodSummaryDto.Governance();
        governance.locked = lockGuard.isLocked(organizationId, period.getId());
        for (CarbonAudit audit : auditRepository.list(organizationId)) { // created_at DESC
            if (period.getId().equals(audit.getReportingPeriodId())) {
                governance.auditId = audit.getId();
                governance.auditStatus = audit.getStatus();
                break;
            }
        }
        dto.setGovernance(governance);
        return dto;
    }

    // ================================================================
    // GET /analytics/breakdown
    // ================================================================

    /** Documented dimension set (API.md §2.9 + Phase 7 Workstream A). */
    static final Set<String> BREAKDOWN_DIMENSIONS =
            Set.of("facility", "legal_entity", "scope", "category", "period");

    /**
     * Dimension breakdown over persisted records. {@code periodId} (optional)
     * is tenant-resolved through {@link ScopeService} — a malformed or foreign
     * value answers 404, never 500 or a leak. {@code dimension=period} rejects
     * {@code periodId} explicitly (it already spans all periods) rather than
     * silently ignoring it.
     */
    public BreakdownDto breakdown(String organizationId, String dimension, String periodId) {
        if (dimension == null || dimension.isBlank()) {
            throw new AuthException("VALIDATION_ERROR",
                    "Breakdown dimension is required.", HttpStatus.BAD_REQUEST);
        }
        String dim = dimension.trim().toLowerCase(Locale.ROOT);
        if (!BREAKDOWN_DIMENSIONS.contains(dim)) {
            throw new AuthException("VALIDATION_ERROR",
                    "Breakdown dimension must be one of: facility, legal_entity, scope, "
                            + "category, period.", HttpStatus.BAD_REQUEST);
        }
        boolean periodSupplied = periodId != null && !periodId.isBlank();
        if ("period".equals(dim) && periodSupplied) {
            throw new AuthException("VALIDATION_ERROR",
                    "periodId is not supported for dimension=period.", HttpStatus.BAD_REQUEST);
        }
        String filterPeriodId = periodSupplied
                ? scope.requireReportingPeriod(organizationId, periodId.trim()).getId()
                : null;
        List<EmissionRecord> records =
                emissionRecords.list(organizationId, filterPeriodId, null, "ACTIVE", null);

        Map<String, Basis> byKey = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        List<String> orderedKeys = new ArrayList<>();

        switch (dim) {
            case "facility" -> {
                List<Facility> facilities = new ArrayList<>(facilityRepository.list(organizationId));
                Map<String, Facility> byId = new HashMap<>();
                for (Facility f : facilities) {
                    byId.put(f.getId(), f);
                    byKey.put(f.getId(), new Basis());
                    labels.put(f.getId(), f.getName());
                }
                labels.put("", "No facility");
                for (EmissionRecord r : records) {
                    String key = r.getFacilityId() == null ? "" : r.getFacilityId();
                    if (!byId.containsKey(key) && !key.isEmpty()) {
                        labels.putIfAbsent(key, key); // defensive: FK guarantees this is rare
                    }
                    byKey.computeIfAbsent(key, k -> new Basis()).add(r);
                }
                facilities.sort(Comparator
                        .comparing((Facility f) -> f.getName(), Comparator.nullsLast(String::compareTo))
                        .thenComparing(Facility::getId));
                facilities.forEach(f -> orderedKeys.add(f.getId()));
                if (byKey.containsKey("")) {
                    orderedKeys.add("");
                }
            }
            case "legal_entity" -> {
                List<LegalEntity> entities = legalEntityRepository.list(organizationId); // ORDER BY name
                Map<String, Facility> facilitiesById = new HashMap<>();
                facilityRepository.list(organizationId)
                        .forEach(f -> facilitiesById.put(f.getId(), f));
                for (LegalEntity e : entities) {
                    byKey.put(e.getId(), new Basis());
                    labels.put(e.getId(), e.getName());
                }
                labels.put("", "No legal entity");
                for (EmissionRecord r : records) {
                    Facility f = r.getFacilityId() == null ? null : facilitiesById.get(r.getFacilityId());
                    String key = (f == null || f.getLegalEntityId() == null) ? "" : f.getLegalEntityId();
                    if (!byKey.containsKey(key) && !key.isEmpty()) {
                        labels.putIfAbsent(key, key);
                    }
                    byKey.computeIfAbsent(key, k -> new Basis()).add(r);
                }
                entities.forEach(e -> orderedKeys.add(e.getId()));
                if (byKey.containsKey("")) {
                    orderedKeys.add("");
                }
            }
            case "scope" -> {
                Map<String, String> scopeLabels = Map.of(
                        "SCOPE_1", "Scope 1", "SCOPE_2", "Scope 2", "SCOPE_3", "Scope 3");
                for (EmissionRecord r : records) {
                    String key = r.getScope() == null ? "UNKNOWN" : r.getScope().name();
                    labels.putIfAbsent(key, scopeLabels.getOrDefault(key, key));
                    byKey.computeIfAbsent(key, k -> new Basis()).add(r);
                }
                for (String key : List.of("SCOPE_1", "SCOPE_2", "SCOPE_3")) {
                    if (byKey.containsKey(key)) {
                        orderedKeys.add(key);
                    }
                }
                orderedKeys.addAll(labels.keySet().stream()
                        .filter(k -> !Set.of("SCOPE_1", "SCOPE_2", "SCOPE_3").contains(k))
                        .sorted().toList());
            }
            case "category" -> {
                for (EmissionRecord r : records) {
                    String key = r.getCategory() == null ? UNCATEGORIZED : r.getCategory();
                    labels.putIfAbsent(key, key);
                    byKey.computeIfAbsent(key, k -> new Basis()).add(r);
                }
                // deterministic order: location-basis tonnes desc, then name asc
                byKey.keySet().stream()
                        .sorted(Comparator.comparing((String k) -> r2(byKey.get(k).locationBasedTotal()))
                                .reversed()
                                .thenComparing(k -> labels.getOrDefault(k, k)))
                        .forEach(orderedKeys::add);
            }
            default -> { // period: every tenant period in start-date order (zeros included)
                List<ReportingPeriod> periods = new ArrayList<>(reportingPeriods.list(organizationId));
                periods.sort(Comparator
                        .comparing(ReportingPeriod::getStartDate,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ReportingPeriod::getId));
                for (ReportingPeriod p : periods) {
                    byKey.put(p.getId(), new Basis());
                    labels.put(p.getId(), p.getName());
                    orderedKeys.add(p.getId());
                }
                for (EmissionRecord r : records) {
                    if (r.getReportingPeriodId() == null) {
                        continue;
                    }
                    labels.putIfAbsent(r.getReportingPeriodId(), r.getReportingPeriodId());
                    byKey.computeIfAbsent(r.getReportingPeriodId(), k -> new Basis()).add(r);
                }
            }
        }

        List<BreakdownDto.Row> rows = new ArrayList<>();
        for (String key : orderedKeys) {
            Basis b = byKey.get(key);
            if (b == null) {
                continue;
            }
            BreakdownDto.Row row = new BreakdownDto.Row(key, labels.getOrDefault(key, key));
            row.scope1Tonnes = r2(b.scope1);
            row.scope2LocationTonnes = r2(b.location);
            row.scope2MarketTonnes = r2(b.market);
            row.scope3Tonnes = r2(b.scope3);
            row.totalLocationBasedTonnes = r2(b.locationBasedTotal());
            row.totalMarketBasedTonnes = r2(b.marketBasedTotal());
            rows.add(row);
        }

        BreakdownDto dto = new BreakdownDto();
        dto.setDimension(dim);
        dto.setPeriodId(filterPeriodId);
        dto.setRows(rows);
        return dto;
    }

    // ================================================================
    // Shared helpers
    // ================================================================

    /** The last {@value #TREND_WINDOW} reporting periods by start date (id tiebreak). */
    private List<ReportingPeriod> trendWindow(String organizationId) {
        List<ReportingPeriod> periods = new ArrayList<>(reportingPeriods.list(organizationId));
        periods.sort(Comparator.comparing(ReportingPeriod::getStartDate,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ReportingPeriod::getId));
        if (periods.size() > TREND_WINDOW) {
            return new ArrayList<>(periods.subList(periods.size() - TREND_WINDOW, periods.size()));
        }
        return periods;
    }

    /** Node's short-label rule: parenthesised group, else first 10 characters. */
    static String shortName(String periodName) {
        if (periodName == null) {
            return "";
        }
        Matcher matcher = PAREN_GROUP.matcher(periodName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return periodName.length() > 10 ? periodName.substring(0, 10) : periodName;
    }

    /** Percent change {@code (cur - prev) / prev * 100}; {@code null} when prev is zero. */
    static BigDecimal pctChange(BigDecimal prev, BigDecimal cur) {
        if (prev == null || cur == null || prev.signum() == 0) {
            return null;
        }
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100))
                .divide(prev, 4, RoundingMode.HALF_UP);
    }

    /** Location-perspective contribution of a single record (never mixes bases). */
    private static BigDecimal locationPerspective(EmissionRecord r) {
        if (r.getScope() == GHGScope.SCOPE_2 && r.getScope2Type() == Scope2Method.MARKET_BASED) {
            return BigDecimal.ZERO;
        }
        return r.getCo2eTonnes();
    }

    private static BigDecimal r2(BigDecimal value) {
        return value.setScale(PRESENTATION_SCALE, RoundingMode.HALF_UP);
    }

    /** Period-summary scale (4dp) — matches the emission ledger summary. */
    private static final int SUMMARY_SCALE = 4;

    private static BigDecimal r4(BigDecimal value) {
        return value.setScale(SUMMARY_SCALE, RoundingMode.HALF_UP);
    }

    private static String fmt(BigDecimal value) {
        return value.setScale(PRESENTATION_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pctText(BigDecimal pct) {
        if (pct == null) {
            return "no comparable baseline";
        }
        String sign = pct.signum() > 0 ? "+" : "";
        return sign + pct.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /**
     * Full-precision accumulator for one aggregation dimension. Package-visible
     * on purpose: {@code CarbonTargetService} reuses it for target progress so
     * the bucketing rules (dual Scope 2, Scope 3 included in both totals)
     * exist exactly once across reporting.
     */
    static final class Basis {
        BigDecimal scope1 = BigDecimal.ZERO;
        BigDecimal location = BigDecimal.ZERO;
        BigDecimal market = BigDecimal.ZERO;
        BigDecimal scope3 = BigDecimal.ZERO;
        int records;

        void add(EmissionRecord r) {
            BigDecimal tonnes = r.getCo2eTonnes();
            records++;
            if (r.getScope() == GHGScope.SCOPE_1) {
                scope1 = scope1.add(tonnes);
            } else if (r.getScope() == GHGScope.SCOPE_2) {
                if (r.getScope2Type() == Scope2Method.LOCATION_BASED) {
                    location = location.add(tonnes);
                } else if (r.getScope2Type() == Scope2Method.MARKET_BASED) {
                    market = market.add(tonnes);
                }
            } else if (r.getScope() == GHGScope.SCOPE_3) {
                scope3 = scope3.add(tonnes);
            }
        }

        /** Scope 1 + Scope 2 location + Scope 3 — the location-basis total. */
        BigDecimal locationBasedTotal() {
            return scope1.add(location).add(scope3);
        }

        /** Scope 1 + Scope 2 market + Scope 3 — the market-basis total. */
        BigDecimal marketBasedTotal() {
            return scope1.add(market).add(scope3);
        }
    }

    /** One reporting period with its aggregation basis. */
    private record PeriodPoint(ReportingPeriod period, Basis basis) {}
}
