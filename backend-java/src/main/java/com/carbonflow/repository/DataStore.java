package com.carbonflow.repository;

import com.carbonflow.model.*;
import com.carbonflow.model.enums.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory store backing the pre-persistence prototype controllers until the
 * JDBC repositories land (Phase 3+).
 *
 * <p>Deliberately annotated {@code @Component}, NOT {@code @Repository}: with
 * spring-boot-starter-jdbc present, {@code @Repository} beans get a CGLIB
 * exception-translation proxy created without running constructors, which would
 * null out the public field maps that controllers read directly.
 */
@Component
public class DataStore {

    /**
     * Seed credentials are hashed with the same algorithm and cost as the login
     * check (BCrypt cost 10 — identical to bcryptjs cost 10 in the Node
     * reference backend). No plaintext credential is stored anywhere.
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(10);

    private static String hash(String rawPassword) {
        return PASSWORD_ENCODER.encode(rawPassword);
    }


    public final Map<String, Organization> organizations = new ConcurrentHashMap<>();
    public final Map<String, User> users = new ConcurrentHashMap<>();
    public final Map<String, Facility> facilities = new ConcurrentHashMap<>();
    public final Map<String, ReportingPeriod> reportingPeriods = new ConcurrentHashMap<>();
    public final Map<String, EmissionFactor> emissionFactors = new ConcurrentHashMap<>();
    public final Map<String, ActivityData> activityData = new ConcurrentHashMap<>();
    public final Map<String, Calculation> calculations = new ConcurrentHashMap<>();
    public final Map<String, EmissionRecord> emissionRecords = new ConcurrentHashMap<>();
    public final Map<String, EvidenceItem> evidenceItems = new ConcurrentHashMap<>();
    public final Map<String, AuditRoom> auditRooms = new ConcurrentHashMap<>();
    public final List<AuditTrailEvent> auditTrail = Collections.synchronizedList(new ArrayList<>());

    public DataStore() {
        seedInitialData();
    }

    private void seedInitialData() {
        // 1. Organizations
        Organization acme = new Organization("org-acme-corp", "Acme Global Manufacturing", "acme-global", "2024", Instant.now());
        Organization apex = new Organization("org-apex-cleantech", "Apex CleanTech Logistics", "apex-cleantech", "2024", Instant.now());
        organizations.put(acme.getId(), acme);
        organizations.put(apex.getId(), apex);

        // 2. Users (canonical roles; demo password stored only as a BCrypt hash)
        User adminAcme = new User("usr-acme-admin", "admin@acmeglobal.com", hash("Password123!"), "Elena Rostova", acme.getId(), Role.COMPANY_ADMIN, List.of(), true, Instant.now());
        User mgrAcme = new User("usr-acme-mgr", "manager@acmeglobal.com", hash("Password123!"), "Marcus Vance", acme.getId(), Role.SUSTAINABILITY_MANAGER, List.of(), true, Instant.now());
        User auditor = new User("usr-auditor-1", "auditor@ey-assurance.com", hash("Password123!"), "Sarah Jenkins (EY Auditor)", acme.getId(), Role.ASSURANCE_PROVIDER, List.of(), true, Instant.now());
        User adminApex = new User("usr-apex-admin", "admin@apexcorp.com", hash("Password123!"), "David Chen", apex.getId(), Role.COMPANY_ADMIN, List.of(), true, Instant.now());

        users.put(adminAcme.getId(), adminAcme);
        users.put(mgrAcme.getId(), mgrAcme);
        users.put(auditor.getId(), auditor);
        users.put(adminApex.getId(), adminApex);

        // 3. Facilities
        Facility facDet = new Facility("fac-det-01", acme.getId(), "Detroit Heavy Assembly Plant", "FAC-DET-01", "United States", "US-MRO", "1200 Industrial Blvd, Detroit, MI", 145000.0, "OPERATIONAL", Instant.now());
        Facility facAtx = new Facility("fac-atx-02", acme.getId(), "Austin Advanced Tech & Prototyping", "FAC-ATX-02", "United States", "US-ERCOT", "450 Silicon Pkwy, Austin, TX", 65000.0, "OPERATIONAL", Instant.now());
        Facility facStg = new Facility("fac-stg-03", acme.getId(), "Stuttgart R&D Engineering Campus", "FAC-STG-03", "Germany", "EU-DE-GRID", "Werkstraße 12, Stuttgart", 42000.0, "OPERATIONAL", Instant.now());

        Facility facApex = new Facility("fac-apex-01", apex.getId(), "Apex Nevada Logistics Hub", "FAC-APX-01", "United States", "US-WECC", "900 Desert Way, Reno, NV", 85000.0, "OPERATIONAL", Instant.now());

        facilities.put(facDet.getId(), facDet);
        facilities.put(facAtx.getId(), facAtx);
        facilities.put(facStg.getId(), facStg);
        facilities.put(facApex.getId(), facApex);

        // 4. Reporting Periods
        ReportingPeriod rp2024 = new ReportingPeriod("period-acme-fy2024", acme.getId(), "FY2024 Annual GHG Reporting Cycle", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), false);
        ReportingPeriod rp2024Apex = new ReportingPeriod("period-apex-fy2024", apex.getId(), "Apex FY2024 Inventory", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), false);
        reportingPeriods.put(rp2024.getId(), rp2024);
        reportingPeriods.put(rp2024Apex.getId(), rp2024Apex);

        // 5. Emission Factors (Standardized eGRID, DEFRA, IPCC)
        EmissionFactor efGas = new EmissionFactor("ef-gas", "UK DEFRA 2024", "GLOBAL", GHGScope.SCOPE_1, EmissionCategory.STATIONARY_COMBUSTION, null, "NATURAL_GAS", "kWh", new BigDecimal("0.18288"), "CO2: 99.8%, CH4: 0.1%, N2O: 0.1%", 2024, "https://www.gov.uk/defra");
        EmissionFactor efDieselGen = new EmissionFactor("ef-diesel-stat", "UK DEFRA 2024", "GLOBAL", GHGScope.SCOPE_1, EmissionCategory.STATIONARY_COMBUSTION, null, "DIESEL_GENERATOR", "Litres", new BigDecimal("2.75620"), "CO2: 99.1%, CH4: 0.2%, N2O: 0.7%", 2024, "https://www.gov.uk/defra");
        EmissionFactor efFleetDiesel = new EmissionFactor("ef-fleet-diesel", "EPA GHG Hub 2024", "US-NATIONAL", GHGScope.SCOPE_1, EmissionCategory.MOBILE_COMBUSTION, null, "FLEET_DIESEL", "Litres", new BigDecimal("2.68920"), "CO2: 99.0%, CH4: 0.3%, N2O: 0.7%", 2024, "https://www.epa.gov/ghgemissions");
        EmissionFactor efRefrig = new EmissionFactor("ef-r410a", "IPCC AR6", "GLOBAL", GHGScope.SCOPE_1, EmissionCategory.FUGITIVE_EMISSIONS, null, "REFRIGERANT_R410A", "KG", new BigDecimal("2088.00"), "HFC-32: 50%, HFC-125: 50%", 2024, "https://www.ipcc.ch");
        EmissionFactor efGridLoc = new EmissionFactor("ef-grid-loc", "US EPA eGRID 2024", "US-MRO", GHGScope.SCOPE_2, EmissionCategory.ELECTRICITY_LOCATION, Scope2Method.LOCATION_BASED, "GRID_ELECTRICITY_US", "kWh", new BigDecimal("0.399987"), "CO2, CH4, N2O", 2024, "https://www.epa.gov/egrid");
        EmissionFactor efGridMkt = new EmissionFactor("ef-grid-mkt-green", "Supplier Contract PPA", "US-MRO", GHGScope.SCOPE_2, EmissionCategory.ELECTRICITY_MARKET, Scope2Method.MARKET_BASED, "GREEN_POWER_TARIFF", "kWh", BigDecimal.ZERO, "100% Certified Wind/Solar RECs", 2024, "https://green-e.org");

        emissionFactors.put(efGas.getId(), efGas);
        emissionFactors.put(efDieselGen.getId(), efDieselGen);
        emissionFactors.put(efFleetDiesel.getId(), efFleetDiesel);
        emissionFactors.put(efRefrig.getId(), efRefrig);
        emissionFactors.put(efGridLoc.getId(), efGridLoc);
        emissionFactors.put(efGridMkt.getId(), efGridMkt);

        // 6. Seed Calculations & Emission Records for Acme
        seedAcmeRecords(acme.getId(), facDet.getId(), rp2024.getId(), efGas, new BigDecimal("500000"), "kWh", new BigDecimal("91.440"), "calc-1");
        seedAcmeRecords(acme.getId(), facDet.getId(), rp2024.getId(), efDieselGen, new BigDecimal("12500"), "Litres", new BigDecimal("34.4525"), "calc-2");
        seedAcmeRecords(acme.getId(), facDet.getId(), rp2024.getId(), efFleetDiesel, new BigDecimal("45000"), "Litres", new BigDecimal("121.014"), "calc-3");
        seedAcmeRecords(acme.getId(), facDet.getId(), rp2024.getId(), efRefrig, new BigDecimal("45"), "KG", new BigDecimal("93.960"), "calc-4");
        seedAcmeRecords(acme.getId(), facDet.getId(), rp2024.getId(), efGridLoc, new BigDecimal("1250000"), "kWh", new BigDecimal("499.98375"), "calc-5");
        seedAcmeRecords(acme.getId(), facAtx.getId(), rp2024.getId(), efGridMkt, new BigDecimal("820000"), "kWh", BigDecimal.ZERO, "calc-6");

        // 7. Audit Room
        AuditRoom room = new AuditRoom("audit-acme-2024", acme.getId(), rp2024.getId(), "FY2024 ISO 14064-3 Third-Party Assurance", AuditStatus.READY_FOR_VERIFICATION, "auditor@ey-assurance.com", Instant.now());
        room.getChecklist().add(new AuditRoom.ChecklistItem("CHK-01", "Boundary definition validated under Operational Control criteria", true, true));
        room.getChecklist().add(new AuditRoom.ChecklistItem("CHK-02", "Scope 2 Dual-Reporting verified (Location-based vs Market-based)", true, true));
        room.getChecklist().add(new AuditRoom.ChecklistItem("CHK-03", "Refrigerant mass-balance leak records reconciled with maintenance invoices", true, true));
        room.getChecklist().add(new AuditRoom.ChecklistItem("CHK-04", "Independent verification of REC retirement certificates on registry", false, true));

        auditRooms.put(room.getId(), room);
    }

    private void seedAcmeRecords(String orgId, String facilityId, String periodId, EmissionFactor factor, BigDecimal qty, String unit, BigDecimal tonnes, String calcId) {
        String actId = "act-" + calcId;
        ActivityData act = new ActivityData(actId, orgId, facilityId, periodId, factor.getScope(), factor.getCategory(), factor.getScope2Type(),
                factor.getActivityType(), qty, unit, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                "Audited activity data for " + factor.getActivityType(), "CALCULATED", null, calcId, Instant.now());
        activityData.put(actId, act);

        String formula = qty.toPlainString() + " " + unit + " × " + factor.getFactorValue().toPlainString() + " = " + tonnes.multiply(new BigDecimal("1000")).toPlainString() + " kgCO2e";
        Calculation calc = new Calculation(calcId, orgId, actId, factor.getId(), qty, unit, qty, unit, factor.getFactorValue(),
                tonnes.multiply(new BigDecimal("1000")), tonnes, formula, "a6d8c9e4f2b1d3e8a5b2c7e9f0d1a4b6c8e0f2d4a6b8c0e2f4a6b8c0d2e4f6a8", "usr-acme-admin", Instant.now());
        calculations.put(calcId, calc);

        String emId = "em-" + calcId;
        EmissionRecord rec = new EmissionRecord(emId, orgId, facilityId, periodId, calcId, factor.getScope(), factor.getCategory(), factor.getScope2Type(), tonnes, "ACTIVE", Instant.now());
        emissionRecords.put(emId, rec);
    }
}
