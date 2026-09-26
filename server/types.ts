/**
 * CarbonFlow — Server Domain Types & Canonical Constants
 */

export type RoleName =
  | 'COMPANY_ADMIN'
  | 'SUSTAINABILITY_MANAGER'
  | 'CARBON_ACCOUNTANT'
  | 'DATA_OWNER'
  | 'FACILITY_MANAGER'
  | 'REVIEWER'
  | 'MANAGEMENT'
  | 'ASSURANCE_PROVIDER'
  | 'PLATFORM_ADMIN';

export type PermissionCode =
  | 'organization.read'
  | 'organization.update'
  | 'users.read'
  | 'users.create'
  | 'users.update'
  | 'users.disable'
  | 'facilities.read'
  | 'facilities.create'
  | 'facilities.update'
  | 'facilities.delete'
  | 'reporting_periods.read'
  | 'reporting_periods.create'
  | 'reporting_periods.update'
  | 'activity_data.read'
  | 'activity_data.create'
  | 'activity_data.update'
  | 'activity_data.submit'
  | 'emission_factors.read'
  | 'emission_factors.manage'
  | 'calculations.read'
  | 'calculations.create'
  | 'evidence.read'
  | 'evidence.upload'
  | 'evidence.delete'
  | 'evidence.version'
  | 'audits.read'
  | 'audits.create'
  | 'audits.submit'
  | 'audits.review'
  | 'audits.approve'
  | 'audits.lock'
  | 'inventory.read'
  | 'inventory.create'
  | 'inventory.lock'
  | 'targets.read'
  | 'targets.create'
  | 'targets.update'
  | 'reduction_projects.read'
  | 'reduction_projects.create'
  | 'reduction_projects.update'
  | 'reports.read'
  | 'analytics.read'
  | 'platform.tenants.read'
  | 'platform.tenants.manage';

export type AuditStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'DATA_COLLECTION'
  | 'VALIDATION'
  | 'REVIEW'
  | 'APPROVED'
  | 'AUDIT_READY'
  | 'LOCKED'
  | 'CORRECTION_REQUESTED'
  | 'REJECTED';

export type ScopeType = 'SCOPE_1' | 'SCOPE_2' | 'SCOPE_3';
export type Scope2Method = 'LOCATION_BASED' | 'MARKET_BASED';

export interface User {
  id: string;
  email: string;
  passwordHash: string;
  fullName: string;
  isActive: boolean;
  createdAt: string;
}

export interface Organization {
  id: string;
  name: string;
  taxId?: string;
  country: string;
  industry: string;
  consolidationApproach: 'OPERATIONAL_CONTROL' | 'FINANCIAL_CONTROL' | 'EQUITY_SHARE';
  baseYear: number;
  createdAt: string;
  updatedAt: string;
}

export interface OrganizationMembership {
  id: string;
  organizationId: string;
  userId: string;
  role: RoleName;
  isActive: boolean;
  createdAt: string;
}

export interface Facility {
  id: string;
  organizationId: string;
  legalEntityId?: string;
  name: string;
  facilityCode: string;
  facilityType: 'MANUFACTURING' | 'OFFICE' | 'DATA_CENTER' | 'WAREHOUSE' | 'RETAIL' | 'LOGISTICS';
  country: string;
  stateProvince?: string;
  gridRegion: string;
  floorAreaM2?: number;
  createdAt: string;
}

export interface LegalEntity {
  id: string;
  organizationId: string;
  name: string;
  jurisdiction: string;
  registrationNumber?: string;
  ownershipPercentage: number;
  createdAt: string;
}

export interface ReportingPeriod {
  id: string;
  organizationId: string;
  name: string;
  startDate: string;
  endDate: string;
  status: 'OPEN' | 'UNDER_AUDIT' | 'LOCKED';
  createdAt: string;
}

export interface GwpSet {
  id: string;
  code: string;
  name: string;
  assessmentReport: string;
  publicationYear: number;
  isDefault: boolean;
}

export interface GwpValue {
  id: string;
  gwpSetId: string;
  gas: string;
  gwp100yr: number;
}

export interface EmissionFactor {
  id: string;
  scope: ScopeType;
  category: string;
  activityType: string;
  fuelOrActivity: string;
  inputUnit: string;
}

