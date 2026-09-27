package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.model.ActivityData;
import com.carbonflow.model.Calculation;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.model.Facility;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.repository.ActivityDataRepository;
import com.carbonflow.repository.CalculationRepository;
import com.carbonflow.repository.EmissionRecordRepository;
import com.carbonflow.repository.FacilityRepository;
import com.carbonflow.repository.ReportingPeriodRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV export of the emission inventory — composed from the PostgreSQL ledger
 * (Phase 6 swapped the prototype in-memory maps for the repositories; the CSV
 * columns are unchanged).
 *
 * <p>Only {@code ACTIVE} records are exported, per row the audit trail joins
 * its period, facility, calculation snapshot (hash, original quantity/unit)
 * and activity type — the provenance an assurance pack needs.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportsController {

    private final EmissionRecordRepository emissionRecords;
    private final FacilityRepository facilityRepository;
    private final ReportingPeriodRepository reportingPeriodRepository;
    private final CalculationRepository calculationRepository;
    private final ActivityDataRepository activityRepository;

    public ReportsController(EmissionRecordRepository emissionRecords,
                             FacilityRepository facilityRepository,
                             ReportingPeriodRepository reportingPeriodRepository,
                             CalculationRepository calculationRepository,
                             ActivityDataRepository activityRepository) {
        this.emissionRecords = emissionRecords;
        this.facilityRepository = facilityRepository;
        this.reportingPeriodRepository = reportingPeriodRepository;
        this.calculationRepository = calculationRepository;
        this.activityRepository = activityRepository;
    }

    @GetMapping(value = "/export-csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERMISSION_reports.read')")
    public void exportInventoryCsv(HttpServletResponse response) throws IOException {
        TenantContext ctx = TenantContext.get();
        String orgId = ctx.getOrganizationId();

        List<EmissionRecord> records =
                emissionRecords.list(orgId, null, null, "ACTIVE", null);

        Map<String, Facility> facilities = index(
                facilityRepository.list(orgId), Facility::getId);
        Map<String, ReportingPeriod> periods = index(
                reportingPeriodRepository.list(orgId), ReportingPeriod::getId);
        Map<String, Calculation> calculations = index(
                calculationRepository.list(orgId, null, null), Calculation::getId);
        Map<String, ActivityData> activities = index(
                activityRepository.list(orgId, null, null, null), ActivityData::getId);

        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"carbonflow_emission_inventory_report.csv\"");

        PrintWriter writer = response.getWriter();
        writer.println("Emission Record ID,Reporting Period,Facility Name,Facility Code,Scope,Category,Scope 2 Method,Activity Type,Original Quantity,Unit,Calculation Hash,CO2e Tonnes,Status,Timestamp");

        for (EmissionRecord r : records) {
            Facility fac = facilities.get(r.getFacilityId());
            ReportingPeriod period = periods.get(r.getReportingPeriodId());
            Calculation calc = calculations.get(r.getCalculationId());
            ActivityData act = calc != null ? activities.get(calc.getActivityDataId()) : null;

            String periodName = period != null ? period.getName() : r.getReportingPeriodId();
            String facName = fac != null ? fac.getName() : "Unknown";
            String facCode = fac != null ? fac.getFacilityCode() : "N/A";
            String actType = act != null ? act.getActivityType() : "N/A";
            String qty = calc != null ? calc.getOriginalQuantity().toPlainString()
                    : (act != null && act.getQuantity() != null ? act.getQuantity().toPlainString() : "N/A");
            String unit = calc != null ? calc.getOriginalUnit()
                    : (act != null ? act.getUnit() : "N/A");
            String hash = calc != null ? calc.getCalculationHash() : "N/A";
            String scope2 = r.getScope2Type() != null ? r.getScope2Type().name() : "N/A";

            writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%s,\"%s\",\"%s\",%s,\"%s\",\"%s\"%n",
                    r.getId(),
                    periodName,
                    facName,
                    facCode,
                    r.getScope().name(),
                    r.getCategory(),
                    scope2,
                    actType,
                    qty,
                    unit,
                    hash,
                    r.getCo2eTonnes().toPlainString(),
                    r.getStatus(),
                    r.getCreatedAt().toString());
        }

        writer.flush();
    }

    private static <T> Map<String, T> index(List<T> rows, java.util.function.Function<T, String> id) {
        Map<String, T> byId = new HashMap<>();
        for (T row : rows) {
            byId.put(id.apply(row), row);
        }
        return byId;
    }
}
