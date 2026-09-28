/**
 * CarbonFlow — Client API Service
 * Handles Bearer token headers, consistent response envelopes, and error mapping.
 *
 * <p>Phase 8: the base URL comes from {@code VITE_JAVA_API_BASE_URL} (Vite
 * env injection) so the React app talks to the Java backend — never a
 * hardcoded origin. An empty/undefined value keeps same-origin relative
 * paths (correct behind a reverse proxy). Tokens are never placed in URLs.
 */

import {
  ActivityDataItem,
  AuditDetail,
  Boundary,
  Breakdown,
  Calculation,
  CarbonTarget,
  CarbonTargetInput,
  DashboardSummary,
  Department,
  EmissionRecord,
  EmissionsSummary,
  EvidenceRecord,
  Facility,
  InventorySnapshot,
  LegalEntity,
  PeriodSummary,
  PlatformTenant,
  ReductionProject,
  ReductionProjectInput,
  ReportingPeriod,
  TestSuiteResult,
  TrendInsightsResponse,
  UserAdmin,
  UserAdminInput,
} from '../types.ts';

const ACCESS_TOKEN_STORAGE_KEY = 'cf_access_token';
const REFRESH_TOKEN_STORAGE_KEY = 'cf_refresh_token';
const tokenStorage = typeof window !== 'undefined' ? window.localStorage : null;
let currentAccessToken: string | null = tokenStorage?.getItem(ACCESS_TOKEN_STORAGE_KEY) ?? null;
let currentRefreshToken: string | null = tokenStorage?.getItem(REFRESH_TOKEN_STORAGE_KEY) ?? null;
let unauthorizedHandler: (() => void) | null = null;
let refreshPromise: Promise<boolean> | null = null;

/**
 * Java backend origin from the build environment. Empty string = same-origin
 * (relative `/api/v1/...`); set {@code VITE_JAVA_API_BASE_URL} (e.g.
 * {@code http://localhost:8080}) to point the SPA at a dev Java server.
 */
const API_BASE_URL: string = (import.meta.env?.VITE_JAVA_API_BASE_URL as string | undefined) ?? '';

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

export function setUnauthorizedHandler(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

function notifyUnauthorized() {
  if (currentAccessToken || currentRefreshToken) unauthorizedHandler?.();
}

export function setAccessToken(token: string | null) {
  currentAccessToken = token;
  if (!tokenStorage) return;
  if (token) {
    tokenStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, token);
  } else {
    tokenStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
  }
}

export function setRefreshToken(token: string | null) {
  currentRefreshToken = token;
  if (!tokenStorage) return;
  if (token) {
    tokenStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, token);
  } else {
    tokenStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  }
}

export function setAuthTokens(accessToken: string | null, refreshToken: string | null) {
  setAccessToken(accessToken);
  setRefreshToken(refreshToken);
}

export function clearAuthTokens() {
  setAuthTokens(null, null);
}

export function getAccessToken(): string | null {
  return currentAccessToken;
}

export function getRefreshToken(): string | null {
  return currentRefreshToken;
}

async function refreshAccessToken(): Promise<boolean> {
  if (!currentRefreshToken) return false;
  if (refreshPromise) return refreshPromise;

  const tokenBeingRotated = currentRefreshToken;
  const operation = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: tokenBeingRotated }),
      });
      const json = await response.json().catch(() => ({}));

      if (
        !response.ok ||
        !json.success ||
        typeof json.data?.accessToken !== 'string' ||
        typeof json.data?.refreshToken !== 'string'
      ) {
        return false;
      }

      // Do not restore a response that arrived after logout or another session change.
      if (currentRefreshToken !== tokenBeingRotated) return false;
      setAuthTokens(json.data.accessToken, json.data.refreshToken);
      return true;
    } catch {
      return false;
    }
  })();

  refreshPromise = operation.finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

