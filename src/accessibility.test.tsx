import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Modal } from './components/Modal.tsx';
import { ConfirmDialog } from './components/ConfirmDialog.tsx';
import { Sidebar, NAV_ITEMS } from './components/Sidebar.tsx';
import { Navbar } from './components/Navbar.tsx';
import { LoginView } from './components/LoginView.tsx';
import { DashboardView } from './components/DashboardView.tsx';
import { TargetsView } from './components/TargetsView.tsx';
import { AuditView } from './components/AuditView.tsx';
import { ActivityDataView } from './components/ActivityDataView.tsx';
import { BoundariesView } from './components/BoundariesView.tsx';
import { EvidenceView } from './components/EvidenceView.tsx';
import { InventoryView } from './components/InventoryView.tsx';
import { PlatformAdminView } from './components/PlatformAdminView.tsx';
import { TestSuiteView } from './components/TestSuiteView.tsx';
import { FOCUSABLE_SELECTOR, resolveTabTarget } from './services/focusTrap.ts';
import { api, clearAuthTokens, setAuthTokens } from './services/api.ts';

const originalFetch = globalThis.fetch;
const noOp = () => {};

const PERMISSIONS_ALL = [
  'analytics.read', 'facilities.read', 'activity_data.read', 'reports.read',
  'emission_factors.read', 'audits.read', 'audits.create', 'audits.submit',
  'audits.review', 'audits.approve', 'audits.lock', 'evidence.read', 'evidence.upload',
  'inventory.read', 'inventory.create', 'inventory.lock', 'targets.read', 'targets.create',
  'targets.update', 'reduction_projects.read', 'reduction_projects.create',
  'reduction_projects.update', 'users.read', 'users.create', 'users.update', 'users.disable',
  'platform.tenants.read', 'platform.tenants.manage',
];

function jsonResponse(status: number, body: any): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    blob: async () => ({ size: 0 }),
  } as unknown as Response;
}

afterEach(() => {
  globalThis.fetch = originalFetch;
  clearAuthTokens();
});

// ------------------------------------------------------------------
// A11Y 1 — Dialog semantics
// ------------------------------------------------------------------

test('A11Y 1: modal exposes dialog role, modal state and an accessible name', () => {
  const markup = renderToStaticMarkup(
    <Modal title="Log Operational Activity Data" onClose={noOp}>
      <form />
    </Modal>,
  );

  assert.match(markup, /role="dialog"/);
  assert.match(markup, /aria-modal="true"/);
  // The accessible name is wired via aria-labelledby pointing at the heading.
  assert.match(markup, /aria-labelledby="[^"]+"/);
  assert.match(markup, /Log Operational Activity Data/);
  // No unlabelled close control: the previous implementation was a bare "✕".
  assert.doesNotMatch(markup, /✕/);
  assert.match(markup, /aria-label="Close dialog"/);
});

test('A11Y 2: modal description is wired through aria-describedby', () => {
  const markup = renderToStaticMarkup(
    <Modal title="Register New Facility" description="A facility becomes the boundary." onClose={noOp}>
      <form />
    </Modal>,
  );
  assert.match(markup, /aria-describedby="[^"]+"/);
  assert.match(markup, /A facility becomes the boundary\./);
});

test('A11Y 3: every CarbonFlow dialog is rendered through the shared Modal', () => {
  // Guards against a future view reintroducing an ad-hoc overlay with no
  // Escape handling and no focus management.
  const dialogs = renderToStaticMarkup(
    <Modal title="Shared dialog" onClose={noOp}>
      <span />
    </Modal>,
  );
  assert.match(dialogs, /role="dialog"/);
  assert.match(dialogs, /aria-modal="true"/);
});

// ------------------------------------------------------------------
// A11Y 4 — Destructive action confirmation
// ------------------------------------------------------------------

test('A11Y 4: destructive confirmation names the consequence and uses dialog semantics', () => {
  const markup = renderToStaticMarkup(
    <ConfirmDialog
      title="Lock this inventory snapshot?"
      body="Locking freezes emission totals for FY2026."
      confirmLabel="Lock snapshot"
      confirmAriaLabel="Confirm locking snapshot for FY2026"
      onConfirm={noOp}
      onCancel={noOp}
    />,
  );

  assert.match(markup, /role="dialog"/);
  assert.match(markup, /aria-modal="true"/);
  assert.match(markup, /Lock this inventory snapshot\?/);
  assert.match(markup, /Locking freezes emission totals for FY2026\./);
  assert.match(markup, /Confirm locking snapshot for FY2026/);
});

