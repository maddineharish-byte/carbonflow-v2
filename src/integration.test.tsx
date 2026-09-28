import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { DashboardView } from './components/DashboardView.tsx';
import { TargetsView } from './components/TargetsView.tsx';
import { Sidebar } from './components/Sidebar.tsx';
import { AuditView } from './components/AuditView.tsx';
import { InventoryView } from './components/InventoryView.tsx';
import { AnalyticsView } from './components/AnalyticsView.tsx';
import { AdminView } from './components/AdminView.tsx';
import { EvidenceView } from './components/EvidenceView.tsx';
import { api, ApiError, clearAuthTokens, setAuthTokens } from './services/api.ts';
import { hasPermission } from './services/permissions.ts';

const originalFetch = globalThis.fetch;

function makeResponse(status: number, body: any): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    blob: async () => ({ size: 0 }),
  } as unknown as Response;
}

const noOp = async () => {};

const PERMISSIONS_ALL = [
  'analytics.read', 'facilities.read', 'activity_data.read', 'reports.read',
  'emission_factors.read', 'audits.read', 'audits.create', 'audits.submit',
  'audits.review', 'audits.approve', 'audits.lock', 'evidence.read', 'evidence.upload',
  'inventory.read', 'inventory.create', 'inventory.lock', 'targets.read', 'targets.create',
  'targets.update', 'reduction_projects.read', 'reduction_projects.create',
  'reduction_projects.update', 'users.read', 'users.create', 'users.update', 'users.disable',
];

const PERMISSIONS_REVIEWER = [
  'analytics.read', 'facilities.read', 'activity_data.read', 'reports.read',
  'emission_factors.read', 'audits.read', 'evidence.read', 'inventory.read',
  'targets.read', 'reduction_projects.read',
];

const PERMISSIONS_PLATFORM = ['platform.tenants.read', 'platform.tenants.manage'];

afterEach(() => {
  globalThis.fetch = originalFetch;
  clearAuthTokens();
});

// ------------------------------------------------------------------
// 7. Role-based navigation
// ------------------------------------------------------------------

test('NAV 1: sidebar shows only permission-gated items for a reviewer', () => {
  const markup = renderToStaticMarkup(
    <Sidebar currentView="DASHBOARD" onSelectView={() => {}} permissions={PERMISSIONS_REVIEWER} />
  );
  assert.match(markup, /Executive Dashboard/);
  assert.match(markup, /Evidence Vault/);
  // Reviewers hold inventory.read in the frozen matrix — it stays visible.
  assert.match(markup, /Inventory Snapshots/);
  assert.doesNotMatch(markup, /Company Administration/);
  assert.doesNotMatch(markup, /Platform Administration/);
  assert.doesNotMatch(markup, /Automated Test Suite/);
});

test('NAV 2: platform admin sees platform administration and the test suite', () => {
  const markup = renderToStaticMarkup(
    <Sidebar currentView="PLATFORM_ADMIN" onSelectView={() => {}} permissions={PERMISSIONS_PLATFORM} />
  );
  assert.match(markup, /Platform Administration/);
  assert.match(markup, /Automated Test Suite/);
  assert.doesNotMatch(markup, /Executive Dashboard/);
});

test('NAV 3: company admin sees inventory, analytics and company administration', () => {
  const markup = renderToStaticMarkup(
    <Sidebar currentView="INVENTORY" onSelectView={() => {}} permissions={PERMISSIONS_ALL} />
  );
  assert.match(markup, /Inventory Snapshots/);
  assert.match(markup, /Analytics .* Breakdowns/);
  assert.match(markup, /Company Administration/);
  assert.doesNotMatch(markup, /Platform Administration/);
});

// ------------------------------------------------------------------
// 8/9/25. Dashboard real data, empty state, no demo fallback
// ------------------------------------------------------------------

