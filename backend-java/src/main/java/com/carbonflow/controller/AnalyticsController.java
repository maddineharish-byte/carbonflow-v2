package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.BreakdownDto;
import com.carbonflow.dto.DashboardSummaryDto;
import com.carbonflow.dto.PeriodSummaryDto;
import com.carbonflow.dto.TrendInsightsDto;
import com.carbonflow.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard reads (Phase 7 moved the aggregation into {@link AnalyticsService}).
 *
 * <p>Both endpoints are tenant-scoped: the organization id comes exclusively
 * from the authenticated {@link TenantContext}, never from the request. All
 * values are aggregated from the persisted emission ledger — Scope 2 stays
 * dual-reported end to end and location/market perspectives are never summed
 * together (ADR-002 / ADR-018).
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('PERMISSION_analytics.read')")
    public ResponseEntity<ApiResponse<DashboardSummaryDto>> getDashboardSummary() {
        TenantContext ctx = TenantContext.get();
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.dashboard(ctx.getOrganizationId())));
    }

    @PostMapping("/trend-insights")
    @PreAuthorize("hasAuthority('PERMISSION_analytics.read')")
    public ResponseEntity<ApiResponse<TrendInsightsDto>> getTrendInsights() {
        TenantContext ctx = TenantContext.get();
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.trendInsights(ctx.getOrganizationId())));
    }

    /** Phase 7 Workstream B — reporting-period summary (4dp, both bases). */
    @GetMapping("/periods/{periodId}/summary")
    @PreAuthorize("hasAuthority('PERMISSION_analytics.read')")
    public ResponseEntity<ApiResponse<PeriodSummaryDto>> getPeriodSummary(
            @PathVariable String periodId) {
        TenantContext ctx = TenantContext.get();
        return ResponseEntity.ok(ApiResponse.ok(
                analyticsService.periodSummary(ctx.getOrganizationId(), periodId)));
    }

    /** Phase 7 Workstream A — dimension breakdown (API.md §2.9 contract). */
    @GetMapping("/breakdown")
    @PreAuthorize("hasAuthority('PERMISSION_analytics.read')")
    public ResponseEntity<ApiResponse<BreakdownDto>> getBreakdown(
            @RequestParam(name = "dimension", required = false) String dimension,
            @RequestParam(name = "periodId", required = false) String periodId) {
        TenantContext ctx = TenantContext.get();
        return ResponseEntity.ok(ApiResponse.ok(
                analyticsService.breakdown(ctx.getOrganizationId(), dimension, periodId)));
    }
}