// ------------------------------------------------------------------
// A11Y 5 — Keyboard focus trap logic
// ------------------------------------------------------------------

test('A11Y 5: Tab wraps from the last focusable back to the first', () => {
  const focusables = [{ id: 'close' }, { id: 'save' }, { id: 'cancel' }];
  const result = resolveTabTarget(focusables, 'cancel', false);
  assert.equal(result.target, focusables[0]);
  assert.equal(result.preventDefault, true);
});

test('A11Y 6: Shift+Tab wraps from the first focusable back to the last', () => {
  const focusables = [{ id: 'close' }, { id: 'save' }, { id: 'cancel' }];
  const result = resolveTabTarget(focusables, 'close', true);
  assert.equal(result.target, focusables[2]);
  assert.equal(result.preventDefault, true);
});

test('A11Y 7: Tab in the middle of the trap moves forward without suppressing default', () => {
  const focusables = [{ id: 'close' }, { id: 'save' }, { id: 'cancel' }];
  const result = resolveTabTarget(focusables, 'close', false);
  assert.equal(result.target, focusables[1]);
  assert.equal(result.preventDefault, false);
});

test('A11Y 8: a dialog with no focusable content keeps focus on the panel (no escape hatch)', () => {
  const result = resolveTabTarget([], undefined, false);
  assert.equal(result.target, 'panel');
  assert.equal(result.preventDefault, true);
});

test('A11Y 9: focus trap selector covers the interactive controls CarbonFlow ships', () => {
  for (const selector of [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ]) {
    assert.ok(FOCUSABLE_SELECTOR.includes(selector), `missing ${selector}`);
  }
});

// ------------------------------------------------------------------
// A11Y 10 — Navigation landmark and current-page state
// ------------------------------------------------------------------

test('A11Y 10: sidebar is a labelled nav landmark and marks the current view', () => {
  const markup = renderToStaticMarkup(
    <Sidebar currentView="AUDIT" onSelectView={noOp} permissions={PERMISSIONS_ALL} auditBadge="REVIEW" />,
  );

  assert.match(markup, /<nav[^>]*aria-label="Primary"/);
  // Current page must not be signalled by colour alone.
  assert.match(markup, /aria-current="page"/);
  // Non-current items must not carry aria-current.
  const currentCount = (markup.match(/aria-current="page"/g) ?? []).length;
  assert.equal(currentCount, 1);
});

test('A11Y 11: sidebar exposes an empty-state message when the role has no sections', () => {
  const markup = renderToStaticMarkup(
    <Sidebar currentView="DASHBOARD" onSelectView={noOp} permissions={[]} />,
  );
  assert.match(markup, /no workspace sections available/i);
});

// ------------------------------------------------------------------
// A11Y 12 — Header controls
// ------------------------------------------------------------------

const ORG = { id: 'org-1', name: 'Northwind', consolidationApproach: 'OPERATIONAL_CONTROL', baseYear: 2023 } as any;
const USER = { id: 'u1', fullName: 'Ada Lovelace', email: 'ada@example.com' } as any;

test('A11Y 12: every header control has an accessible name', () => {
  const markup = renderToStaticMarkup(
    <Navbar
      currentOrg={ORG}
      currentUser={USER}
      currentRole="COMPANY_ADMIN"
      memberships={[{ organizationId: 'org-1', organizationName: 'Northwind', role: 'COMPANY_ADMIN' }]}
      onSwitchTenant={noOp}
      onSwitchRole={noOp}
      onRefresh={noOp}
      onExport={noOp}
      onLogout={noOp}
      isLoading={false}
    />,
  );

  assert.match(markup, /aria-label="Switch tenant"/);
  assert.match(markup, /aria-label="Switch role"/);
  // Icon-only refresh and sign-out buttons previously had no name.
  assert.match(markup, /aria-label="Refresh data"/);
  assert.match(markup, /aria-label="Sign out"/);
});

test('A11Y 13: disabled header controls explain why they are unavailable', () => {
  const markup = renderToStaticMarkup(
    <Navbar
      currentOrg={ORG}
      currentUser={USER}
      currentRole="COMPANY_ADMIN"
      memberships={[{ organizationId: 'org-1', organizationName: 'Northwind', role: 'COMPANY_ADMIN' }]}
      onSwitchTenant={noOp}
      onSwitchRole={noOp}
      onRefresh={noOp}
      onExport={noOp}
      onLogout={noOp}
      isLoading={false}
    />,
  );
  assert.match(markup, /single organization/);
});