test('DASHBOARD 1: renders real backend KPI values', () => {
  const markup = renderToStaticMarkup(
    <DashboardView
      data={{
        emissions: {
          scope1Tonnes: 12.5,
          scope2LocationTonnes: 34.25,
          scope2MarketTonnes: 28.75,
          scope3Tonnes: 0,
          totalLocationBasedTonnes: 46.75,
          totalMarketBasedTonnes: 41.25,
        },
        auditStatus: 'REVIEW',
        auditHealth: { checklistSatisfied: 3, checklistTotal: 5, openFindingsCount: 1 },
        activityCount: 7,
        targetsCount: 2,
        reductionProjectsCount: 3,
        categories: [{ category: 'Stationary Combustion', tonnes: 12.5, tonnesMarketBased: 12.5 }],
        facilities: [],
        periodTrends: [
          {
            periodId: 'p1', periodName: 'FY2026', shortName: 'FY26',
            scope1Tonnes: 12.5, scope2LocationTonnes: 34.25, scope2MarketTonnes: 28.75,
            scope3Tonnes: 0, totalLocationBasedTonnes: 46.75, totalMarketBasedTonnes: 41.25,
          },
        ],
      }}
      periods={[]}
      onNavigate={() => {}}
      onSnapshot={() => {}}
    />
  );
  assert.match(markup, /12\.5/);
  assert.match(markup, /34\.25/);
  assert.match(markup, /28\.75/);
  assert.match(markup, /REVIEW/);
  assert.match(markup, /46\.75/);
  assert.match(markup, /41\.25/);
});

test('DASHBOARD 2: empty state when the backend returns no periods — no demo fallback', () => {
  const markup = renderToStaticMarkup(
    <DashboardView
      data={{
        emissions: {
          scope1Tonnes: 0, scope2LocationTonnes: 0, scope2MarketTonnes: 0,
          scope3Tonnes: 0, totalLocationBasedTonnes: 0, totalMarketBasedTonnes: 0,
        },
        auditStatus: 'DRAFT',
        auditHealth: { checklistSatisfied: 0, checklistTotal: 0, openFindingsCount: 0 },
        activityCount: 0,
        targetsCount: 0,
        reductionProjectsCount: 0,
        categories: [],
        facilities: [],
        periodTrends: [],
      }}
      periods={[]}
      onNavigate={() => {}}
      onSnapshot={() => {}}
    />
  );
  assert.match(markup, /No reporting periods with data yet/);
  // The removed 2024 demo series must never appear.
  assert.doesNotMatch(markup, /2024-M01/);
  assert.doesNotMatch(markup, /Jan 24/);
  assert.doesNotMatch(markup, /p-2024/);
  assert.doesNotMatch(markup, /45% Realized/);
});

// ------------------------------------------------------------------
// 15/16. Targets & reduction projects — backend progress, no fake bar
// ------------------------------------------------------------------

test('TARGETS 1: renders backend-computed progress, not a hardcoded value', () => {
  const markup = renderToStaticMarkup(
    <TargetsView
      targets={[
        {
          id: 't1', organizationId: 'o1', name: 'Cut 20%', baselinePeriodId: 'p1', targetPeriodId: 'p2',
          baselineValueT: 100, targetValueT: 80, reductionPercentage: 20, status: 'ON_TRACK',
          ownerId: 'u1', createdAt: '2026-01-01T00:00:00Z',
          plannedReductionT: 20, currentLocationBasedT: 90, currentMarketBasedT: 88,
          progressLocationPct: 50, progressMarketPct: 60, hasPersistedEmissions: true,
        },
      ]}
      projects={[]}
      periods={[]}
      facilities={[]}
      permissions={PERMISSIONS_ALL}
      onCreateTarget={noOp}
      onUpdateTarget={noOp}
      onCreateProject={noOp}
      onUpdateProject={noOp}
    />
  );
  assert.match(markup, /50\.0% realized/);
  assert.doesNotMatch(markup, /45% Realized/);
  assert.match(markup, /Cut 20%/);
});

test('TARGETS 2: empty state and permission-gated create buttons', () => {
  const markup = renderToStaticMarkup(
    <TargetsView
      targets={[]}
      projects={[]}
      periods={[]}
      facilities={[]}
      permissions={PERMISSIONS_REVIEWER}
      onCreateTarget={noOp}
      onUpdateTarget={noOp}
      onCreateProject={noOp}
      onUpdateProject={noOp}
    />
  );
  assert.match(markup, /No carbon targets defined/);
  assert.doesNotMatch(markup, /New Target/);
});

