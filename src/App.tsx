/**
 * CarbonFlow — Enterprise GHG Accounting & Audit SaaS Platform
 */
import React, { useState, useEffect, useCallback, useRef } from 'react';
import { CheckCircle2, AlertTriangle } from 'lucide-react';
import { Navbar } from './components/Navbar.tsx';
import { Sidebar, NAV_ITEMS } from './components/Sidebar.tsx';
import { DashboardView } from './components/DashboardView.tsx';
import { BoundariesView } from './components/BoundariesView.tsx';
import { ActivityDataView } from './components/ActivityDataView.tsx';
import { EmissionsView } from './components/EmissionsView.tsx';
import { FactorsView } from './components/FactorsView.tsx';
import { AuditView } from './components/AuditView.tsx';
import { EvidenceView } from './components/EvidenceView.tsx';
import { TargetsView } from './components/TargetsView.tsx';
import { TestSuiteView } from './components/TestSuiteView.tsx';
import { InventoryView } from './components/InventoryView.tsx';
import { AnalyticsView } from './components/AnalyticsView.tsx';
import { AdminView } from './components/AdminView.tsx';
import { PlatformAdminView } from './components/PlatformAdminView.tsx';
import { AuthBoundary } from './components/AuthBoundary.tsx';

import {
  api,
  ApiError,
  clearAuthTokens,
  getAccessToken,
  getRefreshToken,
  setAuthTokens,
  setUnauthorizedHandler,
} from './services/api.ts';
import {
  NavView,
  RoleName,
  Organization,
  User,
  Facility,
  ReportingPeriod,
  ActivityDataItem,
  AuditDetail,
  EvidenceRecord,
  CarbonTarget,
  CarbonTargetInput,
  ReductionProject,
  ReductionProjectInput,
  DashboardSummary,
  AuditStatus,
  AuthMembership,
  AuthSession,
  AuthState,
} from './types.ts';

// Page titles are derived from the canonical sidebar labels so navigation
// terminology stays in one place (no parallel wording to drift).
const VIEW_TITLES: Record<NavView, string> = NAV_ITEMS.reduce(
  (acc, item) => ({ ...acc, [item.view]: item.label }),
  {} as Record<NavView, string>,
);