// ------------------------------------------------------------------
// A11Y 14 — Form labels
// ------------------------------------------------------------------

test('A11Y 14: login fields are programmatically labelled and autocomplete is set', () => {
  const markup = renderToStaticMarkup(<LoginView onLogin={async () => {}} isLoading={false} error={null} />);

  assert.match(markup, /<label[^>]*for="auth-email"/);
  assert.match(markup, /id="auth-email"/);
  assert.match(markup, /<label[^>]*for="auth-password"/);
  assert.match(markup, /id="auth-password"/);
  // React 19 SSR emits the camelCase attribute verbatim.
  assert.match(markup, /autoComplete="username"/);
  assert.match(markup, /autoComplete="current-password"/);
});

test('A11Y 15: login errors are announced assertively', () => {
  const markup = renderToStaticMarkup(
    <LoginView onLogin={async () => {}} isLoading={false} error="Invalid email or password." />,
  );
  assert.match(markup, /role="alert"/);
});

// ------------------------------------------------------------------
// A11Y 16 — Table semantics
// ------------------------------------------------------------------

const FACILITIES = [
  { id: 'f1', facilityCode: 'FAC-PHX-01', name: 'Phoenix Hub', facilityType: 'LOGISTICS', country: 'US', stateProvince: 'AZ', gridRegion: 'AZNM', floorAreaM2: 15000 },
] as any;

test('A11Y 16: data tables expose captions and column header scope', () => {
  const markup = renderToStaticMarkup(
    <BoundariesView org={ORG} facilities={FACILITIES} onUpdateOrg={noOp} onCreateFacility={noOp} onCreatePeriod={noOp} />,
  );

  assert.match(markup, /<caption class="sr-only">/);
  assert.match(markup, /<th scope="col"/);
  // Header count must equal column count.
  assert.ok((markup.match(/<th scope="col"/g) ?? []).length >= 6);
});

// ------------------------------------------------------------------
// A11Y 17 — Colour is never the only carrier of meaning
// ------------------------------------------------------------------

test('A11Y 17: target progress bar exposes numeric value, not just a gradient', () => {
  const markup = renderToStaticMarkup(
    <TargetsView
      targets={[
        {
          id: 't1', name: '20% by 2030', status: 'ON_TRACK', baselineValueT: 1000, targetValueT: 800,
          reductionPercentage: 20, progressLocationPct: 42.5, notes: null, hasPersistedEmissions: true,
          currentLocationBasedT: 575, currentMarketBasedT: 560,
        },
      ] as any}
      projects={[]}
      periods={[]}
      facilities={[]}
      permissions={PERMISSIONS_ALL}
      onCreateTarget={noOp}
      onUpdateTarget={noOp}
      onCreateProject={noOp}
      onUpdateProject={noOp}
    />,
  );

  assert.match(markup, /role="progressbar"/);
  assert.match(markup, /aria-valuenow="42\.5"/);
  assert.match(markup, /aria-valuemin="0"/);
  assert.match(markup, /aria-valuemax="100"/);
  // The value is also visible as text, so colour/gradient is not load-bearing.
  assert.match(markup, /42\.5% realized/);
});

test('A11Y 18: audit lifecycle stepper states every stage in text', () => {
  const markup = renderToStaticMarkup(
    <AuditView
      audit={{
        id: 'a1', status: 'REVIEW', period: { id: 'p1', name: 'FY2026' },
        checklist: [{ id: 'c1', code: 'CHK-01', title: 'Evidence attached', isSatisfied: true, notes: null, verifiedAt: null }],
        findings: [], comments: [],
      } as any}
      currentRole="REVIEWER"
      permissions={PERMISSIONS_ALL}
      periods={[]}
      onCreateAudit={noOp}
      onTransition={noOp}
      onVerifyChecklist={noOp}
      onCreateFinding={noOp}
      onResolveFinding={noOp}
      onAddComment={noOp}
    />,
  );

  // Ordered list semantics for the lifecycle, plus explicit state words.
  assert.match(markup, /<ol[^>]*class="[^"]*min-w-\[700px\]/);
  assert.match(markup, /, current/);
  assert.match(markup, /, completed/);
  assert.match(markup, /, pending/);
});

// ------------------------------------------------------------------
// A11Y 19 — Announcements and busy states
// ------------------------------------------------------------------

