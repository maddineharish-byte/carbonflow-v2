package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.service.ReportingPeriodService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reporting-period endpoints (docs/API.md §2.2; get/update are greenfield —
 * ADR-015). Node's list ({@code reporting_periods.read}, newest period
 * first) and create ({@code reporting_periods.create}, {@code 201},
 * {@code INVALID_DATE_RANGE} on an inverted range) semantics are preserved.
 *
 * <p>No {@code DELETE} mapping exists: the frozen permission matrix has no
 * {@code reporting_periods.delete} code, and period history is referenced by
 * later-phase tables with {@code ON DELETE RESTRICT} — the lifecycle answer
 * is {@code status}, not deletion (405 for the verb).
 */
@RestController
@RequestMapping("/api/v1/reporting-periods")
public class ReportingPeriodController {

    private final ReportingPeriodService reportingPeriodService;

    public ReportingPeriodController(ReportingPeriodService reportingPeriodService) {
        this.reportingPeriodService = reportingPeriodService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.read')")
    public ResponseEntity<ApiResponse<List<ReportingPeriod>>> getReportingPeriods() {
        return ResponseEntity.ok(ApiResponse.ok(
                reportingPeriodService.list(TenantContext.get().getOrganizationId())));
    }

    @GetMapping("/{periodId}")
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.read')")
    public ResponseEntity<ApiResponse<ReportingPeriod>> getReportingPeriod(
            @PathVariable String periodId) {
        return ResponseEntity.ok(ApiResponse.ok(
                reportingPeriodService.get(TenantContext.get().getOrganizationId(), periodId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.create')")
    public ResponseEntity<ApiResponse<ReportingPeriod>> createReportingPeriod(
            @RequestBody ScopeRequests.ReportingPeriodRequest request) {
        ReportingPeriod created =
                reportingPeriodService.create(TenantContext.get().getOrganizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Reporting period created."));
    }

    @PutMapping("/{periodId}")
    @PreAuthorize("hasAuthority('PERMISSION_reporting_periods.update')")
    public ResponseEntity<ApiResponse<ReportingPeriod>> updateReportingPeriod(
            @PathVariable String periodId,
            @RequestBody ScopeRequests.ReportingPeriodRequest request) {
        ReportingPeriod updated = reportingPeriodService.update(
                TenantContext.get().getOrganizationId(), periodId, request);
        return ResponseEntity.ok(ApiResponse.ok(updated, "Reporting period updated."));
    }
}
