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
import com.carbonflow.service.AuthException;
import com.carbonflow.service.UuidContract;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
 *
 * <p><b>Task 7.6 hardening</b> (deviations documented in ADR-018):
 * <ul>
 *   <li>every cell passes the Node reference's {@code csvCell} guard — CSV
 *       formula injection ({@code = + - @} prefixes) is neutralised with a
 *       leading apostrophe, embedded quotes are doubled, cells are quoted;</li>
 *   <li>{@code periodId}/{@code facilityId} (Node-UUID contract) and
 *       {@code scope}/{@code scope2Type} (enum) query filters are validated
 *       up front — malformed values answer 400 instead of a SQL cast error,
 *       and a foreign id simply filters to an empty export (no existence
 *       leak);</li>
 *   <li>rows are emitted in deterministic {@code created_at DESC, id DESC}
 *       order with platform-independent {@code \n} line endings and
 *       ISO-8601 UTC timestamps — identical ledgers produce identical CSVs.</li>
 * </ul>
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
    public void exportInventoryCsv(
            @RequestParam(required = false) String periodId,
            @RequestParam(required = false) String facilityId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String scope2Type,
            HttpServletResponse response) throws IOException {
        TenantContext ctx = TenantContext.get();
        String orgId = ctx.getOrganizationId();

        // Validated up front: malformed ids/enums answer 400, never a 500
        // SQL cast error; a well-formed foreign id just filters to empty.
        String periodFilter = validateUuid(periodId, "periodId");
        String facilityFilter = validateUuid(facilityId, "facilityId");
        String scopeFilter = validateEnum(scope,
                List.of("SCOPE_1", "SCOPE_2", "SCOPE_3"), "scope");
        String scope2Filter = validateEnum(scope2Type,
                List.of("LOCATION_BASED", "MARKET_BASED"), "scope2Type");

        List<EmissionRecord> records = emissionRecords.listForExport(
                orgId, periodFilter, facilityFilter, scopeFilter, scope2Filter);

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
        writer.print("Emission Record ID,Reporting Period,Facility Name,Facility Code,"
                + "Scope,Category,Scope 2 Method,Activity Type,Original Quantity,Unit,"
                + "Calculation Hash,CO2e Tonnes,Status,Timestamp\n");

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

            // Every cell through the Node csvCell guard (ADR-018), joined and
            // terminated with a platform-independent \n (Node parity).
            writer.print(String.join(",",
                    cell(r.getId()),
                    cell(periodName),
                    cell(facName),
                    cell(facCode),
                    cell(r.getScope().name()),
                    cell(r.getCategory()),
                    cell(scope2),
                    cell(actType),
                    cell(qty),
                    cell(unit),
                    cell(hash),
                    cell(r.getCo2eTonnes().toPlainString()),
                    cell(r.getStatus()),
                    cell(r.getCreatedAt().toString())) + "\n");
        }

        writer.flush();
    }

    /**
     * The Node reference's {@code csvCell} (server/routes.ts:69): {@code null}
     * → {@code N/A}, a value starting with {@code = + - @} (spreadsheet
     * formula injection) gets a leading apostrophe, embedded quotes are
     * doubled, and the whole cell is quoted.
     */
    static String cell(String value) {
        String text = value == null ? "N/A" : value;
        String safe = !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0
                ? "'" + text : text;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String validateUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!UuidContract.isNodeUuid(trimmed)) {
            throw new AuthException("VALIDATION_ERROR",
                    field + " must be a valid UUID.", HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private static String validateEnum(String value, List<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!allowed.contains(trimmed)) {
            throw new AuthException("VALIDATION_ERROR",
                    field + " must be one of: " + String.join(", ", allowed) + ".",
                    HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private static <T> Map<String, T> index(List<T> rows, java.util.function.Function<T, String> id) {
        Map<String, T> byId = new HashMap<>();
        for (T row : rows) {
            byId.put(id.apply(row), row);
        }
        return byId;
    }
}