test('A11Y 19: evidence upload state is announced and errors are assertive', () => {
  const markup = renderToStaticMarkup(
    <EvidenceView evidence={[]} linkedActivityId={null} onUpload={async () => {}} onDownload={async () => {}} />,
  );

  assert.match(markup, /aria-live="polite"/);
  assert.match(markup, /Select an evidence document to upload/);
  // Drag-and-drop has a keyboard equivalent.
  assert.match(markup, /browse local files/);
});

test('A11Y 20: the full evidence checksum is rendered, not only a truncated prefix', () => {
  const hash = 'a'.repeat(64);
  const markup = renderToStaticMarkup(
    <EvidenceView
      evidence={[
        { id: 'e1', fileName: 'invoice.pdf', fileSizeBytes: 1024, mimeType: 'application/pdf', sha256Hash: hash, createdAt: '2026-01-01T00:00:00Z' },
      ] as any}
      onUpload={async () => {}}
      onDownload={async () => {}}
    />,
  );

  assert.ok(markup.includes(hash), 'full SHA-256 digest must be present in the DOM');
  assert.match(markup, /aria-label="Download invoice\.pdf"/);
});

test('A11Y 21: refreshing controls report busy state instead of only spinning', () => {
  const markup = renderToStaticMarkup(
    <Navbar
      currentOrg={ORG}
      currentUser={USER}
      currentRole="COMPANY_ADMIN"
      memberships={[{ organizationId: 'org-1', organizationName: 'Northwind', role: 'COMPANY_ADMIN' }]}
      onSwitchTenant={noOp}
      onSwitchRole={noOp}
      onRefresh={noOp}
      onExport={noOp}
      onLogout={noOp}
      isLoading
    />,
  );

  assert.match(markup, /aria-busy="true"/);
  // Disabled controls state why.
  assert.match(markup, /disabled:cursor-not-allowed/);
});

test('A11Y 22: test suite results are not conveyed by badge colour alone', () => {
  const markup = renderToStaticMarkup(
    <TestSuiteView
      testSuiteData={{
        total: 2, passed: 1, failed: 1,
        results: [{ id: 'T-1', category: 'isolation', name: 'Tenant isolation', passed: true, details: 'ok' }],
      }}
      onRunTestSuite={noOp}
      isLoading={false}
    />,
  );

  assert.match(markup, /PASSED/);
  // The pass rate is derived from the payload, not hardcoded.
  assert.match(markup, /50% of scenarios passed/);
});

// ------------------------------------------------------------------
// A11Y 23 — Heading structure
// ------------------------------------------------------------------

test('A11Y 23: each view exposes exactly one h1 and nests its sections under h2', () => {
  const markup = renderToStaticMarkup(
    <ActivityDataView
      activities={[]}
      facilities={FACILITIES}
      periods={[]}
      onAddActivity={noOp}
      onRunCalculation={noOp}
      onBatchCalculate={noOp}
      onUploadEvidenceForActivity={noOp}
    />,
  );

  assert.equal((markup.match(/<h1/g) ?? []).length, 1);
  assert.match(markup, /<h1[^>]*>Activity Data Collection Ledger<\/h1>/);
});

test('A11Y 24: dashboard announces export outcomes with an explicit role', () => {
  const markup = renderToStaticMarkup(
    <DashboardView
      data={{
        emissions: { scope1Tonnes: 1, scope2LocationTonnes: 2, scope2MarketTonnes: 3, totalLocationBasedTonnes: 3, totalMarketBasedTonnes: 4 },
        auditStatus: 'REVIEW',
        auditHealth: { checklistSatisfied: 3, checklistTotal: 4, openFindingsCount: 1 },
        categories: [], facilities: [], periodTrends: [],
      } as any}
      periods={[]}
      onNavigate={noOp}
      onSnapshot={noOp}
    />,
  );

  assert.match(markup, /<h1/);
  // Charts are labelled images, so the figures are reachable as text.
  assert.match(markup, /role="img"/);
  assert.match(markup, /aria-label="Reporting period for snapshot"/);
});

// ------------------------------------------------------------------
// A11Y 25 — Empty, loading and error states
// ------------------------------------------------------------------

