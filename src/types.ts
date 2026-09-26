/**
 * CarbonFlow — Frontend Domain Types & Interfaces
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

export type NavView =
  | 'DASHBOARD'
  | 'BOUNDARIES'
  | 'ACTIVITY_DATA'
  | 'EMISSIONS'
  | 'FACTORS'
  | 'AUDIT'
  | 'EVIDENCE'
  | 'TARGETS'
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
  name: string;
  facilityCode: string;
  facilityType: string;
  country: string;
  stateProvince?: string;
  gridRegion: string;
  floorAreaM2?: number;
}

export interface ReportingPeriod {
  id: string;
  organizationId: string;
  name: string;
  startDate: string;
  endDate: string;
  status: 'OPEN' | 'UNDER_AUDIT' | 'LOCKED';
}

export interface CalculationGasResult {
  gas: string;
  rawGasEmissionKg: number;
  gwpApplied: number;
  co2eKg: number;
}

export interface Calculation {
  id: string;
  activityDataId: string;
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
}

export interface ActivityDataItem {
  id: string;
  organizationId: string;
  reportingPeriodId: string;
  facilityId: string;
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

export interface TargetItem {
  id: string;
  name: string;
  baselineValueT: number;
  targetValueT: number;
  reductionPercentage: number;
  status: 'ON_TRACK' | 'BEHIND' | 'ACHIEVED';
  notes?: string;
}

export interface ReductionProjectItem {
  id: string;
  name: string;
  description: string;
  baselineT: number;
  expectedReductionT: number;
  actualReductionT: number;
  status: 'PLANNED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  startDate: string;
  endDate: string;
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
  totalLocationBasedTonnes: number;
  totalMarketBasedTonnes: number;
}

export interface DashboardSummary {
  emissions: {
    scope1Tonnes: number;
    scope2LocationTonnes: number;
    scope2MarketTonnes: number;
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
  categories: { category: string; tonnes: number }[];
  facilities: {
    id: string;
    name: string;
    code: string;
    scope1Tonnes: number;
    scope2Tonnes: number;
    totalTonnes: number;
  }[];
  periodTrends?: PeriodTrendItem[];
}

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
