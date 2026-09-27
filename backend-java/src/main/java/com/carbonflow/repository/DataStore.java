package com.carbonflow.repository;

import com.carbonflow.model.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store backing the remaining prototype-era seeds (facilities and
 * reporting periods for the analytics/reports mocks, evidence and audit trail
 * caches) — everything else migrated to PostgreSQL:
 *
 * <ul>
 *   <li>identity (orgs/users/memberships) — Phase 3 ({@link DemoDataSeeder},
 *       {@link SeedIds});</li>
 *   <li>facilities, reporting periods, legal entities, departments — Phase 4
 *       JDBC repositories;</li>
 *   <li>evidence and audits — Phase 5 (V5/V6);</li>
 *   <li>emission factors, activity data, calculations and emission records —
 *       Phase 6: the canonical accounting flow owns them end to end (V1
 *       schema + {@code CalculationService}/{@code EmissionLedgerService}).
 *       The prototype maps and their seeded records are gone; there is no
 *       second accounting model (ADR-017).</li>
 * </ul>
 *
 * <p>Deliberately annotated {@code @Component}, NOT {@code @Repository}: with
 * spring-boot-starter-jdbc present, {@code @Repository} beans get a CGLIB
 * exception-translation proxy created without running constructors, which would
 * null out the public field maps that controllers read directly.
 */
@Component
public class DataStore {

    public final Map<String, Facility> facilities = new ConcurrentHashMap<>();
    public final Map<String, ReportingPeriod> reportingPeriods = new ConcurrentHashMap<>();
    public final Map<String, EvidenceItem> evidenceItems = new ConcurrentHashMap<>();
    public final List<AuditTrailEvent> auditTrail = Collections.synchronizedList(new ArrayList<>());

    public DataStore() {
        seedInitialData();
    }

    private void seedInitialData() {
        // Tenant ids: identity lives in PostgreSQL (Phase 3) — these are the
        // same fixed UUIDs DemoDataSeeder inserts.
        String acmeOrgId = SeedIds.ORG_ACME;
        String apexOrgId = SeedIds.ORG_APEX;

        // Facilities — prototype-era copies feeding the not-yet-migrated
        // analytics/reports mocks only; the Phase 4 /facilities API reads
        // PostgreSQL (schema shape: facilityType + ISO country + gridRegion).
        Facility facDet = new Facility("fac-det-01", acmeOrgId, null, "Detroit Heavy Assembly Plant", "FAC-DET-01", "MANUFACTURING", "United States", "Michigan", "US-MRO", 145000.0, Instant.now(), Instant.now());
        Facility facAtx = new Facility("fac-atx-02", acmeOrgId, null, "Austin Advanced Tech & Prototyping", "FAC-ATX-02", "MANUFACTURING", "United States", "Texas", "US-ERCOT", 65000.0, Instant.now(), Instant.now());
        Facility facStg = new Facility("fac-stg-03", acmeOrgId, null, "Stuttgart R&D Engineering Campus", "FAC-STG-03", "MANUFACTURING", "Germany", "Baden-Württemberg", "EU-DE-GRID", 42000.0, Instant.now(), Instant.now());

        Facility facApex = new Facility("fac-apex-01", apexOrgId, null, "Apex Nevada Logistics Hub", "FAC-APX-01", "LOGISTICS", "United States", "Nevada", "US-WECC", 85000.0, Instant.now(), Instant.now());

        facilities.put(facDet.getId(), facDet);
        facilities.put(facAtx.getId(), facAtx);
        facilities.put(facStg.getId(), facStg);
        facilities.put(facApex.getId(), facApex);

        // Reporting Periods — prototype copies for the analytics mock;
        // the Phase 4 /reporting-periods API reads PostgreSQL (V1 status).
        ReportingPeriod rp2024 = new ReportingPeriod("period-acme-fy2024", acmeOrgId, "FY2024 Annual GHG Reporting Cycle", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "OPEN", Instant.now(), Instant.now());
        ReportingPeriod rp2024Apex = new ReportingPeriod("period-apex-fy2024", apexOrgId, "Apex FY2024 Inventory", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), "OPEN", Instant.now(), Instant.now());
        reportingPeriods.put(rp2024.getId(), rp2024);
        reportingPeriods.put(rp2024Apex.getId(), rp2024Apex);

        // Emission factors, activity data, calculations and emission records
        // are seeded in PostgreSQL by Flyway (V2 reference data) and written
        // only by the Phase 6 calculation flow — no in-memory accounting
        // seeds remain. Audit seeds moved to PostgreSQL with Phase 5.
    }
}