async function request<T>(endpoint: string, options: RequestInit = {}, allowRefresh = true): Promise<T> {
  const accessTokenAtStart = currentAccessToken;
  const headers = new Headers(options.headers || {});
  
  if (currentAccessToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${currentAccessToken}`);
  }

  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(`${API_BASE_URL}/api/v1${endpoint}`, {
    ...options,
    headers,
  });

  const json = await response.json().catch(() => ({}));
  if (!response.ok || !json.success) {
    const errorMsg = json.error?.message || `Request failed with status ${response.status}`;

    if (
      response.status === 401 &&
      allowRefresh &&
      endpoint !== '/auth/login' &&
      endpoint !== '/auth/refresh' &&
      currentRefreshToken
    ) {
      // Another request may already have rotated the token while this
      // response was in flight. Reuse that result instead of rotating again.
      if (currentAccessToken !== accessTokenAtStart) {
        return request<T>(endpoint, options, false);
      }

      const refreshed = await refreshAccessToken();
      if (refreshed) {
        return request<T>(endpoint, options, false);
      }
    }

    if (response.status === 401) notifyUnauthorized();
    throw new ApiError(errorMsg, response.status, json.error?.code);
  }

  return json.data as T;
}

export const api = {
  // Auth
  login: (email: string, password: string, organizationId?: string) =>
    request<any>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password, organizationId }),
    }),

  switchTenantOrRole: (targetOrgId: string, targetRole: string) =>
    request<any>('/auth/switch-tenant-or-role', {
      method: 'POST',
      body: JSON.stringify({ targetOrgId, targetRole }),
    }),

  getMe: () => request<any>('/auth/me'),

  logout: (refreshToken: string) =>
    request<{ revoked: boolean }>('/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }, false),

  // Organizations & Facilities
  getCurrentOrg: () => request<any>('/organizations/current'),
  updateCurrentOrg: (data: any) =>
    request<any>('/organizations/current', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  getFacilities: () => request<any[]>('/facilities'),
  createFacility: (data: any) =>
    request<any>('/facilities', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  getReportingPeriods: () => request<ReportingPeriod[]>('/reporting-periods'),

  // Reference Data
  getGwpSets: () => request<any[]>('/reference/gwp-sets'),
  getEmissionFactors: () => request<any[]>('/reference/emission-factors'),

  // Activity Data & Calculations
  getActivityData: (params?: { periodId?: string; facilityId?: string; scope?: string }) => {
    const query = new URLSearchParams();
    if (params?.periodId) query.set('periodId', params.periodId);
    if (params?.facilityId) query.set('facilityId', params.facilityId);
    if (params?.scope) query.set('scope', params.scope);
    const qs = query.toString() ? `?${query.toString()}` : '';
    return request<any[]>(`/activity-data${qs}`);
  },
  createActivityData: (data: any) =>
    request<any>('/activity-data', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  runCalculation: (activityDataId: string, factorVersionId?: string, gwpSetId?: string) =>
    request<any>('/calculations/run', {
      method: 'POST',
      body: JSON.stringify({ activityDataId, factorVersionId, gwpSetId }),
    }),
  batchRunCalculations: (reportingPeriodId?: string) =>
    request<any>('/calculations/batch-run', {
      method: 'POST',
      body: JSON.stringify({ reportingPeriodId }),
    }),

  // Emissions
  getEmissions: (periodId?: string) => {
    const qs = periodId ? `?periodId=${encodeURIComponent(periodId)}` : '';
    return request<{ records: EmissionRecord[]; summary: EmissionsSummary }>(`/emissions${qs}`);
  },
  getCalculation: (calculationId: string) =>
    request<Calculation>(`/calculations/${encodeURIComponent(calculationId)}`),

  // Audits & Assurance
  getAudits: () => request<any[]>('/audits'),
  getAuditDetail: (id: string) => request<any>(`/audits/${id}`),
  transitionAudit: (id: string, targetState: string, reason?: string) =>
    request<any>(`/audits/${id}/transition`, {
      method: 'POST',
      body: JSON.stringify({ targetState, reason }),
    }),
  verifyChecklistItem: (auditId: string, itemId: string, isSatisfied: boolean, notes?: string) =>
    request<any>(`/audits/${auditId}/checklist/${itemId}/verify`, {
      method: 'POST',
      body: JSON.stringify({ isSatisfied, notes }),
    }),
  createFinding: (auditId: string, data: any) =>
    request<any>(`/audits/${auditId}/findings`, {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  resolveFinding: (auditId: string, findingId: string) =>
    request<any>(`/audits/${auditId}/findings/${findingId}/resolve`, {
      method: 'POST',
    }),
  addAuditComment: (auditId: string, commentText: string) =>
    request<any>(`/audits/${auditId}/comments`, {
      method: 'POST',
      body: JSON.stringify({ commentText }),
    }),

  // Evidence Management
  getEvidence: () => request<any[]>('/evidence'),
  uploadEvidence: (file: File, entityType?: string, entityId?: string) => {
    const formData = new FormData();
    formData.append('file', file);
    if (entityType) formData.append('entityType', entityType);
    if (entityId) formData.append('entityId', entityId);

    return request<any>('/evidence/upload', {
      method: 'POST',
      body: formData,
    });
  },
  downloadEvidence: async (evidenceId: string, fileName: string): Promise<void> => {
    const response = await fetch(`${API_BASE_URL}/api/v1/evidence/${encodeURIComponent(evidenceId)}/download`, {
      headers: { Authorization: `Bearer ${getAccessToken() || ''}` },
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      throw new ApiError(body.error?.message || 'Evidence download failed.', response.status, body.error?.code);
    }
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(objectUrl);
  },

  // Inventory & Targets
  getInventory: () => request<InventorySnapshot[]>('/inventory'),
  createInventorySnapshot: (reportingPeriodId: string) =>
    request<InventorySnapshot>('/inventory/snapshot', {
      method: 'POST',
      body: JSON.stringify({ reportingPeriodId }),
    }),
  lockInventorySnapshot: (snapshotId: string) =>
    request<InventorySnapshot>(`/inventory/${encodeURIComponent(snapshotId)}/lock`, {
      method: 'POST',
    }),
  getTargets: () => request<CarbonTarget[]>('/targets'),
  createTarget: (data: CarbonTargetInput) =>
    request<CarbonTarget>('/targets', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  updateTarget: (targetId: string, data: Partial<CarbonTargetInput>) =>
    request<CarbonTarget>(`/targets/${encodeURIComponent(targetId)}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  getReductionProjects: () => request<ReductionProject[]>('/reduction-projects'),
  createReductionProject: (data: ReductionProjectInput) =>
    request<ReductionProject>('/reduction-projects', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  updateReductionProject: (projectId: string, data: Partial<ReductionProjectInput>) =>
    request<ReductionProject>(`/reduction-projects/${encodeURIComponent(projectId)}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),

  // Platform administration (PLATFORM_ADMIN only)
  getPlatformTenants: (status?: string) => {
    const qs = status ? `?status=${encodeURIComponent(status)}` : '';
    return request<PlatformTenant[]>(`/platform/tenants${qs}`);
  },
  getPlatformTenant: (organizationId: string) =>
    request<PlatformTenant>(`/platform/tenants/${encodeURIComponent(organizationId)}`),
  approveTenant: (organizationId: string) =>
    request<PlatformTenant>(`/platform/tenants/${encodeURIComponent(organizationId)}/approve`, {
      method: 'POST',
    }),
  rejectTenant: (organizationId: string, note?: string) =>
    request<PlatformTenant>(`/platform/tenants/${encodeURIComponent(organizationId)}/reject`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),
  suspendTenant: (organizationId: string, note?: string) =>
    request<PlatformTenant>(`/platform/tenants/${encodeURIComponent(organizationId)}/suspend`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),

  // Company administration (users)
  getUsers: () => request<UserAdmin[]>('/users'),
  createUser: (data: UserAdminInput) =>
    request<UserAdmin>('/users', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  updateUser: (userId: string, data: Partial<UserAdminInput>) =>
    request<UserAdmin>(`/users/${encodeURIComponent(userId)}`, {
      method: 'PATCH',
      body: JSON.stringify(data),
    }),
  disableUser: (userId: string) =>
    request<UserAdmin>(`/users/${encodeURIComponent(userId)}/disable`, { method: 'POST' }),
  enableUser: (userId: string) =>
    request<UserAdmin>(`/users/${encodeURIComponent(userId)}/enable`, { method: 'POST' }),

  // Scope domain (legal entities, departments, boundaries, reporting periods)
  getLegalEntities: () => request<LegalEntity[]>('/legal-entities'),
  getDepartments: () => request<Department[]>('/departments'),
  getBoundaries: () => request<Boundary[]>('/boundaries'),
  createReportingPeriod: (data: { name: string; startDate: string; endDate: string }) =>
    request<ReportingPeriod>('/reporting-periods', {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  // Activity data update/submit (greenfield Java verbs)
  updateActivityData: (activityId: string, data: Record<string, unknown>) =>
    request<ActivityDataItem>(`/activity-data/${encodeURIComponent(activityId)}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
  submitActivityData: (activityId: string) =>
    request<ActivityDataItem>(`/activity-data/${encodeURIComponent(activityId)}/submit`, {
      method: 'POST',
    }),
  createAudit: (reportingPeriodId: string) =>
    request<AuditDetail>('/audits', {
      method: 'POST',
      body: JSON.stringify({ reportingPeriodId }),
    }),

  // Evidence detail/versions/link/delete
  getEvidenceDetail: (evidenceId: string) =>
    request<EvidenceRecord>(`/evidence/${encodeURIComponent(evidenceId)}`),
  linkEvidence: (evidenceId: string, entityType: string, entityId: string) =>
    request<EvidenceRecord>(`/evidence/${encodeURIComponent(evidenceId)}/link`, {
      method: 'POST',
      body: JSON.stringify({ entityType, entityId }),
    }),
  deleteEvidence: (evidenceId: string) =>
    request<{ deleted: boolean }>(`/evidence/${encodeURIComponent(evidenceId)}`, { method: 'DELETE' }),

  // Analytics & Reports
  getDashboardAnalytics: () => request<DashboardSummary>('/analytics/dashboard'),
  getTrendInsights: (refresh?: boolean) =>
    request<TrendInsightsResponse>('/analytics/trend-insights', {
      method: 'POST',
      body: JSON.stringify({ refresh }),
    }),
  getPeriodSummary: (periodId: string) =>
    request<PeriodSummary>(`/analytics/periods/${encodeURIComponent(periodId)}/summary`),
  getBreakdown: (dimension: string, periodId?: string) => {
    const query = new URLSearchParams();
    query.set('dimension', dimension);
    if (periodId) query.set('periodId', periodId);
    return request<Breakdown>(`/analytics/breakdown?${query.toString()}`);
  },
  getTestSuiteResults: () => request<TestSuiteResult>('/test-suite/run'),
  exportEmissionReportCsv: async (filters?: {
    periodId?: string;
    facilityId?: string;
    scope?: string;
    scope2Type?: string;
  }) => {
    const accessTokenAtStart = currentAccessToken;
    const sendExportRequest = () => {
      const headers: Record<string, string> = {};
      if (currentAccessToken) {
        headers['Authorization'] = `Bearer ${currentAccessToken}`;
      }
      const query = new URLSearchParams();
      if (filters?.periodId) query.set('periodId', filters.periodId);
      if (filters?.facilityId) query.set('facilityId', filters.facilityId);
      if (filters?.scope) query.set('scope', filters.scope);
      if (filters?.scope2Type) query.set('scope2Type', filters.scope2Type);
      const qs = query.toString() ? `?${query.toString()}` : '';
      return fetch(`${API_BASE_URL}/api/v1/reports/export-csv${qs}`, { headers });
    };

    let response = await sendExportRequest();
    if (!response.ok && response.status === 401 && currentRefreshToken) {
      if (currentAccessToken !== accessTokenAtStart) {
        response = await sendExportRequest();
      } else {
        const refreshed = await refreshAccessToken();
        if (refreshed) response = await sendExportRequest();
      }
    }

    if (!response.ok) {
      if (response.status === 401) notifyUnauthorized();
      throw new ApiError(`Failed to export CSV report: status ${response.status}`, response.status);
    }
    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `carbonflow_emission_inventory_${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.appendChild(a);
    a.click();
    window.URL.revokeObjectURL(url);
    document.body.removeChild(a);
  },
};
