package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.model.*;
import com.carbonflow.repository.DataStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportsController {

    private final DataStore dataStore;

    public ReportsController(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @GetMapping(value = "/export-csv", produces = "text/csv")
    public void exportInventoryCsv(HttpServletResponse response) throws IOException {
        TenantContext ctx = TenantContext.get();
        String orgId = ctx.getOrganizationId();

        List<EmissionRecord> records = dataStore.emissionRecords.values().stream()
                .filter(e -> e.getOrganizationId().equals(orgId) && "ACTIVE".equals(e.getStatus()))
                .collect(Collectors.toList());

        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"carbonflow_emission_inventory_report.csv\"");

        PrintWriter writer = response.getWriter();
        writer.println("Emission Record ID,Reporting Period,Facility Name,Facility Code,Scope,Category,Scope 2 Method,Activity Type,Original Quantity,Unit,Calculation Hash,CO2e Tonnes,Status,Timestamp");

        for (EmissionRecord r : records) {
            Facility fac = dataStore.facilities.get(r.getFacilityId());
            ReportingPeriod period = dataStore.reportingPeriods.get(r.getReportingPeriodId());
            Calculation calc = dataStore.calculations.get(r.getCalculationId());
            ActivityData act = calc != null ? dataStore.activityData.get(calc.getActivityDataId()) : null;

            String periodName = period != null ? period.getName() : r.getReportingPeriodId();
            String facName = fac != null ? fac.getName() : "Unknown";
            String facCode = fac != null ? fac.getFacilityCode() : "N/A";
            String actType = act != null ? act.getActivityType() : "N/A";
            String qty = calc != null ? calc.getOriginalQuantity().toPlainString() : (act != null ? act.getQuantity().toPlainString() : "N/A");
            String unit = calc != null ? calc.getOriginalUnit() : (act != null ? act.getUnit() : "N/A");
            String hash = calc != null ? calc.getCalculationHash() : "N/A";
            String scope2 = r.getScope2Type() != null ? r.getScope2Type().name() : "N/A";

            writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%s,\"%s\",\"%s\",%s,\"%s\",\"%s\"%n",
                    r.getId(),
                    periodName,
                    facName,
                    facCode,
                    r.getScope().name(),
                    r.getCategory().name(),
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
}
