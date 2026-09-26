/**
 * CarbonFlow — Client API Service
 * Handles Bearer token headers, consistent response envelopes, and error mapping.
 */

import { TrendInsightsResponse } from '../types.ts';

const ACCESS_TOKEN_STORAGE_KEY = 'cf_access_token';
const REFRESH_TOKEN_STORAGE_KEY = 'cf_refresh_token';
const tokenStorage = typeof window !== 'undefined' ? window.localStorage : null;
let currentAccessToken: string | null = tokenStorage?.getItem(ACCESS_TOKEN_STORAGE_KEY) ?? null;
let currentRefreshToken: string | null = tokenStorage?.getItem(REFRESH_TOKEN_STORAGE_KEY) ?? null;
let unauthorizedHandler: (() => void) | null = null;
let refreshPromise: Promise<boolean> | null = null;

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
      const response = await fetch('/api/v1/auth/refresh', {
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

  const response = await fetch(`/api/v1${endpoint}`, {
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
  getReportingPeriods: () => request<any[]>('/reporting-periods'),
  createReportingPeriod: (data: any) =>
    request<any>('/reporting-periods', {
      method: 'POST',
      body: JSON.stringify(data),
    }),

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
    const qs = periodId ? `?periodId=${periodId}` : '';
    return request<any>(`/emissions${qs}`);
  },

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
    const response = await fetch(`/api/v1/evidence/${encodeURIComponent(evidenceId)}/download`, {
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
  getInventory: () => request<any[]>('/inventory'),
  createInventorySnapshot: (reportingPeriodId: string) =>
    request<any>('/inventory/snapshot', {
      method: 'POST',
      body: JSON.stringify({ reportingPeriodId }),
    }),
  getTargets: () => request<any[]>('/targets'),
  createTarget: (data: any) =>
    request<any>('/targets', {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  getReductionProjects: () => request<any[]>('/reduction-projects'),
  createReductionProject: (data: any) =>
    request<any>('/reduction-projects', {
      method: 'POST',
      body: JSON.stringify(data),
    }),

  // Analytics & Reports
  getDashboardAnalytics: () => request<any>('/analytics/dashboard'),
  getTrendInsights: (refresh?: boolean) =>
    request<TrendInsightsResponse>('/analytics/trend-insights', {
      method: 'POST',
      body: JSON.stringify({ refresh }),
    }),
  getTestSuiteResults: () => request<any>('/test-suite/run'),
  exportEmissionReportCsv: async () => {
    const accessTokenAtStart = currentAccessToken;
    const sendExportRequest = () => {
      const headers: Record<string, string> = {};
      if (currentAccessToken) {
        headers['Authorization'] = `Bearer ${currentAccessToken}`;
      }
      return fetch('/api/v1/reports/export-csv', { headers });
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