export default function App() {
  const [authState, setAuthState] = useState<AuthState>('AUTH_LOADING');
  const [authError, setAuthError] = useState<string | null>(null);
  const [currentView, setCurrentView] = useState<NavView>('DASHBOARD');
  const [currentOrg, setCurrentOrg] = useState<Organization | null>(null);
  const [currentUser, setCurrentUser] = useState<User | null>(null);
  const [currentRole, setCurrentRole] = useState<RoleName | null>(null);
  const [memberships, setMemberships] = useState<AuthMembership[]>([]);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [notification, setNotification] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const notificationTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Core domain state
  const [facilities, setFacilities] = useState<Facility[]>([]);
  const [reportingPeriods, setReportingPeriods] = useState<ReportingPeriod[]>([]);
  const [activities, setActivities] = useState<ActivityDataItem[]>([]);
  const [dashboardData, setDashboardData] = useState<DashboardSummary | null>(null);
  const [gwpSets, setGwpSets] = useState<any[]>([]);
  const [emissionFactors, setEmissionFactors] = useState<any[]>([]);
  const [currentAudit, setCurrentAudit] = useState<AuditDetail | null>(null);
  const [evidenceRecords, setEvidenceRecords] = useState<EvidenceRecord[]>([]);
  const [evidenceActivityId, setEvidenceActivityId] = useState<string | null>(null);
  const [targets, setTargets] = useState<CarbonTarget[]>([]);
  const [reductionProjects, setReductionProjects] = useState<ReductionProject[]>([]);
  const [testSuiteData, setTestSuiteData] = useState<any>(null);
  const sessionGeneration = useRef(0);

  // Success messages are announced politely; errors are announced assertively
  // by the live region in the render tree below (role/aria-live live there).
  const showToast = (message: string, type: 'success' | 'error' = 'success') => {
    setNotification({ message, type });
    if (notificationTimer.current) clearTimeout(notificationTimer.current);
    notificationTimer.current = setTimeout(() => setNotification(null), 4000);
  };

  // Never leave a timer running after the shell unmounts.
  useEffect(() => {
    return () => {
      if (notificationTimer.current) clearTimeout(notificationTimer.current);
    };
  }, []);

  const getMembershipOptions = (authData: AuthSession): AuthMembership[] => {
    return Array.isArray(authData.memberships) ? authData.memberships : [];
  };

  const clearAuthenticatedState = useCallback(() => {
    setCurrentView('DASHBOARD');
    setCurrentOrg(null);
    setCurrentUser(null);
    setCurrentRole(null);
    setMemberships([]);
    setPermissions([]);
    setFacilities([]);
    setReportingPeriods([]);
    setActivities([]);
    setDashboardData(null);
    setGwpSets([]);
    setEmissionFactors([]);
    setCurrentAudit(null);
    setEvidenceRecords([]);
    setEvidenceActivityId(null);
    setTargets([]);
    setReductionProjects([]);
    setTestSuiteData(null);
    setNotification(null);
    setIsLoading(false);
  }, []);

  const clearFrontendSession = useCallback(() => {
    sessionGeneration.current += 1;
    clearAuthTokens();
    clearAuthenticatedState();
    setAuthError(null);
    setAuthState('UNAUTHENTICATED');
  }, [clearAuthenticatedState]);

  const applyAuthSession = useCallback((session: AuthSession) => {
    if (!session?.user || !session?.organization || !session?.role) {
      throw new Error('Authenticated session response was incomplete.');
    }
    setCurrentUser(session.user);
    setCurrentOrg(session.organization);
    setCurrentRole(session.role);
    setMemberships(getMembershipOptions(session));
    setPermissions(Array.isArray(session.permissions) ? session.permissions : []);
    setAuthError(null);
  }, []);

  const isUnauthorized = (error: unknown): boolean => {
    return error instanceof ApiError && error.status === 401;
  };

  // Load all tenant-scoped data
  const loadTenantData = useCallback(async () => {
    const requestGeneration = sessionGeneration.current;
    if (!getAccessToken()) return;

    try {
      setIsLoading(true);
      const [
        orgRes,
        facRes,
        periodsRes,
        activitiesRes,
        analyticsRes,
        gwpRes,
        efRes,
        auditsRes,
        evidenceRes,
        targetsRes,
        projectsRes,
      ] = await Promise.all([
        api.getCurrentOrg().catch(() => null),
        api.getFacilities().catch(() => []),
        api.getReportingPeriods().catch(() => []),
        api.getActivityData(),
        api.getDashboardAnalytics().catch(() => null),
        api.getGwpSets().catch(() => []),
        api.getEmissionFactors().catch(() => []),
        api.getAudits().catch(() => []),
        api.getEvidence(),
        api.getTargets().catch(() => []),
        api.getReductionProjects().catch(() => []),
      ]);

      // A logout, invalid session, or tenant switch may have happened while
      // these requests were in flight. Do not apply stale tenant data.
      if (sessionGeneration.current !== requestGeneration) return;

      if (orgRes) setCurrentOrg(orgRes);
      setFacilities(facRes);
      setReportingPeriods(periodsRes);
      setActivities(activitiesRes);
      setDashboardData(analyticsRes);
      setGwpSets(gwpRes);
      setEmissionFactors(efRes);
      setEvidenceRecords(evidenceRes);
      setTargets(targetsRes);
      setReductionProjects(projectsRes);

      // Load active audit detail. Re-check the session after this second
      // request so a logout or tenant switch cannot apply stale audit data.
      if (auditsRes && auditsRes.length > 0) {
        const detail = await api.getAuditDetail(auditsRes[0].id).catch(() => null);
        if (sessionGeneration.current !== requestGeneration) return;
        setCurrentAudit(detail);
      } else {
        if (sessionGeneration.current !== requestGeneration) return;
        setCurrentAudit(null);
      }
    } catch (err: any) {
      if (err instanceof ApiError && err.status === 401) {
        clearFrontendSession();
        return;
      }
      console.error('Failed to load tenant data:', err);
      setNotification({ message: err instanceof Error ? err.message : 'Failed to load tenant data.', type: 'error' });
    } finally {
      if (sessionGeneration.current === requestGeneration) {
        setIsLoading(false);
      }
    }
  }, [clearFrontendSession]);

  // Announce client-side navigation. CarbonFlow switches views by state rather
  // than by URL, so screen readers get no page-load cue without this.
  useEffect(() => {
    if (authState !== 'AUTHENTICATED') return;
    document.title = `${VIEW_TITLES[currentView] ?? 'Workspace'} — CarbonFlow`;
  }, [currentView, authState]);

  // Register one session-invalid boundary for all authenticated API requests.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      clearFrontendSession();
    });
    return () => setUnauthorizedHandler(null);
  }, [clearFrontendSession]);

  // Validate an existing browser token against the backend before restoring state.
  useEffect(() => {
    let active = true;

    const restoreSession = async () => {
      if (!getAccessToken() && !getRefreshToken()) {
        if (active) setAuthState('UNAUTHENTICATED');
        return;
      }

      try {
        const session = await api.getMe();
        if (!active) return;
        applyAuthSession(session);
        setAuthState('AUTHENTICATED');
        await loadTenantData();
      } catch (error) {
        if (!active) return;
        clearFrontendSession();
        if (!isUnauthorized(error)) {
          setAuthError('Unable to restore the previous session. Please sign in again.');
        }
      }
    };

    void restoreSession();
    return () => {
      active = false;
    };
  }, [applyAuthSession, clearFrontendSession, loadTenantData]);

  const handleLogin = async (email: string, password: string) => {
    setAuthError(null);
    setIsLoading(true);

    try {
      const loginData = await api.login(email, password);
      if (!loginData?.accessToken || !loginData?.refreshToken) {
        throw new Error('Login response did not contain a complete token pair.');
      }

      sessionGeneration.current += 1;
      setAuthTokens(loginData.accessToken, loginData.refreshToken);
      const session = await api.getMe();
      applyAuthSession(session);
      setAuthState('AUTHENTICATED');
      await loadTenantData();
    } catch (error: any) {
      clearFrontendSession();
      setAuthError(error?.message || 'Sign in failed. Please try again.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleLogout = async () => {
    const refreshToken = getRefreshToken();
    try {
      if (refreshToken) {
        await api.logout(refreshToken);
      }
    } catch {
      // Local state is cleared even when the server is unavailable or the
      // refresh session has already been revoked.
    } finally {
      clearFrontendSession();
    }
  };

  // Switch only to an organization/role combination assigned to the current user.
  const handleSwitchTenant = async (orgId: string) => {
    const targetMembership = memberships.find((membership) => membership.organizationId === orgId);
    if (!targetMembership) {
      showToast('You are not assigned to that organization.', 'error');
      return;
    }

    try {
      setIsLoading(true);
      const authData = await api.switchTenantOrRole(targetMembership.organizationId, targetMembership.role);
      sessionGeneration.current += 1;
      setAuthTokens(authData.accessToken, authData.refreshToken);
      setCurrentOrg(authData.organization);
      setCurrentUser(authData.user);
      setCurrentRole(authData.role);
      setMemberships(getMembershipOptions(authData));
      await loadTenantData();
      showToast(`Switched to tenant: ${authData.organization.name}`);
    } catch (err: any) {
      if (err instanceof ApiError && err.status === 401) {
        clearFrontendSession();
        return;
      }
      showToast(err.message || 'Tenant switch failed', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  // Switch only to a role assigned by the current user's active membership.
  const handleSwitchRole = async (role: RoleName) => {
    const targetMembership = memberships.find(
      (membership) => membership.organizationId === currentOrg?.id && membership.role === role
    );
    if (!targetMembership) {
      showToast('That role is not assigned to your current organization.', 'error');
      return;
    }

    try {
      setIsLoading(true);
      const authData = await api.switchTenantOrRole(targetMembership.organizationId, targetMembership.role);
      sessionGeneration.current += 1;
      setAuthTokens(authData.accessToken, authData.refreshToken);
      setCurrentOrg(authData.organization);
      setCurrentUser(authData.user);
      setCurrentRole(authData.role);
      setMemberships(getMembershipOptions(authData));
      await loadTenantData();
      showToast(`Role updated to ${role}`);
    } catch (err: any) {
      if (err instanceof ApiError && err.status === 401) {
        clearFrontendSession();
        return;
      }
      showToast(err.message || 'Role switch failed', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  // Handlers for child views
  const handleUpdateOrg = async (data: Partial<Organization>) => {
    try {
      const updated = await api.updateCurrentOrg(data);
      setCurrentOrg(updated);
      showToast('Organizational boundaries saved.');
    } catch (err: any) {
      showToast(err.message || 'Update failed', 'error');
    }
  };

  const handleCreateFacility = async (data: any) => {
    try {
      await api.createFacility(data);
      await loadTenantData();
      showToast('Facility registered successfully.');
    } catch (err: any) {
      showToast(err.message || 'Facility creation failed', 'error');
    }
  };

  const handleCreatePeriod = async (data: { name: string; startDate: string; endDate: string }) => {
    try {
      await api.createReportingPeriod(data);
      await loadTenantData();
      showToast('Reporting period created.');
    } catch (err: any) {
      showToast(err.message || 'Reporting period creation failed', 'error');
    }
  };

  const handleAddActivity = async (data: any) => {
    try {
      await api.createActivityData(data);
      await loadTenantData();
      showToast('Activity record logged.');
    } catch (err: any) {
      showToast(err.message || 'Activity logging failed', 'error');
    }
  };

  const handleRunCalculation = async (activityId: string) => {
    try {
      await api.runCalculation(activityId);
      await loadTenantData();
      showToast('Deterministic calculation completed.');
    } catch (err: any) {
      showToast(err.message || 'Calculation failed', 'error');
    }
  };

  const handleBatchCalculate = async () => {
    try {
      const res = await api.batchRunCalculations();
      await loadTenantData();
      showToast(res.message || 'Batch calculation finished.');
    } catch (err: any) {
      showToast(err.message || 'Batch calculation failed', 'error');
    }
  };

  const handleCreateSnapshot = async (periodId: string) => {
    try {
      await api.createInventorySnapshot(periodId);
      showToast('Immutable inventory snapshot generated.');
    } catch (err: any) {
      showToast(err.message || 'Snapshot creation failed', 'error');
    }
  };

  const handleCreateAudit = async (reportingPeriodId: string) => {
    try {
      await api.createAudit(reportingPeriodId);
      await loadTenantData();
      showToast('Audit initiated.');
    } catch (err: any) {
      showToast(err.message || 'Audit creation failed', 'error');
    }
  };

  const handleTransitionAudit = async (targetState: AuditStatus, reason?: string) => {
    if (!currentAudit) return;
    try {
      await api.transitionAudit(currentAudit.id, targetState, reason);
      const detail = await api.getAuditDetail(currentAudit.id);
      setCurrentAudit(detail);
      await loadTenantData();
      showToast(`Audit transitioned to ${targetState}`);
    } catch (err: any) {
      showToast(err.message || 'Audit transition failed', 'error');
    }
  };

  const handleVerifyChecklist = async (itemId: string, isSatisfied: boolean, notes?: string) => {
    if (!currentAudit) return;
    try {
      await api.verifyChecklistItem(currentAudit.id, itemId, isSatisfied, notes);
      const detail = await api.getAuditDetail(currentAudit.id);
      setCurrentAudit(detail);
      showToast('Checklist item updated.');
    } catch (err: any) {
      showToast(err.message || 'Verification failed', 'error');
    }
  };

  const handleCreateFinding = async (data: any) => {
    if (!currentAudit) return;
    try {
      await api.createFinding(currentAudit.id, data);
      const detail = await api.getAuditDetail(currentAudit.id);
      setCurrentAudit(detail);
      showToast('Review finding logged.');
    } catch (err: any) {
      showToast(err.message || 'Failed to log finding', 'error');
    }
  };

  const handleResolveFinding = async (findingId: string) => {
    if (!currentAudit) return;
    try {
      await api.resolveFinding(currentAudit.id, findingId);
      const detail = await api.getAuditDetail(currentAudit.id);
      setCurrentAudit(detail);
      showToast('Finding marked as resolved.');
    } catch (err: any) {
      showToast(err.message || 'Failed to resolve finding', 'error');
    }
  };

  const handleAddAuditComment = async (text: string) => {
    if (!currentAudit) return;
    try {
      await api.addAuditComment(currentAudit.id, text);
      const detail = await api.getAuditDetail(currentAudit.id);
      setCurrentAudit(detail);
      showToast('Comment added to audit record.');
    } catch (err: any) {
      showToast(err.message || 'Failed to add comment', 'error');
    }
  };

  const handleUploadEvidence = async (file: File) => {
    try {
      await api.uploadEvidence(
        file,
        evidenceActivityId ? 'ACTIVITY_DATA' : undefined,
        evidenceActivityId || undefined,
      );
      await loadTenantData();
      setEvidenceActivityId(null);
      showToast(`Uploaded ${file.name} with SHA-256 verification.`);
    } catch (err: any) {
      showToast(err.message || 'Upload failed', 'error');
      throw err;
    }
  };

  const handleDownloadEvidence = async (evidenceId: string, fileName: string) => {
    await api.downloadEvidence(evidenceId, fileName);
  };

  const handleCreateTarget = async (data: CarbonTargetInput) => {
    try {
      await api.createTarget(data);
      await loadTenantData();
      showToast('Carbon target created.');
    } catch (err: any) {
      showToast(err.message || 'Target creation failed', 'error');
    }
  };

  const handleUpdateTarget = async (targetId: string, data: Partial<CarbonTargetInput>) => {
    try {
      await api.updateTarget(targetId, data);
      await loadTenantData();
      showToast('Carbon target updated.');
    } catch (err: any) {
      showToast(err.message || 'Target update failed', 'error');
    }
  };

  const handleCreateProject = async (data: ReductionProjectInput) => {
    try {
      await api.createReductionProject(data);
      await loadTenantData();
      showToast('Reduction project created.');
    } catch (err: any) {
      showToast(err.message || 'Project creation failed', 'error');
    }
  };

  const handleUpdateProject = async (projectId: string, data: Partial<ReductionProjectInput>) => {
    try {
      await api.updateReductionProject(projectId, data);
      await loadTenantData();
      showToast('Reduction project updated.');
    } catch (err: any) {
      showToast(err.message || 'Project update failed', 'error');
    }
  };

  const handleExportCsv = async () => {
    try {
      await api.exportEmissionReportCsv();
      showToast('Emission inventory report exported successfully.');
    } catch (err: any) {
      showToast(err?.message || 'Failed to export emission report.', 'error');
    }
  };

  const handleRunTestSuite = async () => {
    try {
      setIsLoading(true);
      const res = await api.getTestSuiteResults();
      setTestSuiteData(res);
      showToast(`Automated test suite complete: ${res.passed} passed, ${res.failed} failed.`);
    } catch (err: any) {
      showToast(err.message || 'Test suite failed', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <AuthBoundary
      state={authState}
      isLoading={isLoading}
      error={authError}
      onLogin={handleLogin}
    >
      <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans selection:bg-emerald-500 selection:text-slate-950">
      {/* Skip link — first tabbable element on every authenticated page. */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-[60] focus:px-3 focus:py-2 focus:rounded-lg focus:bg-emerald-600 focus:text-white focus:text-xs focus:font-semibold"
      >
        Skip to main content
      </a>

      {/* Top Bar */}
      <Navbar
        currentOrg={currentOrg}
        currentUser={currentUser}
        currentRole={currentRole}
        memberships={memberships}
        onSwitchTenant={handleSwitchTenant}
        onSwitchRole={handleSwitchRole}
        onRefresh={loadTenantData}
        onExport={handleExportCsv}
        onLogout={handleLogout}
        isLoading={isLoading}
      />

      {/* Main Layout */}
      <div className="flex-1 flex flex-col md:flex-row">
        {/* Navigation Sidebar */}
        <Sidebar
          currentView={currentView}
          onSelectView={setCurrentView}
          auditBadge={currentAudit?.status}
          testPassedCount={testSuiteData?.passed}
          permissions={permissions}
        />

        {/* Content Area. Padding and width step down at 390px so no view is
            forced to scroll horizontally; main is the skip-link target. */}
        <main
          id="main-content"
          tabIndex={-1}
          aria-label="Workspace content"
          className="flex-1 w-full min-w-0 p-4 sm:p-6 lg:p-8 max-w-7xl mx-auto"
        >
          {currentView === 'DASHBOARD' && (
            <DashboardView
              data={dashboardData}
              periods={reportingPeriods}
              onNavigate={setCurrentView}
              onSnapshot={handleCreateSnapshot}
            />
          )}

          {currentView === 'BOUNDARIES' && (
            <BoundariesView
              org={currentOrg}
              facilities={facilities}
              onUpdateOrg={handleUpdateOrg}
              onCreateFacility={handleCreateFacility}
              onCreatePeriod={handleCreatePeriod}
            />
          )}

          {currentView === 'ACTIVITY_DATA' && (
            <ActivityDataView
              activities={activities}
              facilities={facilities}
              periods={reportingPeriods}
              onAddActivity={handleAddActivity}
              onRunCalculation={handleRunCalculation}
              onBatchCalculate={handleBatchCalculate}
              onUploadEvidenceForActivity={(activityId) => {
                setEvidenceActivityId(activityId);
                setCurrentView('EVIDENCE');
              }}
            />
          )}

          {currentView === 'EMISSIONS' && (
            <EmissionsView permissions={permissions} periods={reportingPeriods} />
          )}

          {currentView === 'FACTORS' && (
            <FactorsView gwpSets={gwpSets} emissionFactors={emissionFactors} />
          )}

          {currentView === 'AUDIT' && (
            <AuditView
              audit={currentAudit}
              currentRole={currentRole}
              permissions={permissions}
              periods={reportingPeriods}
              onCreateAudit={handleCreateAudit}
              onTransition={handleTransitionAudit}
              onVerifyChecklist={handleVerifyChecklist}
              onCreateFinding={handleCreateFinding}
              onResolveFinding={handleResolveFinding}
              onAddComment={handleAddAuditComment}
            />
          )}

          {currentView === 'EVIDENCE' && (
            <EvidenceView
              evidence={evidenceRecords}
              linkedActivityId={evidenceActivityId}
              onUpload={handleUploadEvidence}
              onDownload={handleDownloadEvidence}
            />
          )}

          {currentView === 'TARGETS' && (
            <TargetsView
              targets={targets}
              projects={reductionProjects}
              periods={reportingPeriods}
              facilities={facilities}
              permissions={permissions}
              onCreateTarget={handleCreateTarget}
              onUpdateTarget={handleUpdateTarget}
              onCreateProject={handleCreateProject}
              onUpdateProject={handleUpdateProject}
            />
          )}

          {currentView === 'TEST_SUITE' && (
            <TestSuiteView
              testSuiteData={testSuiteData}
              onRunTestSuite={handleRunTestSuite}
              isLoading={isLoading}
            />
          )}

          {currentView === 'INVENTORY' && (
            <InventoryView periods={reportingPeriods} permissions={permissions} />
          )}

          {currentView === 'ANALYTICS' && (
            <AnalyticsView periods={reportingPeriods} />
          )}

          {currentView === 'ADMIN' && (
            <AdminView permissions={permissions} />
          )}

          {currentView === 'PLATFORM_ADMIN' && <PlatformAdminView />}
        </main>
      </div>

      {/* Status message. The container is always mounted so assistive technology
          registers the live region before the first message arrives; errors use
          role="alert" (assertive) and successes role="status" (polite). The
          icon is decorative — meaning is carried by the text, never colour. */}
      <div
        aria-live={notification?.type === 'error' ? 'assertive' : 'polite'}
        aria-atomic="true"
        className="fixed bottom-4 right-4 sm:bottom-6 sm:right-6 z-50 max-w-[calc(100vw-2rem)]"
      >
        {notification && (
          <div
            role={notification.type === 'error' ? 'alert' : 'status'}
            className={`px-4 py-2.5 rounded-lg shadow-xl text-xs font-semibold flex items-start gap-2 border ${
              notification.type === 'success'
                ? 'bg-emerald-950 text-emerald-200 border-emerald-800'
                : 'bg-rose-950 text-rose-200 border-rose-800'
            }`}
          >
            {notification.type === 'success' ? (
              <CheckCircle2 className="w-4 h-4 shrink-0 mt-px" aria-hidden="true" />
            ) : (
              <AlertTriangle className="w-4 h-4 shrink-0 mt-px" aria-hidden="true" />
            )}
            <span>{notification.message}</span>
          </div>
        )}
      </div>
      </div>
    </AuthBoundary>
  );
}
