/**
 * CarbonFlow — Frontend Domain Types & Interfaces
 *
 * Phase 8: request/response models mirror the Java DTOs
 * (`backend-java/src/main/java/com/carbonflow/dto|model`) so the frontend
 * binds directly to the backend contract. The frontend performs display-only
 * formatting only — no business calculation lives here.
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

export type OrganizationStatus =
  | 'PENDING_ACTIVATION'
  | 'ACTIVE'
  | 'REJECTED'
  | 'SUSPENDED';

export type NavView =
  | 'DASHBOARD'
  | 'BOUNDARIES'
  | 'ACTIVITY_DATA'
  | 'EMISSIONS'
  | 'FACTORS'
  | 'AUDIT'
  | 'EVIDENCE'
  | 'INVENTORY'
  | 'ANALYTICS'
  | 'TARGETS'
  | 'ADMIN'
  | 'PLATFORM_ADMIN'
  | 'TEST_SUITE';

export interface User {
  id: string;
  email: string;
  fullName: string;
}

export interface Organization {
  id: string;
  name: string;
  taxId?: string;
  country: string;
  industry: string;
  consolidationApproach: 'OPERATIONAL_CONTROL' | 'FINANCIAL_CONTROL' | 'EQUITY_SHARE';
  baseYear: number;
  status?: OrganizationStatus;
  statusChangedAt?: string;
  statusChangedBy?: string;
  statusNote?: string;
}

export interface AuthMembership {
  organizationId: string;
  organizationName: string;
  role: RoleName;
}

export type AuthState = 'AUTH_LOADING' | 'AUTHENTICATED' | 'UNAUTHENTICATED';

export interface AuthSession {
  user: User;
  organization: Organization;
  role: RoleName;
  permissions: string[];
  memberships: AuthMembership[];
}

export interface Facility {
  id: string;
  organizationId: string;
  legalEntityId?: string;
  name: string;
  facilityCode: string;
  facilityType: string;
  country: string;
  stateProvince?: string;
  gridRegion: string;
  floorAreaM2?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface ReportingPeriod {
  id: string;
  organizationId: string;
  name: string;
  startDate: string;
  endDate: string;
  status: 'OPEN' | 'UNDER_AUDIT' | 'LOCKED';
  createdAt?: string;
  updatedAt?: string;
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
  factorVersionId?: string;
  factorId?: string;
  gwpSetId?: string;
  originalQuantity?: number;
  originalUnit?: string;
  normalizedQuantity?: number;
  normalizedUnit?: string;
  conversionFactor?: number;
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

export interface ActivityDataItem {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  facilityId: string;
  departmentId?: string;
  facilityName: string;
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
  createdAt?: string;
  updatedAt?: string;
  evidence?: {
    id: string;
    fileName: string;
    fileSizeBytes: number;
    sha256Hash: string;
  } | null;
  calculation?: Calculation | null;
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
  status: string;
  createdAt: string;
}

/** `GET /emissions` summary — both Scope 2 perspectives never summed. */
export interface EmissionsSummary {
  scope1Tonnes: number;
  scope2LocationTonnes: number;
  scope2MarketTonnes: number;
  totalLocationBasedTonnes: number;
  totalMarketBasedTonnes: number;
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
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  title: string;
  description: string;
  status: 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'DISMISSED';
  createdBy?: string;
  createdAt: string;
}

export interface ReviewComment {
  id: string;
  userName: string;
  userRole: string;
  commentText: string;
  createdAt: string;
}

export interface AuditDetail {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  status: AuditStatus;
  notes?: string;
  checklist: AuditChecklistItem[];
  findings: ReviewFinding[];
  comments: ReviewComment[];
  period?: ReportingPeriod;
}

export interface EvidenceRecord {
  id: string;
  organizationId: string;
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  sha256Hash: string;
  uploadedBy?: string;
  createdAt: string;
  links?: { entityType: string; entityId: string }[];
}

// ------------------------------------------------------------------
// Phase 7 reporting/portfolio DTOs (Java parity)
// ------------------------------------------------------------------

/** `GET /analytics/dashboard` — mirrors DashboardSummaryDto. */
export interface DashboardSummary {
  emissions: {
    scope1Tonnes: number;
    scope2LocationTonnes: number;
    scope2MarketTonnes: number;
    scope3Tonnes: number;
    totalLocationBasedTonnes: number;
    totalMarketBasedTonnes: number;
  };
  auditStatus: AuditStatus;
  auditHealth: {
    checklistSatisfied: number;
    checklistTotal: number;
    openFindingsCount: number;
  };
  activityCount: number;
  targetsCount: number;
  reductionProjectsCount: number;
  categories: { category: string; tonnes: number; tonnesMarketBased: number }[];
  facilities: {
    id: string;
    name: string;
    code: string;
    scope1Tonnes: number;
    scope2Tonnes: number;
    scope2LocationTonnes: number;
    scope2MarketTonnes: number;
    totalTonnes: number;
    totalMarketBasedTonnes: number;
  }[];
  periodTrends?: PeriodTrendItem[];
}

export interface PeriodTrendItem {
  periodId: string;
  periodName: string;
  shortName: string;
  startDate?: string;
  endDate?: string;
  scope1Tonnes: number;
  scope2LocationTonnes: number;
  scope2MarketTonnes: number;
  scope3Tonnes: number;
  totalLocationBasedTonnes: number;
  totalMarketBasedTonnes: number;
}