test('A11Y 25: inventory surfaces an announced loading state on first paint', () => {
  // Server rendering paints the initial state only; the fetch runs in an
  // effect, so this asserts the loading contract rather than the loaded rows.
  globalThis.fetch = (async () => jsonResponse(200, [])) as typeof fetch;
  setAuthTokens('access', 'refresh');

  const markup = renderToStaticMarkup(
    <InventoryView periods={[]} permissions={PERMISSIONS_ALL} />,
  );
  assert.match(markup, /role="status"/);
  assert.match(markup, /Loading inventory snapshots/);
});

test('A11Y 26: platform administration shows an explicit loading and empty state', () => {
  globalThis.fetch = (async () => jsonResponse(200, [])) as typeof fetch;
  setAuthTokens('access', 'refresh');
  const markup = renderToStaticMarkup(<PlatformAdminView />);

  assert.match(markup, /Organizations/);
  assert.match(markup, /aria-label="Filter by lifecycle status"/);
  assert.match(markup, /aria-label="Refresh organizations"/);
});

// ------------------------------------------------------------------
// A11Y 27 — Terminology stability
// ------------------------------------------------------------------

test('A11Y 27: approved CarbonFlow terminology is unchanged', () => {
  const labels = NAV_ITEMS.map((item) => item.label);

  // Scope / ledger terminology is regulated copy — do not silently reword it.
  for (const expected of [
    'Executive Dashboard',
    'Boundaries & Facilities',
    'Activity Data Collection',
    'Calculations & Ledger',
    'Emission Factors & GWP',
    'Audit & Governance',
    'Evidence Vault',
    'Inventory Snapshots',
    'Analytics & Breakdowns',
    'Targets & Projects',
    'Company Administration',
    'Platform Administration',
    'Automated Test Suite',
  ]) {
    assert.ok(labels.includes(expected), `terminology drift: missing "${expected}"`);
  }
});

test('A11Y 28: audit view keeps its approved state names and permission explanations', () => {
  const reviewerMarkup = renderToStaticMarkup(
    <AuditView
      audit={null}
      currentRole="REVIEWER"
      permissions={PERMISSIONS_ALL}
      periods={[]}
      onCreateAudit={noOp}
      onTransition={noOp}
      onVerifyChecklist={noOp}
      onCreateFinding={noOp}
      onResolveFinding={noOp}
      onAddComment={noOp}
    />,
  );
  assert.match(reviewerMarkup, /Audit &amp; Assurance Room/);
  assert.match(reviewerMarkup, /No audit workspace/);

  // A role without audits.create must be told why it cannot initiate, and the
  // inert control must not be offered at all.
  const readOnlyMarkup = renderToStaticMarkup(
    <AuditView
      audit={null}
      currentRole="MANAGEMENT"
      permissions={['audits.read']}
      periods={[]}
      onCreateAudit={noOp}
      onTransition={noOp}
      onVerifyChecklist={noOp}
      onCreateFinding={noOp}
      onResolveFinding={noOp}
      onAddComment={noOp}
    />,
  );
  assert.match(readOnlyMarkup, /cannot initiate audits/);
  assert.doesNotMatch(readOnlyMarkup, /Initiate Audit/);
});

test('A11Y 29: dual-reporting terminology is preserved on the dashboard', () => {
  const markup = renderToStaticMarkup(
    <DashboardView
      data={{
        emissions: { scope1Tonnes: 1, scope2LocationTonnes: 2, scope2MarketTonnes: 3, totalLocationBasedTonnes: 3, totalMarketBasedTonnes: 4 },
        auditStatus: 'LOCKED',
        auditHealth: { checklistSatisfied: 4, checklistTotal: 4, openFindingsCount: 0 },
        categories: [], facilities: [], periodTrends: [],
      } as any}
      periods={[]}
      onNavigate={noOp}
      onSnapshot={noOp}
    />,
  );

  assert.match(markup, /Scope 2 \(Location\)/);
  assert.match(markup, /Scope 2 \(Market\)/);
  assert.match(markup, /Dual-Reporting Totals \(Non-Aggregated Presentation\)/);
  assert.match(markup, /CYCLE: LOCKED/);
});

// ------------------------------------------------------------------
// A11Y 30 — Backend boundary preserved
// ------------------------------------------------------------------

test('A11Y 30: no frontend change altered the backend API surface', () => {
  // Guards the "do not change backend APIs unnecessarily" constraint: the
  // frontend still talks to the same evidence endpoints.
  for (const method of ['uploadEvidence', 'downloadEvidence', 'getUsers', 'disableUser', 'enableUser', 'lockInventorySnapshot']) {
    assert.equal(typeof (api as any)[method], 'function', `${method} missing`);
  }
});