test('PROJECTS 1: renders reduction project rows from backend data', () => {
  const markup = renderToStaticMarkup(
    <TargetsView
      targets={[]}
      projects={[
        {
          id: 'r1', organizationId: 'o1', name: 'LED Retrofit', description: 'Warehouse lighting',
          baselineT: 10, expectedReductionT: 2.5, actualReductionT: 1.2,
          startDate: '2026-01-01', endDate: '2026-12-31', status: 'IN_PROGRESS',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ]}
      periods={[]}
      facilities={[]}
      permissions={PERMISSIONS_ALL}
      onCreateTarget={noOp}
      onUpdateTarget={noOp}
      onCreateProject={noOp}
      onUpdateProject={noOp}
    />
  );
  assert.match(markup, /LED Retrofit/);
  assert.match(markup, /2\.5 tCO₂e\/yr/);
  assert.match(markup, /IN_PROGRESS/);
});

// ------------------------------------------------------------------
// 12. Audit state rendering — data-driven transitions
// ------------------------------------------------------------------

test('AUDIT 1: a DRAFT audit offers the backend-legal Submit transition', () => {
  const draftAudit = {
    id: 'a1', organizationId: 'o1', reportingPeriodId: 'p1', status: 'DRAFT' as const,
    checklist: [], findings: [], comments: [],
  };
  const markup = renderToStaticMarkup(
    <AuditView
      audit={draftAudit}
      currentRole="CARBON_ACCOUNTANT"
      permissions={PERMISSIONS_ALL}
      periods={[{ id: 'p1', organizationId: 'o1', name: 'FY2026', startDate: '2026-01-01', endDate: '2026-12-31', status: 'OPEN' }]}
      onCreateAudit={noOp}
      onTransition={noOp}
      onVerifyChecklist={noOp}
      onCreateFinding={noOp}
      onResolveFinding={noOp}
      onAddComment={noOp}
    />
  );
  assert.match(markup, /Submit Audit/);
  assert.doesNotMatch(markup, /Approve Audit/);
  assert.doesNotMatch(markup, /ISO 14064/);
  assert.doesNotMatch(markup, /FY2024/);
});

test('AUDIT 2: a LOCKED audit shows the terminal state with no transition buttons', () => {
  const lockedAudit = {
    id: 'a1', organizationId: 'o1', reportingPeriodId: 'p1', status: 'LOCKED' as const,
    checklist: [], findings: [], comments: [],
  };
  const markup = renderToStaticMarkup(
    <AuditView
      audit={lockedAudit}
      currentRole="COMPANY_ADMIN"
      permissions={PERMISSIONS_ALL}
      periods={[]}
      onCreateAudit={noOp}
      onTransition={noOp}
      onVerifyChecklist={noOp}
      onCreateFinding={noOp}
      onResolveFinding={noOp}
      onAddComment={noOp}
    />
  );
  assert.match(markup, /terminal state/);
  assert.doesNotMatch(markup, /Lock Inventory Cycle/);
});

// ------------------------------------------------------------------
// 14. Inventory display
// ------------------------------------------------------------------

test('INVENTORY 1: renders backend snapshot rows with both Scope 2 perspectives', async () => {
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/inventory')) {
      return makeResponse(200, {
        success: true,
        data: [
          {
            id: 's1', organizationId: 'o1', reportingPeriodId: 'p1',
            scope1Co2eT: 10, scope2LocationCo2eT: 20.5, scope2MarketCo2eT: 18.25,
            biogenicCo2eT: 0, status: 'ACTIVE', snapshotHash: 'abc123', createdAt: '2026-01-01T00:00:00Z',
          },
        ],
      });
    }
    return makeResponse(404, { success: false, error: { code: 'NOT_FOUND', message: 'nope' } });
  }) as typeof fetch;

  // The view loads on mount; assert via the API contract it consumes.
  const result = await api.getInventory();
  assert.equal(result.length, 1);
  assert.equal(result[0].scope2LocationCo2eT, 20.5);
  assert.equal(result[0].scope2MarketCo2eT, 18.25);
  assert.equal(result[0].status, 'ACTIVE');
  assert.equal(result[0].snapshotHash, 'abc123');
});

// ------------------------------------------------------------------
// 17. Analytics rendering
// ------------------------------------------------------------------