/** `GET /analytics/periods/:id/summary` — mirrors PeriodSummaryDto. */
export interface PeriodSummary {
  organizationId: string;
  period: {
    id: string;
    name: string;
    startDate: string;
    endDate: string;
    status: string;
  };
  totals: {
    scope1Tonnes: number;
    scope2LocationTonnes: number;
    scope2MarketTonnes: number;
    scope3Tonnes: number;
    totalLocationBasedTonnes: number;
    totalMarketBasedTonnes: number;
  };
  counts: {
    activityData: number;
    calculations: number;
    emissionRecords: number;
  };
  coverage: {
    facilitiesTotal: number;
    facilitiesWithEmissions: number;
  };
  governance: {
    locked: boolean;
    auditId: string | null;
    auditStatus: string | null;
  };
}

/** `GET /analytics/breakdown` — mirrors BreakdownDto. */
export interface Breakdown {
  dimension: string;
  periodId: string | null;
  rows: BreakdownRow[];
}

export interface BreakdownRow {
  key: string;
  label: string;
  scope1Tonnes: number;
  scope2LocationTonnes: number;
  scope2MarketTonnes: number;
  scope3Tonnes: number;
  totalLocationBasedTonnes: number;
  totalMarketBasedTonnes: number;
}

/** `GET /inventory` / snapshot create/lock — mirrors InventorySnapshot. */
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

/** `GET /targets` — mirrors CarbonTargetDto (progress computed by backend). */
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
  ownerId: string;
  notes?: string;
  createdAt: string;
  plannedReductionT: number | null;
  currentLocationBasedT: number | null;
  currentMarketBasedT: number | null;
  progressLocationPct: number | null;
  progressMarketPct: number | null;
  hasPersistedEmissions: boolean;
}

export interface CarbonTargetInput {
  name: string;
  baselinePeriodId: string;
  targetPeriodId: string;
  baselineValueT: number;
  targetValueT: number;
  reductionPercentage: number;
  status?: string;
  notes?: string;
}

/** `GET /reduction-projects` — mirrors ReductionProject. */
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

export interface ReductionProjectInput {
  name: string;
  description?: string;
  facilityId?: string;
  targetId?: string;
  baselineT?: number;
  expectedReductionT?: number;
  actualReductionT?: number;
  startDate: string;
  endDate?: string;
  status?: string;
}

/** `GET /platform/tenants` — Organization + V8 audit columns. */
export interface PlatformTenant {
  id: string;
  name: string;
  taxId?: string;
  country: string;
  industry: string;
  consolidationApproach: string;
  baseYear: number;
  status: OrganizationStatus;
  createdAt: string;
  updatedAt: string;
  statusChangedAt?: string;
  statusChangedBy?: string;
  statusNote?: string;
}

/** `GET /users` — tenant user administration row. */
export interface UserAdmin {
  id: string;
  email: string;
  fullName: string;
  role: RoleName;
  active: boolean;
  createdAt?: string;
  lastLoginAt?: string;
}

export interface UserAdminInput {
  email: string;
  password: string;
  fullName: string;
  role: RoleName;
}

/** `GET /test-suite/run` — platform self-test result. */
export interface TestSuiteResult {
  total: number;
  passed: number;
  failed: number;
  results: {
    id: string;
    category: string;
    name: string;
    passed: boolean;
    details: string;
  }[];
}

/** `GET /reference/gwp-sets` — GWP set with per-gas values. */
export interface GwpSet {
  id: string;
  code: string;
  name: string;
  assessmentReport?: string;
  publicationYear: number;
  isDefault: boolean;
  values: { gas: string; gwp100yr: number }[];
}

/** `GET /reference/emission-factors` — factor with versioned values. */
export interface EmissionFactorVersion {
  id: string;
  versionNumber: number;
  co2eFactor: number;
  factorUnit: string;
  source: string;
  sourceYear: number;
  geography: string;
  status: string;
}

export interface EmissionFactor {
  id: string;
  scope: ScopeType;
  category: string;
  activityType: string;
  fuelOrActivity: string;
  inputUnit: string;
  versions: EmissionFactorVersion[];
}

export interface LegalEntity {
  id: string;
  organizationId: string;
  name: string;
  jurisdiction: string;
  registrationNumber?: string;
  ownershipPercentage?: number;
}

export interface Department {
  id: string;
  organizationId: string;
  facilityId: string;
  name: string;
}

export interface Boundary {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  consolidationApproach: string;
  notes?: string;
  facilityIds: string[];
}

// ------------------------------------------------------------------
// Legacy trend-insights response (Java TrendInsightsDto parity)
// ------------------------------------------------------------------

export interface EmissionAnomaly {
  id: string;
  type: 'SPIKE' | 'DIVERGENCE' | 'UNUSUAL_PATTERN' | 'DRIFT';
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  title: string;
  description: string;
  affectedPeriod: string;
  scope: string;
  metricImpact: string;
}

export interface ReductionOpportunity {
  id: string;
  category: 'ENERGY_EFFICIENCY' | 'RENEWABLE_PROCUREMENT' | 'FLEET_ELECTRIFICATION' | 'PROCESS_OPTIMIZATION' | 'SUPPLY_CHAIN';
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  title: string;
  description: string;
  estimatedReductionTonnes: number;
  paybackPeriod: string;
  feasibility: 'HIGH' | 'MEDIUM' | 'LOW';
  ghgProtocolGuidance: string;
}

export interface TrendAnalysisSummary {
  headline: string;
  overallTrajectory: 'DECLINING' | 'PLATEAUING' | 'INCREASING' | 'VOLATILE';
  confidenceScore: number;
  periodRange: string;
  keyObservations: string[];
}

export interface TrendInsightsResponse {
  summary: TrendAnalysisSummary;
  anomalies: EmissionAnomaly[];
  reductionOpportunities: ReductionOpportunity[];
  generatedAt: string;
  modelUsed: string;
}