export interface EmissionFactorVersion {
  id: string;
  emissionFactorId: string;
  versionNumber: number;
  co2Factor: number;
  ch4Factor: number;
  n2oFactor: number;
  co2eFactor: number;
  factorUnit: string;
  source: string;
  sourceYear: number;
  geography: string;
  status: 'ACTIVE' | 'SUPERSEDED' | 'ARCHIVED';
  effectiveStart: string;
  effectiveEnd?: string;
}

export interface ActivityData {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  facilityId: string;
  departmentId?: string;
  scope: ScopeType;
  category: string;
  activityType: string;
  quantity: number;
  unit: string;
  startDate: string;
  endDate: string;
  source: string;
  status: 'DRAFT' | 'SUBMITTED' | 'VALIDATED' | 'CALCULATED' | 'LOCKED';
  notes?: string;
  submittedBy?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CalculationGasResult {
  gas: string;
  rawGasEmissionKg: number;
  gwpApplied: number;
  co2eKg: number;
}

export interface Calculation {
  id: string;
  organizationId: string;
  activityDataId: string;
  reportingPeriodId: string;
  factorVersionId: string;
  gwpSetId: string;
  originalQuantity: number;
  originalUnit: string;
  normalizedQuantity: number;
  normalizedUnit: string;
  conversionFactor: number;
  factorValue: number;
  factorUnit: string;
  factorSource: string;
  factorVersion: number;
  gwpName: string;
  totalCo2eKg: number;
  totalCo2eTonnes: number;
  calculationHash: string;
  gasResults: CalculationGasResult[];
  calculatedAt: string;
  calculatedBy?: string;
}

export interface EmissionRecord {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  facilityId: string;
  calculationId: string;
  scope: ScopeType;
  category: string;
  scope2Type?: Scope2Method;
  co2eTonnes: number;
  status: 'ACTIVE' | 'SUPERSEDED' | 'VOIDED';
  createdAt: string;
}

export interface CarbonAudit {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  status: AuditStatus;
  initiatedBy?: string;
  approvedBy?: string;
  lockedAt?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AuditChecklistItem {
  id: string;
  auditId: string;
  code: string;
  title: string;
  isMandatory: boolean;
  isSatisfied: boolean;
  verifiedBy?: string;
  verifiedAt?: string;
  notes?: string;
}

export interface ReviewFinding {
  id: string;
  auditId: string;
  activityDataId?: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  title: string;
  description: string;
  status: 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'DISMISSED';
  createdBy?: string;
  resolvedBy?: string;
  createdAt: string;
}

export interface ReviewComment {
  id: string;
  auditId: string;
  userId: string;
  userName: string;
  userRole: string;
  commentText: string;
  createdAt: string;
}

export interface EvidenceRecord {
  id: string;
  organizationId: string;
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  sha256Hash: string;
  storagePath: string;
  uploadedBy?: string;
  createdAt: string;
}

export interface EvidenceLink {
  id: string;
  evidenceRecordId: string;
  entityType: 'ACTIVITY_DATA' | 'AUDIT' | 'FACILITY';
  entityId: string;
  createdAt: string;
}

export interface InventorySnapshot {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  auditId?: string;
  scope1Co2eT: number;
  scope2LocationCo2eT: number;
  scope2MarketCo2eT: number;
  biogenicCo2eT: number;
  status: 'ACTIVE' | 'LOCKED' | 'REVERTED';
  snapshotHash: string;
  createdAt: string;
}

export interface CarbonTarget {
  id: string;
  organizationId: string;
  name: string;
  baselinePeriodId: string;
  targetPeriodId: string;
  baselineValueT: number;
  targetValueT: number;
  reductionPercentage: number;
  status: 'ON_TRACK' | 'BEHIND' | 'ACHIEVED' | 'EXPIRED';
  ownerId?: string;
  notes?: string;
  createdAt: string;
}

export interface ReductionProject {
  id: string;
  organizationId: string;
  targetId?: string;
  facilityId?: string;
  name: string;
  description: string;
  baselineT: number;
  expectedReductionT: number;
  actualReductionT: number;
  startDate: string;
  endDate: string;
  status: 'PLANNED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  ownerId?: string;
  createdAt: string;
}

export interface TenantContext {
  organizationId: string;
  userId: string;
  role: RoleName;
  permissions: PermissionCode[];
}
