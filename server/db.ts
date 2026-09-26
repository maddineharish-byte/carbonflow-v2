/**
 * CarbonFlow — Enterprise Relational Data Store
 * In-memory transactional engine maintaining full schema integrity, foreign keys, and indexes.
 */
import bcrypt from 'bcryptjs';
import crypto from 'crypto';
import {
  Organization,
  User,
  RoleName,
  OrganizationMembership,
  LegalEntity,
  Facility,
  ReportingPeriod,
  GwpSet,
  GwpValue,
  EmissionFactor,
  EmissionFactorVersion,
  ActivityData,
  Calculation,
  EmissionRecord,
  CarbonAudit,
  AuditChecklistItem,
  ReviewFinding,
  ReviewComment,
  EvidenceRecord,
  EvidenceLink,
  InventorySnapshot,
  CarbonTarget,
  ReductionProject,
} from './types.ts';
import { executeCalculation } from './calc.ts';

export class Database {
  organizations: Organization[] = [];
  users: User[] = [];
  memberships: OrganizationMembership[] = [];
  legalEntities: LegalEntity[] = [];
  facilities: Facility[] = [];
  reportingPeriods: ReportingPeriod[] = [];
  gwpSets: GwpSet[] = [];
  gwpValues: GwpValue[] = [];
  emissionFactors: EmissionFactor[] = [];
  emissionFactorVersions: EmissionFactorVersion[] = [];
  activityData: ActivityData[] = [];
  calculations: Calculation[] = [];
  emissionRecords: EmissionRecord[] = [];
  audits: CarbonAudit[] = [];
  auditChecklistItems: AuditChecklistItem[] = [];
  reviewFindings: ReviewFinding[] = [];
  reviewComments: ReviewComment[] = [];
  evidenceRecords: EvidenceRecord[] = [];
  evidenceLinks: EvidenceLink[] = [];
  inventorySnapshots: InventorySnapshot[] = [];
  targets: CarbonTarget[] = [];
  reductionProjects: ReductionProject[] = [];

  constructor() {
    this.seed();
  }

