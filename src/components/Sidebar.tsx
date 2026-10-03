/**
 * CarbonFlow — Main Navigation Sidebar
 * Phase 8: items are gated on the backend session's permission codes
 * (frozen 44-code matrix). Hiding is UX only — the backend remains the
 * security boundary.
 *
 * Phase 10.11: rendered as a labelled `nav` landmark with `aria-current="page"`
 * on the active item (previously the current view was signalled by colour
 * alone). Below the `md` breakpoint the rail becomes a horizontally scrollable
 * strip so all destinations stay reachable at 390x844 and 768x1024.
 */
import React from 'react';
import {
  LayoutDashboard,
  Building,
  Database,
  Calculator,
  Layers,
  FileCheck2,
  FolderLock,
  Target,
  FlaskConical,
  Boxes,
  ChartLine,
  Users,
  ShieldCheck,
} from 'lucide-react';
import { NavView } from '../types.ts';
import { hasPermission } from '../services/permissions.ts';

interface SidebarProps {
  currentView: NavView;
  onSelectView: (view: NavView) => void;
  auditBadge?: string;
  testPassedCount?: number;
  permissions: string[];
}

interface NavItem {
  view: NavView;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  /** Frozen-matrix permission code required to see this item. */
  permission: string;
  badge?: string;
}

export const NAV_ITEMS: NavItem[] = [
  { view: 'DASHBOARD', label: 'Executive Dashboard', icon: LayoutDashboard, permission: 'analytics.read' },
  { view: 'BOUNDARIES', label: 'Boundaries & Facilities', icon: Building, permission: 'facilities.read' },
  { view: 'ACTIVITY_DATA', label: 'Activity Data Collection', icon: Database, permission: 'activity_data.read' },
  { view: 'EMISSIONS', label: 'Calculations & Ledger', icon: Calculator, permission: 'reports.read' },
  { view: 'FACTORS', label: 'Emission Factors & GWP', icon: Layers, permission: 'emission_factors.read' },
  { view: 'AUDIT', label: 'Audit & Governance', icon: FileCheck2, permission: 'audits.read', badge: undefined },
  { view: 'EVIDENCE', label: 'Evidence Vault', icon: FolderLock, permission: 'evidence.read' },
  { view: 'INVENTORY', label: 'Inventory Snapshots', icon: Boxes, permission: 'inventory.read' },
  { view: 'ANALYTICS', label: 'Analytics & Breakdowns', icon: ChartLine, permission: 'analytics.read' },
  { view: 'TARGETS', label: 'Targets & Projects', icon: Target, permission: 'targets.read' },
  { view: 'ADMIN', label: 'Company Administration', icon: Users, permission: 'users.read' },
  { view: 'PLATFORM_ADMIN', label: 'Platform Administration', icon: ShieldCheck, permission: 'platform.tenants.read' },
  {
    view: 'TEST_SUITE',
    label: 'Automated Test Suite',
    icon: FlaskConical,
    permission: 'platform.tenants.manage',
    badge: undefined,
  },
];

export const Sidebar: React.FC<SidebarProps> = ({
  currentView,
  onSelectView,
  auditBadge,
  testPassedCount,
  permissions,
}) => {
  // Each item carries its own badge resolver (audit status / test count).
  const badgeFor = (view: NavView): string | undefined => {
    if (view === 'AUDIT') return auditBadge;
    if (view === 'TEST_SUITE') {
      return testPassedCount !== undefined ? `${testPassedCount} PASS` : undefined;
    }
    return undefined;
  };

  const visibleItems = NAV_ITEMS.filter((item) => hasPermission(permissions, item.permission));

  if (visibleItems.length === 0) {
    return (
      <nav
        aria-label="Primary"
        className="w-full md:w-64 bg-slate-900 border-b md:border-b-0 md:border-r border-slate-800 text-slate-300 p-4 shrink-0"
      >
        <p className="text-[11px] text-slate-500">
          Your role has no workspace sections available. Contact a company administrator.
        </p>
      </nav>
    );
  }

  return (
    <nav
      aria-label="Primary"
      className="w-full md:w-64 bg-slate-900 border-b md:border-b-0 md:border-r border-slate-800 text-slate-300 flex flex-col justify-between p-3 md:p-4 shrink-0 md:min-h-[calc(100vh-57px)]"
    >
      <div className="space-y-1">
        <div
          id="nav-section-heading"
          className="text-[11px] font-bold uppercase tracking-wider text-slate-500 px-3 py-2 hidden md:block"
        >
          Enterprise Accounting
        </div>

        {/* Narrow viewports: single scrollable row. Wide viewports: stacked
            rail. Same markup and same tab order in both cases. */}
        <ul
          aria-labelledby="nav-section-heading"
          className="flex md:block gap-1 overflow-x-auto md:overflow-visible pb-1 md:pb-0 -mx-1 px-1 md:mx-0 md:px-0"
        >
          {visibleItems.map((item) => {
            const Icon = item.icon;
            const isActive = currentView === item.view;
            const badge = badgeFor(item.view);
            return (
              <li key={item.view} className="shrink-0 md:shrink">
                <button
                  id={`nav-${item.view.toLowerCase()}`}
                  onClick={() => onSelectView(item.view)}
                  aria-current={isActive ? 'page' : undefined}
                  className={`w-full min-w-max md:min-w-0 flex items-center justify-between gap-3 px-3 py-2.5 rounded-lg text-xs font-semibold transition ${
                    isActive
                      ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30'
                      : 'hover:bg-slate-800 hover:text-slate-100 text-slate-400 border border-transparent'
                  }`}
                >
                  <span className="flex items-center gap-3">
                    <Icon
                      className={`w-4 h-4 shrink-0 ${isActive ? 'text-emerald-400' : 'text-slate-500'}`}
                    />
                    <span>{item.label}</span>
                  </span>
                  {badge && (
                    <span
                      className={`text-[10px] font-bold px-1.5 py-0.5 rounded whitespace-nowrap ${
                        item.view === 'TEST_SUITE'
                          ? 'bg-emerald-950 text-emerald-300 border border-emerald-800'
                          : 'bg-amber-950 text-amber-300 border border-amber-800'
                      }`}
                    >
                      {badge}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      <div className="hidden md:block p-3 bg-slate-800/60 rounded-lg border border-slate-800 text-[11px] text-slate-400 space-y-1">
        <div className="font-semibold text-slate-300 flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse motion-reduce:animate-none" aria-hidden="true"></span>
          Tenant Isolation Active
        </div>
        <div>All queries are tenant-scoped through the authenticated session.</div>
      </div>
    </nav>
  );
};