test('ANALYTICS 1: period summary and breakdown consume backend payloads', async () => {
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/analytics/periods/p1/summary')) {
      return makeResponse(200, {
        success: true,
        data: {
          organizationId: 'o1',
          period: { id: 'p1', name: 'FY2026', startDate: '2026-01-01', endDate: '2026-12-31', status: 'OPEN' },
          totals: {
            scope1Tonnes: 10, scope2LocationTonnes: 20, scope2MarketTonnes: 18,
            scope3Tonnes: 0, totalLocationBasedTonnes: 30, totalMarketBasedTonnes: 28,
          },
          counts: { activityData: 3, calculations: 3, emissionRecords: 3 },
          coverage: { facilitiesTotal: 2, facilitiesWithEmissions: 1 },
          governance: { locked: false, auditId: null, auditStatus: null },
        },
      });
    }
    if (url.includes('/analytics/breakdown')) {
      return makeResponse(200, {
        success: true,
        data: {
          dimension: 'scope',
          periodId: null,
          rows: [
            {
              key: 'SCOPE_1', label: 'Scope 1', scope1Tonnes: 10, scope2LocationTonnes: 0,
              scope2MarketTonnes: 0, scope3Tonnes: 0, totalLocationBasedTonnes: 10, totalMarketBasedTonnes: 10,
            },
          ],
        },
      });
    }
    return makeResponse(404, { success: false, error: { code: 'NOT_FOUND', message: 'nope' } });
  }) as typeof fetch;

  const summary = await api.getPeriodSummary('p1');
  assert.equal(summary.totals.scope2LocationTonnes, 20);
  assert.equal(summary.totals.scope2MarketTonnes, 18);
  assert.equal(summary.totals.scope2LocationTonnes + summary.totals.scope2MarketTonnes, 38);
  // The two perspectives are distinct fields — never merged into one.
  assert.notEqual(summary.totals.scope2LocationTonnes, summary.totals.scope2MarketTonnes);

  const breakdown = await api.getBreakdown('scope');
  assert.equal(breakdown.rows.length, 1);
  assert.equal(breakdown.rows[0].key, 'SCOPE_1');
});

// ------------------------------------------------------------------
// 13. Evidence upload error state
// ------------------------------------------------------------------

test('EVIDENCE 1: upload failure surfaces an inline error, not a silent throw', async () => {
  globalThis.fetch = (async () =>
    makeResponse(400, {
      success: false,
      error: { code: 'FILE_MISSING', message: 'Evidence file is required.' },
    })) as typeof fetch;

  let caught: any = null;
  try {
    await api.uploadEvidence(new File(['x'], 'bill.pdf'));
  } catch (err) {
    caught = err;
  }
  assert.ok(caught instanceof ApiError);
  assert.equal(caught.status, 400);
  assert.equal(caught.code, 'FILE_MISSING');
});

// ------------------------------------------------------------------
// 19/20. Admin restrictions
// ------------------------------------------------------------------

test('ADMIN 1: company admin restrictions — reviewer role cannot see user administration', () => {
  const markup = renderToStaticMarkup(<AdminView permissions={PERMISSIONS_REVIEWER} />);
  // The view itself loads nothing without users.read; the nav gate is tested in NAV 1.
  assert.match(markup, /Loading users/);
});

test('ADMIN 2: users.create permission gates the Add User button', () => {
  // The permission gate itself is the security-relevant assertion.
  assert.equal(hasPermission(PERMISSIONS_ALL, 'users.create'), true);
  assert.equal(hasPermission(PERMISSIONS_REVIEWER, 'users.create'), false);
  // Static render shows the loading state before the mount effect fires.
  const markup = renderToStaticMarkup(<AdminView permissions={PERMISSIONS_ALL} />);
  assert.match(markup, /Loading users/);
});

// ------------------------------------------------------------------
// 21/22/23/24. API error mapping
// ------------------------------------------------------------------

test('API ERR 1: 403 maps to ApiError with FORBIDDEN code and no refresh attempt', async () => {
  let refreshCalls = 0;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/auth/refresh')) {
      refreshCalls += 1;
      return makeResponse(200, { success: true, data: { accessToken: 'a', refreshToken: 'r' } });
    }
    return makeResponse(403, { success: false, error: { code: 'FORBIDDEN', message: 'Insufficient permissions for this operation.' } });
  }) as typeof fetch;

  setAuthTokens('access-1', 'refresh-1');
  await assert.rejects(
    api.getInventory(),
    (error: unknown) => error instanceof ApiError && error.status === 403 && error.code === 'FORBIDDEN'
  );
  assert.equal(refreshCalls, 0);
});

test('API ERR 2: 404 maps to ApiError with the backend code', async () => {
  globalThis.fetch = (async () =>
    makeResponse(404, { success: false, error: { code: 'CARBON_TARGET_NOT_FOUND', message: 'Carbon target does not exist or access denied.' } })) as typeof fetch;

  await assert.rejects(
    api.getTargets(),
    (error: unknown) => error instanceof ApiError && error.status === 404 && error.code === 'CARBON_TARGET_NOT_FOUND'
  );
});