  seed() {
    const now = new Date().toISOString();
    const defaultPasswordHash = bcrypt.hashSync('Password123!', 10);

    // ==========================================
    // 1. GWP REFERENCE DATA
    // ==========================================
    const gwpAr6Id = 'gwp-ar6-100';
    const gwpAr5Id = 'gwp-ar5-100';
    const gwpAr4Id = 'gwp-ar4-100';

    this.gwpSets = [
      { id: gwpAr6Id, code: 'IPCC_AR6', name: 'IPCC Sixth Assessment Report (AR6)', assessmentReport: 'AR6', publicationYear: 2021, isDefault: true },
      { id: gwpAr5Id, code: 'IPCC_AR5', name: 'IPCC Fifth Assessment Report (AR5)', assessmentReport: 'AR5', publicationYear: 2013, isDefault: false },
      { id: gwpAr4Id, code: 'IPCC_AR4', name: 'IPCC Fourth Assessment Report (AR4)', assessmentReport: 'AR4', publicationYear: 2007, isDefault: false },
    ];

    this.gwpValues = [
      // AR6
      { id: 'gwp-v-1', gwpSetId: gwpAr6Id, gas: 'CO2', gwp100yr: 1 },
      { id: 'gwp-v-2', gwpSetId: gwpAr6Id, gas: 'CH4', gwp100yr: 27.9 },
      { id: 'gwp-v-3', gwpSetId: gwpAr6Id, gas: 'N2O', gwp100yr: 273 },
      { id: 'gwp-v-4', gwpSetId: gwpAr6Id, gas: 'SF6', gwp100yr: 25200 },
      { id: 'gwp-v-5', gwpSetId: gwpAr6Id, gas: 'HFC-134a', gwp100yr: 1530 },
      { id: 'gwp-v-6', gwpSetId: gwpAr6Id, gas: 'HFC-32', gwp100yr: 771 },
      // AR5
      { id: 'gwp-v-7', gwpSetId: gwpAr5Id, gas: 'CO2', gwp100yr: 1 },
      { id: 'gwp-v-8', gwpSetId: gwpAr5Id, gas: 'CH4', gwp100yr: 28 },
      { id: 'gwp-v-9', gwpSetId: gwpAr5Id, gas: 'N2O', gwp100yr: 265 },
      // AR4
      { id: 'gwp-v-10', gwpSetId: gwpAr4Id, gas: 'CO2', gwp100yr: 1 },
      { id: 'gwp-v-11', gwpSetId: gwpAr4Id, gas: 'CH4', gwp100yr: 25 },
      { id: 'gwp-v-12', gwpSetId: gwpAr4Id, gas: 'N2O', gwp100yr: 298 },
    ];

    // ==========================================
    // 2. EMISSION FACTORS & VERSIONS
    // ==========================================
    const efGasId = 'ef-natgas-stationary';
    const efDieselStatId = 'ef-diesel-generator';
    const efFleetDieselId = 'ef-fleet-diesel';
    const efRefrigId = 'ef-r410a-leakage';
    const efGridLocId = 'ef-grid-us-location';
    const efGridMktGreenId = 'ef-grid-green-tariff';
    const efGridMktResidId = 'ef-grid-us-residual';

    this.emissionFactors = [
      { id: efGasId, scope: 'SCOPE_1', category: 'STATIONARY_COMBUSTION', activityType: 'NATURAL_GAS', fuelOrActivity: 'Natural Gas (Pipeline)', inputUnit: 'kWh' },
      { id: efDieselStatId, scope: 'SCOPE_1', category: 'STATIONARY_COMBUSTION', activityType: 'DIESEL_GENERATOR', fuelOrActivity: 'Gas Oil / Stationary Diesel', inputUnit: 'Litres' },
      { id: efFleetDieselId, scope: 'SCOPE_1', category: 'MOBILE_COMBUSTION', activityType: 'FLEET_DIESEL', fuelOrActivity: 'Diesel (Commercial Fleet)', inputUnit: 'Litres' },
      { id: efRefrigId, scope: 'SCOPE_1', category: 'FUGITIVE_EMISSIONS', activityType: 'REFRIGERANT_R410A', fuelOrActivity: 'R-410A Refrigerant Leakage', inputUnit: 'KG' },
      { id: efGridLocId, scope: 'SCOPE_2', category: 'ELECTRICITY_LOCATION', activityType: 'GRID_ELECTRICITY_US', fuelOrActivity: 'US Grid Electricity (eGRID)', inputUnit: 'kWh' },
      { id: efGridMktGreenId, scope: 'SCOPE_2', category: 'ELECTRICITY_MARKET', activityType: 'GREEN_POWER_TARIFF', fuelOrActivity: 'Supplier Green Power Contract (RECs/PPA)', inputUnit: 'kWh' },
      { id: efGridMktResidId, scope: 'SCOPE_2', category: 'ELECTRICITY_MARKET', activityType: 'RESIDUAL_MIX_US', fuelOrActivity: 'US Residual Mix Electricity', inputUnit: 'kWh' },
    ];

    this.emissionFactorVersions = [
      {
        id: 'efv-gas-1',
        emissionFactorId: efGasId,
        versionNumber: 1,
        co2Factor: 0.18254,
        ch4Factor: 0.00024,
        n2oFactor: 0.0001,
        co2eFactor: 0.18288,
        factorUnit: 'kgCO2e/kWh',
        source: 'UK DEFRA / BEIS',
        sourceYear: 2024,
        geography: 'GLOBAL',
        status: 'ACTIVE',
        effectiveStart: '2024-01-01',
      },
      {
        id: 'efv-diesel-stat-1',
        emissionFactorId: efDieselStatId,
        versionNumber: 1,
        co2Factor: 2.68697,
        ch4Factor: 0.00085,
        n2oFactor: 0.02422,
        co2eFactor: 2.71204,
        factorUnit: 'kgCO2e/Litre',
        source: 'UK DEFRA / BEIS',
        sourceYear: 2024,
        geography: 'GLOBAL',
        status: 'ACTIVE',
        effectiveStart: '2024-01-01',
      },
      {
        id: 'efv-fleet-diesel-1',
        emissionFactorId: efFleetDieselId,
        versionNumber: 1,
        co2Factor: 2.5121,
        ch4Factor: 0.00007,
        n2oFactor: 0.0381,
        co2eFactor: 2.55027,
        factorUnit: 'kgCO2e/Litre',
        source: 'US EPA Emission Hub',
        sourceYear: 2024,
        geography: 'US',
        status: 'ACTIVE',
        effectiveStart: '2024-01-01',
      },
      {
        id: 'efv-refrig-1',
        emissionFactorId: efRefrigId,
        versionNumber: 1,
        co2Factor: 0,
        ch4Factor: 0,
        n2oFactor: 0,
        co2eFactor: 2088.0,
        factorUnit: 'kgCO2e/kg',
        source: 'IPCC AR6 Blend',
        sourceYear: 2024,
        geography: 'GLOBAL',
        status: 'ACTIVE',
        effectiveStart: '2024-01-01',
      },
      {
        id: 'efv-grid-loc-1',
        emissionFactorId: efGridLocId,
        versionNumber: 1,
        co2Factor: 0.3855,
        ch4Factor: 0.00003,
        n2oFactor: 0.00005,
        co2eFactor: 0.38558,
        factorUnit: 'kgCO2e/kWh',
        source: 'US EPA eGRID',
        sourceYear: 2024,
        geography: 'US',
        status: 'ACTIVE',
        effectiveStart: '2024-01-01',
      },
      {
        id: 'efv-grid-green-1',
        emissionFactorId: efGridMktGreenId,
        versionNumber: 1,
        co2Factor: 0,
        ch4Factor: 0,
        n2oFactor: 0,
        co2eFactor: 0.0,
        factorUnit: 'kgCO2e/kWh',
        source: 'Contractual Guarantee of Origin',
        sourceYear: 2024,
        geography: 'GLOBAL',
        status: 'ACTIVE',
        effectiveStart: '2024-01-01',
      },
      {
        id: 'efv-grid-resid-1',
        emissionFactorId: efGridMktResidId,
        versionNumber: 1,
        co2Factor: 0.442,
        ch4Factor: 0.00004,
        n2oFactor: 0.00006,
        co2eFactor: 0.4421,
        factorUnit: 'kgCO2e/kWh',
        source: 'Green-e Residual Mix',
        sourceYear: 2024,
        geography: 'US',
        status: 'ACTIVE',
        effectiveStart: '2024-01-01',
      },
    ];

    // ==========================================
    // 3. TENANT A: Acme Global Manufacturing
    // ==========================================
    const orgAId = 'org-tenant-a-1111';
    this.organizations.push({
      id: orgAId,
      name: 'Acme Global Manufacturing Corp',
      taxId: 'US-94-3829104',
      country: 'US',
      industry: 'Industrial Manufacturing & Automotive Systems',
      consolidationApproach: 'OPERATIONAL_CONTROL',
      baseYear: 2023,
      createdAt: now,
      updatedAt: now,
    });

    // Users in Tenant A
    const userAdminId = 'user-acme-admin-1';
    const userSustId = 'user-acme-sust-2';
    const userAccId = 'user-acme-acc-3';
    const userRevId = 'user-acme-rev-4';

    this.users.push(
      {
        id: userAdminId,
        email: 'admin@acmeglobal.com',
        passwordHash: defaultPasswordHash,
        fullName: 'Elena Rostova (Company Admin)',
        isActive: true,
        createdAt: now,
      },
      {
        id: userSustId,
        email: 'sustainability@acmeglobal.com',
        passwordHash: defaultPasswordHash,
        fullName: 'Marcus Vance (Sustainability Manager)',
        isActive: true,
        createdAt: now,
      },
      {
        id: userAccId,
        email: 'accountant@acmeglobal.com',
        passwordHash: defaultPasswordHash,
        fullName: 'Sarah Chen (Carbon Accountant)',
        isActive: true,
        createdAt: now,
      },
      {
        id: userRevId,
        email: 'auditor@thirdpartyaudit.com',
        passwordHash: defaultPasswordHash,
        fullName: 'David Sterling (Lead GHG Reviewer)',
        isActive: true,
        createdAt: now,
      }
    );

    this.memberships.push(
      { id: 'mem-a-1', organizationId: orgAId, userId: userAdminId, role: 'COMPANY_ADMIN', isActive: true, createdAt: now },
      { id: 'mem-a-2', organizationId: orgAId, userId: userSustId, role: 'SUSTAINABILITY_MANAGER', isActive: true, createdAt: now },
      { id: 'mem-a-3', organizationId: orgAId, userId: userAccId, role: 'CARBON_ACCOUNTANT', isActive: true, createdAt: now },
      { id: 'mem-a-4', organizationId: orgAId, userId: userRevId, role: 'REVIEWER', isActive: true, createdAt: now }
    );

    // Legal entities & Facilities in Tenant A
    const leA1 = 'le-acme-na';
    this.legalEntities.push({
      id: leA1,
      organizationId: orgAId,
      name: 'Acme North America Operations LLC',
      jurisdiction: 'Delaware, USA',
      registrationNumber: 'DEL-849204',
      ownershipPercentage: 100,
      createdAt: now,
    });

    const facDetroit = 'fac-acme-detroit';
    const facAustin = 'fac-acme-austin';
    const facFrankfurt = 'fac-acme-frankfurt';

    this.facilities.push(
      {
        id: facDetroit,
        organizationId: orgAId,
        legalEntityId: leA1,
        name: 'Detroit Heavy Assembly Plant',
        facilityCode: 'FAC-DET-01',
        facilityType: 'MANUFACTURING',
        country: 'US',
        stateProvince: 'Michigan',
        gridRegion: 'eGRID_MROW',
        floorAreaM2: 45000,
        createdAt: now,
      },
      {
        id: facAustin,
        organizationId: orgAId,
        legalEntityId: leA1,
        name: 'Austin Advanced Tech & Prototyping Facility',
        facilityCode: 'FAC-ATX-02',
        facilityType: 'DATA_CENTER',
        country: 'US',
        stateProvince: 'Texas',
        gridRegion: 'eGRID_ERCT',
        floorAreaM2: 18500,
        createdAt: now,
      },
      {
        id: facFrankfurt,
        organizationId: orgAId,
        legalEntityId: leA1,
        name: 'Frankfurt European Logistics Hub',
        facilityCode: 'FAC-FRA-03',
        facilityType: 'LOGISTICS',
        country: 'DE',
        stateProvince: 'Hesse',
        gridRegion: 'EU_GRID_DE',
        floorAreaM2: 24000,
        createdAt: now,
      }
    );

    // Reporting Periods in Tenant A
    const periodFy2024 = 'period-acme-fy2024';
    const periodFy2025 = 'period-acme-fy2025';

    this.reportingPeriods.push(
      {
        id: periodFy2024,
        organizationId: orgAId,
        name: 'FY2024 Annual GHG Reporting Cycle',
        startDate: '2024-01-01',
        endDate: '2024-12-31',
        status: 'UNDER_AUDIT',
        createdAt: now,
      },
      {
        id: periodFy2025,
        organizationId: orgAId,
        name: 'FY2025 Active Collection Cycle',
        startDate: '2025-01-01',
        endDate: '2025-12-31',
        status: 'OPEN',
        createdAt: now,
      }
    );

    // 12 Monthly Reporting Periods for Historical Trend Tracking (Tenant A)
    const monthly12 = [
      { id: 'period-acme-2024-m01', name: '2024-M01 (Jan 2024)', start: '2024-01-01', end: '2024-01-31', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m02', name: '2024-M02 (Feb 2024)', start: '2024-02-01', end: '2024-02-29', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m03', name: '2024-M03 (Mar 2024)', start: '2024-03-01', end: '2024-03-31', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m04', name: '2024-M04 (Apr 2024)', start: '2024-04-01', end: '2024-04-30', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m05', name: '2024-M05 (May 2024)', start: '2024-05-01', end: '2024-05-31', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m06', name: '2024-M06 (Jun 2024)', start: '2024-06-01', end: '2024-06-30', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m07', name: '2024-M07 (Jul 2024)', start: '2024-07-01', end: '2024-07-31', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m08', name: '2024-M08 (Aug 2024)', start: '2024-08-01', end: '2024-08-31', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m09', name: '2024-M09 (Sep 2024)', start: '2024-09-01', end: '2024-09-30', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m10', name: '2024-M10 (Oct 2024)', start: '2024-10-01', end: '2024-10-31', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m11', name: '2024-M11 (Nov 2024)', start: '2024-11-01', end: '2024-11-30', status: 'LOCKED' as const },
      { id: 'period-acme-2024-m12', name: '2024-M12 (Dec 2024)', start: '2024-12-01', end: '2024-12-31', status: 'UNDER_AUDIT' as const },
    ];
    for (const m of monthly12) {
      this.reportingPeriods.push({
        id: m.id,
        organizationId: orgAId,
        name: m.name,
        startDate: m.start,
        endDate: m.end,
        status: m.status,
        createdAt: now,
      });
    }

    // Evidence Records for Tenant A
    const evGasBill = 'ev-gas-bill-detroit';
    const evElecBill = 'ev-elec-bill-detroit';
    const evPpaCert = 'ev-austin-rec-ppa';

    this.evidenceRecords.push(
      {
        id: evGasBill,
        organizationId: orgAId,
        fileName: 'DTE_Energy_Natural_Gas_Annual_2024.pdf',
        fileSizeBytes: 2458291,
        mimeType: 'application/pdf',
        sha256Hash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        storagePath: `vault_storage/${orgAId}/DTE_Energy_Natural_Gas_Annual_2024.pdf`,
        uploadedBy: userAccId,
        createdAt: now,
      },
      {
        id: evElecBill,
        organizationId: orgAId,
        fileName: 'DTE_Electric_Meter_Summary_FY2024.csv',
        fileSizeBytes: 651204,
        mimeType: 'text/csv',
        sha256Hash: 'ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb',
        storagePath: `vault_storage/${orgAId}/DTE_Electric_Meter_Summary_FY2024.csv`,
        uploadedBy: userAccId,
        createdAt: now,
      },
      {
        id: evPpaCert,
        organizationId: orgAId,
        fileName: 'GreenPower_Austin_PPA_Guarantee_Origin_2024.pdf',
        fileSizeBytes: 1840294,
        mimeType: 'application/pdf',
        sha256Hash: '4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a',
        storagePath: `vault_storage/${orgAId}/GreenPower_Austin_PPA_Guarantee_Origin_2024.pdf`,
        uploadedBy: userSustId,
        createdAt: now,
      }
    );

    // Activity Data for Tenant A (FY2024)
    const actGas = {
      id: 'act-detroit-gas',
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      facilityId: facDetroit,
      scope: 'SCOPE_1' as const,
      category: 'STATIONARY_COMBUSTION',
      activityType: 'NATURAL_GAS',
      quantity: 500000,
      unit: 'kWh',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      source: 'Meter Gas-01 / Utility Invoice',
      status: 'CALCULATED' as const,
      submittedBy: userAccId,
      createdAt: now,
      updatedAt: now,
    };

    const actDieselGen = {
      id: 'act-detroit-diesel-gen',
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      facilityId: facDetroit,
      scope: 'SCOPE_1' as const,
      category: 'STATIONARY_COMBUSTION',
      activityType: 'DIESEL_GENERATOR',
      quantity: 12500,
      unit: 'Litres',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      source: 'Fuel Tank Dipstick & Logbook',
      status: 'CALCULATED' as const,
      submittedBy: userAccId,
      createdAt: now,
      updatedAt: now,
    };

    const actFleetDiesel = {
      id: 'act-detroit-fleet',
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      facilityId: facDetroit,
      scope: 'SCOPE_1' as const,
      category: 'MOBILE_COMBUSTION',
      activityType: 'FLEET_DIESEL',
      quantity: 45000,
      unit: 'Litres',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      source: 'WEX Fleet Fuel Card Records',
      status: 'CALCULATED' as const,
      submittedBy: userAccId,
      createdAt: now,
      updatedAt: now,
    };

    const actRefrig = {
      id: 'act-detroit-refrig',
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      facilityId: facDetroit,
      scope: 'SCOPE_1' as const,
      category: 'FUGITIVE_EMISSIONS',
      activityType: 'REFRIGERANT_R410A',
      quantity: 45,
      unit: 'KG',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      source: 'HVAC Maintenance Service Ticket',
      status: 'CALCULATED' as const,
      submittedBy: userAccId,
      createdAt: now,
      updatedAt: now,
    };

    const actElecDetroit = {
      id: 'act-detroit-elec',
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      facilityId: facDetroit,
      scope: 'SCOPE_2' as const,
      category: 'ELECTRICITY_LOCATION',
      activityType: 'GRID_ELECTRICITY_US',
      quantity: 1250000,
      unit: 'kWh',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      source: 'DTE Utility Substation Meter',
      status: 'CALCULATED' as const,
      submittedBy: userAccId,
      createdAt: now,
      updatedAt: now,
    };

    const actElecAustinLoc = {
      id: 'act-austin-elec-loc',
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      facilityId: facAustin,
      scope: 'SCOPE_2' as const,
      category: 'ELECTRICITY_LOCATION',
      activityType: 'GRID_ELECTRICITY_US',
      quantity: 820000,
      unit: 'kWh',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      source: 'Austin Energy Smart Meter Log',
      status: 'CALCULATED' as const,
      submittedBy: userAccId,
      createdAt: now,
      updatedAt: now,
    };

    const actElecAustinMkt = {
      id: 'act-austin-elec-mkt',
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      facilityId: facAustin,
      scope: 'SCOPE_2' as const,
      category: 'ELECTRICITY_MARKET',
      activityType: 'GREEN_POWER_TARIFF',
      quantity: 820000,
      unit: 'kWh',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      source: 'Solar PPA Contract #SP-994',
      status: 'CALCULATED' as const,
      submittedBy: userSustId,
      createdAt: now,
      updatedAt: now,
    };

    this.activityData.push(
      actGas,
      actDieselGen,
      actFleetDiesel,
      actRefrig,
      actElecDetroit,
      actElecAustinLoc,
      actElecAustinMkt
    );

    // Link evidence
    this.evidenceLinks.push(
      { id: 'el-1', evidenceRecordId: evGasBill, entityType: 'ACTIVITY_DATA', entityId: actGas.id, createdAt: now },
      { id: 'el-2', evidenceRecordId: evElecBill, entityType: 'ACTIVITY_DATA', entityId: actElecDetroit.id, createdAt: now },
      { id: 'el-3', evidenceRecordId: evPpaCert, entityType: 'ACTIVITY_DATA', entityId: actElecAustinMkt.id, createdAt: now }
    );

    // Run calculations on seeded activities
    const gwpAr6 = this.gwpSets.find((s) => s.id === gwpAr6Id)!;
    const gwpVals = this.gwpValues.filter((v) => v.gwpSetId === gwpAr6Id);

    const calc1 = executeCalculation({
      activityData: actGas,
      factorVersion: this.emissionFactorVersions.find((v) => v.id === 'efv-gas-1')!,
      factorInputUnit: 'kWh',
      gwpSet: gwpAr6,
      gwpValues: gwpVals,
      userId: userAccId,
    });
    this.calculations.push(calc1.calculation);
    this.emissionRecords.push(calc1.emissionRecord);

    const calc2 = executeCalculation({
      activityData: actDieselGen,
      factorVersion: this.emissionFactorVersions.find((v) => v.id === 'efv-diesel-stat-1')!,
      factorInputUnit: 'Litres',
      gwpSet: gwpAr6,
      gwpValues: gwpVals,
      userId: userAccId,
    });
    this.calculations.push(calc2.calculation);
    this.emissionRecords.push(calc2.emissionRecord);

    const calc3 = executeCalculation({
      activityData: actFleetDiesel,
      factorVersion: this.emissionFactorVersions.find((v) => v.id === 'efv-fleet-diesel-1')!,
      factorInputUnit: 'Litres',
      gwpSet: gwpAr6,
      gwpValues: gwpVals,
      userId: userAccId,
    });
    this.calculations.push(calc3.calculation);
    this.emissionRecords.push(calc3.emissionRecord);

    const calc4 = executeCalculation({
      activityData: actRefrig,
      factorVersion: this.emissionFactorVersions.find((v) => v.id === 'efv-refrig-1')!,
      factorInputUnit: 'KG',
      gwpSet: gwpAr6,
      gwpValues: gwpVals,
      userId: userAccId,
    });
    this.calculations.push(calc4.calculation);
    this.emissionRecords.push(calc4.emissionRecord);

    const calc5 = executeCalculation({
      activityData: actElecDetroit,
      factorVersion: this.emissionFactorVersions.find((v) => v.id === 'efv-grid-loc-1')!,
      factorInputUnit: 'kWh',
      gwpSet: gwpAr6,
      gwpValues: gwpVals,
      userId: userAccId,
    });
    this.calculations.push(calc5.calculation);
    this.emissionRecords.push(calc5.emissionRecord);

    const calc6 = executeCalculation({
      activityData: actElecAustinLoc,
      factorVersion: this.emissionFactorVersions.find((v) => v.id === 'efv-grid-loc-1')!,
      factorInputUnit: 'kWh',
      gwpSet: gwpAr6,
      gwpValues: gwpVals,
      userId: userAccId,
    });
    this.calculations.push(calc6.calculation);
    this.emissionRecords.push(calc6.emissionRecord);

    const calc7 = executeCalculation({
      activityData: actElecAustinMkt,
      factorVersion: this.emissionFactorVersions.find((v) => v.id === 'efv-grid-green-1')!,
      factorInputUnit: 'kWh',
      gwpSet: gwpAr6,
      gwpValues: gwpVals,
      userId: userSustId,
    });
    this.calculations.push(calc7.calculation);
    this.emissionRecords.push(calc7.emissionRecord);

    // Carbon Audit for Tenant A (FY2024)
    const auditFy2024Id = 'audit-acme-fy2024';
    this.audits.push({
      id: auditFy2024Id,
      organizationId: orgAId,
      reportingPeriodId: periodFy2024,
      status: 'REVIEW',
      initiatedBy: userSustId,
      notes: 'FY2024 comprehensive assurance review. Awaiting final reviewer sign-off.',
      createdAt: now,
      updatedAt: now,
    });

    this.auditChecklistItems.push(
      { id: 'chk-1', auditId: auditFy2024Id, code: 'CHK-BND-01', title: 'Organizational Boundary Confirmation (Operational Control)', isMandatory: true, isSatisfied: true, verifiedBy: userSustId, verifiedAt: now },
      { id: 'chk-2', auditId: auditFy2024Id, code: 'CHK-FAC-02', title: 'Facility Completeness (All 3 sites accounted for)', isMandatory: true, isSatisfied: true, verifiedBy: userSustId, verifiedAt: now },
      { id: 'chk-3', auditId: auditFy2024Id, code: 'CHK-DAT-03', title: 'Activity Data Ingestion (All 12 billing months confirmed)', isMandatory: true, isSatisfied: true, verifiedBy: userAccId, verifiedAt: now },
      { id: 'chk-4', auditId: auditFy2024Id, code: 'CHK-EVD-04', title: 'Primary Evidence Reconciliation (Utility invoices attached)', isMandatory: true, isSatisfied: true, verifiedBy: userAccId, verifiedAt: now },
      { id: 'chk-5', auditId: auditFy2024Id, code: 'CHK-FAC-05', title: 'Emission Factor Integrity (UK DEFRA & US EPA 2024 applied)', isMandatory: true, isSatisfied: true, verifiedBy: userAccId, verifiedAt: now },
      { id: 'chk-6', auditId: auditFy2024Id, code: 'CHK-GWP-06', title: 'GWP Reference Consistency (IPCC AR6 applied across all calculations)', isMandatory: true, isSatisfied: true, verifiedBy: userAccId, verifiedAt: now },
      { id: 'chk-7', auditId: auditFy2024Id, code: 'CHK-S2D-07', title: 'Scope 2 Dual-Reporting Verification (Location vs Market segregated)', isMandatory: true, isSatisfied: true, verifiedBy: userSustId, verifiedAt: now },
      { id: 'chk-8', auditId: auditFy2024Id, code: 'CHK-FIN-08', title: 'Reviewer Finding Resolution (Zero unresolved critical/high items)', isMandatory: true, isSatisfied: false, notes: 'Awaiting resolution of finding #F-01 before approval.' }
    );

    this.reviewFindings.push({
      id: 'f-01',
      auditId: auditFy2024Id,
      activityDataId: actRefrig.id,
      severity: 'MEDIUM',
      title: 'Missing Technician EPA 608 Certification for R-410A Leak',
      description: 'The refrigerant maintenance ticket has been attached, but the contractor technician certification credential is missing from the attachment bundle.',
      status: 'OPEN',
      createdBy: userRevId,
      createdAt: now,
    });

    this.reviewComments.push({
      id: 'c-01',
      auditId: auditFy2024Id,
      userId: userRevId,
      userName: 'David Sterling',
      userRole: 'Lead GHG Reviewer',
      commentText: 'Scope 1 stationary and Scope 2 dual reporting match utility meter statements. Please upload the technician credential to resolve finding #F-01 so we can proceed to APPROVED status.',
      createdAt: now,
    });

    // Targets & Reduction Projects for Tenant A
    const target2030Id = 'target-acme-2030';
    this.targets.push({
      id: target2030Id,
      organizationId: orgAId,
      name: '2030 Science-Based Reduction Target',
      baselinePeriodId: periodFy2024,
      targetPeriodId: 'period-2030',
      baselineValueT: 1045.2,
      targetValueT: 606.2,
      reductionPercentage: 42.0,
      status: 'ON_TRACK',
      ownerId: userSustId,
      notes: 'Aligned with 1.5°C Paris trajectory for Scope 1 & Scope 2 emissions.',
      createdAt: now,
    });

    this.reductionProjects.push(
      {
        id: 'rp-austin-solar',
        organizationId: orgAId,
        targetId: target2030Id,
        facilityId: facAustin,
        name: 'Austin Facility Rooftop Solar PV Installation',
        description: '750 kW commercial solar canopy offsetting 85% of daytime facility load.',
        baselineT: 316.18,
        expectedReductionT: 280.0,
        actualReductionT: 145.5,
        startDate: '2024-03-01',
        endDate: '2025-06-30',
        status: 'IN_PROGRESS',
        ownerId: userSustId,
        createdAt: now,
      },
      {
        id: 'rp-detroit-boiler',
        organizationId: orgAId,
        targetId: target2030Id,
        facilityId: facDetroit,
        name: 'Detroit Heavy Boiler Economizer & Flue Gas Heat Recovery',
        description: 'Flue gas heat recovery system reducing natural gas boiler demand by 14%.',
        baselineT: 91.44,
        expectedReductionT: 12.8,
        actualReductionT: 13.1,
        startDate: '2024-01-15',
        endDate: '2024-08-30',
        status: 'COMPLETED',
        ownerId: userAccId,
        createdAt: now,
      }
    );

    // ==========================================
    // 4. TENANT B: Vertex Tech Solutions (Proving Multi-Tenancy Isolation)
    // ==========================================
    const orgBId = 'org-tenant-b-2222';
    this.organizations.push({
      id: orgBId,
      name: 'Vertex Tech Solutions Inc',
      taxId: 'US-91-8392019',
      country: 'US',
      industry: 'Cloud Infrastructure & AI Systems',
      consolidationApproach: 'OPERATIONAL_CONTROL',
      baseYear: 2024,
      createdAt: now,
      updatedAt: now,
    });

    const userBId = 'user-vertex-admin';
    this.users.push({
      id: userBId,
      email: 'ceo@vertextech.com',
      passwordHash: defaultPasswordHash,
      fullName: 'Alex Vance (Vertex Admin)',
      isActive: true,
      createdAt: now,
    });

    this.memberships.push({
      id: 'mem-b-1',
      organizationId: orgBId,
      userId: userBId,
      role: 'COMPANY_ADMIN',
      isActive: true,
      createdAt: now,
    });

    const facSeattle = 'fac-vertex-seattle';
    this.facilities.push({
      id: facSeattle,
      organizationId: orgBId,
      name: 'Seattle Cloud Operations Hub',
      facilityCode: 'FAC-SEA-01',
      facilityType: 'DATA_CENTER',
      country: 'US',
      stateProvince: 'Washington',
      gridRegion: 'eGRID_NWPP',
      floorAreaM2: 12000,
      createdAt: now,
    });

    const periodVertex = 'period-vertex-fy2024';
    this.reportingPeriods.push({
      id: periodVertex,
      organizationId: orgBId,
      name: 'FY2024 Vertex Cloud GHG Inventory',
      startDate: '2024-01-01',
      endDate: '2024-12-31',
      status: 'OPEN',
      createdAt: now,
    });

    this.evidenceRecords.push({
      id: 'ev-vertex-confidential',
      organizationId: orgBId,
      fileName: 'Vertex_Private_Seattle_Power_Contract.pdf',
      fileSizeBytes: 1240000,
      mimeType: 'application/pdf',
      sha256Hash: '11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff',
      storagePath: `vault_storage/${orgBId}/Vertex_Private_Seattle_Power_Contract.pdf`,
      uploadedBy: userBId,
      createdAt: now,
    });
  }
}

// Export database singleton
export const db = new Database();