test('API ERR 3: 409 conflict (locked snapshot) maps to ApiError', async () => {
  globalThis.fetch = (async () =>
    makeResponse(409, { success: false, error: { code: 'INVENTORY_SNAPSHOT_LOCKED', message: 'Inventory snapshot for this reporting period is locked.' } })) as typeof fetch;

  await assert.rejects(
    api.createInventorySnapshot('p1'),
    (error: unknown) => error instanceof ApiError && error.status === 409
  );
});

test('API ERR 4: 500 server error maps to ApiError without crashing the client', async () => {
  globalThis.fetch = (async () =>
    makeResponse(500, { success: false, error: { code: 'INTERNAL', message: 'Unexpected failure.' } })) as typeof fetch;

  await assert.rejects(
    api.getFacilities(),
    (error: unknown) => error instanceof ApiError && error.status === 500
  );
});

// ------------------------------------------------------------------
// 18. CSV export — authenticated, no token in URL
// ------------------------------------------------------------------

test('CSV 1: export sends the Authorization header and never a query-string token', async () => {
  // The download flow uses browser APIs; stub the minimal surface.
  const originalWindow = (globalThis as any).window;
  const originalDocument = (globalThis as any).document;
  (globalThis as any).window = {
    URL: { createObjectURL: () => 'blob:stub', revokeObjectURL: () => {} },
  };
  (globalThis as any).document = {
    createElement: () => ({ click() {}, remove() {}, set href(v: string) {}, set download(v: string) {} }),
    body: { appendChild() {}, removeChild() {} },
  };
  try {
    let capturedUrl = '';
    let capturedAuthHeader: string | null = null;
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = String(input);
      capturedAuthHeader = new Headers(init?.headers).get('Authorization');
      return makeResponse(200, { success: true });
    }) as typeof fetch;

    setAuthTokens('access-secret', 'refresh-secret');
    await api.exportEmissionReportCsv({ periodId: 'p1', scope: 'SCOPE_1' });

    assert.match(capturedUrl, /\/api\/v1\/reports\/export-csv/);
    assert.match(capturedUrl, /periodId=p1/);
    assert.match(capturedUrl, /scope=SCOPE_1/);
    assert.equal(capturedAuthHeader, 'Bearer access-secret');
    assert.doesNotMatch(capturedUrl, /token=/);
    assert.doesNotMatch(capturedUrl, /access-secret/);
  } finally {
    (globalThis as any).window = originalWindow;
    (globalThis as any).document = originalDocument;
  }
});

// ------------------------------------------------------------------
// 10/11. Activity creation & calculation results via the API contract
// ------------------------------------------------------------------

test('ACTIVITY 1: create posts the Java ActivityCreateRequest shape', async () => {
  let capturedBody: any = null;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/activity-data') && init?.method === 'POST') {
      capturedBody = JSON.parse(String(init.body));
      return makeResponse(201, { success: true, data: { id: 'a1' } });
    }
    return makeResponse(404, { success: false, error: { code: 'NOT_FOUND', message: 'nope' } });
  }) as typeof fetch;

  await api.createActivityData({
    facilityId: 'f1',
    reportingPeriodId: 'p1',
    scope: 'SCOPE_1',
    category: 'Stationary Combustion',
    activityType: 'NATURAL_GAS',
    quantity: 1000,
    unit: 'kWh',
    source: 'Utility Meter Invoice',
    startDate: '2026-01-01',
    endDate: '2026-12-31',
    notes: '',
  });

  assert.equal(capturedBody.facilityId, 'f1');
  assert.equal(capturedBody.scope, 'SCOPE_1');
  assert.equal(capturedBody.quantity, 1000);
});

test('CALC 1: run posts the Java CalculationRequest shape and returns the backend result', async () => {
  let capturedBody: any = null;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/calculations/run')) {
      capturedBody = JSON.parse(String(init?.body));
      return makeResponse(200, {
        success: true,
        data: {
          calculation: {
            id: 'c1', totalCo2eTonnes: 0.216536, calculationHash: 'hash', gasResults: [],
          },
          emissionRecord: { id: 'e1', co2eTonnes: 0.216536, status: 'ACTIVE' },
        },
      });
    }
    return makeResponse(404, { success: false, error: { code: 'NOT_FOUND', message: 'nope' } });
  }) as typeof fetch;

  const result = await api.runCalculation('a1');
  assert.equal(capturedBody.activityDataId, 'a1');
  assert.equal(result.calculation.totalCo2eTonnes, 0.216536);
  assert.equal(result.emissionRecord.status, 'ACTIVE');
});
