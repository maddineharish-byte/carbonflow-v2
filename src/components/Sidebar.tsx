/**
 * CarbonFlow — Main Navigation Sidebar
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
} from 'lucide-react';
import { NavView } from '../types.ts';

interface SidebarProps {
  currentView: NavView;
  onSelectView: (view: NavView) => void;
  auditBadge?: string;
  testPassedCount?: number;
}

interface NavItem {
  view: NavView;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  badge?: string;
}

export const Sidebar: React.FC<SidebarProps> = ({
  currentView,
  onSelectView,
  auditBadge,
  testPassedCount,
}) => {
  const items: NavItem[] = [
    { view: 'DASHBOARD', label: 'Executive Dashboard', icon: LayoutDashboard },
    { view: 'BOUNDARIES', label: 'Boundaries & Facilities', icon: Building },
    { view: 'ACTIVITY_DATA', label: 'Activity Data Collection', icon: Database },
    { view: 'EMISSIONS', label: 'Calculations & Ledger', icon: Calculator },
    { view: 'FACTORS', label: 'Emission Factors & GWP', icon: Layers },
    { view: 'AUDIT', label: 'Audit & Governance', icon: FileCheck2, badge: auditBadge },
    { view: 'EVIDENCE', label: 'Evidence Vault', icon: FolderLock },
    { view: 'TARGETS', label: 'Targets & Projects', icon: Target },
    {
      view: 'TEST_SUITE',
      label: 'Automated Test Suite',
      icon: FlaskConical,
      badge: testPassedCount !== undefined ? `${testPassedCount} PASS` : undefined,
    },
  ];

  return (
    <aside className="w-64 bg-slate-900 border-r border-slate-800 text-slate-300 flex flex-col justify-between p-4 shrink-0 min-h-[calc(100vh-57px)]">
      <div className="space-y-1">
        <div className="text-[11px] font-bold uppercase tracking-wider text-slate-500 px-3 py-2">
          Enterprise Accounting
        </div>
        {items.map((item) => {
          const Icon = item.icon;
          const isActive = currentView === item.view;
          return (
            <button
              key={item.view}
              id={`nav-${item.view.toLowerCase()}`}
              onClick={() => onSelectView(item.view)}
              className={`w-full flex items-center justify-between px-3 py-2.5 rounded-lg text-xs font-semibold transition ${
                isActive
                  ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30'
                  : 'hover:bg-slate-800 hover:text-slate-100 text-slate-400 border border-transparent'
              }`}
            >
              <div className="flex items-center gap-3">
                <Icon className={`w-4 h-4 ${isActive ? 'text-emerald-400' : 'text-slate-500'}`} />
                <span>{item.label}</span>
              </div>
              {item.badge && (
                <span
                  className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                    item.view === 'TEST_SUITE'
                      ? 'bg-emerald-950 text-emerald-300 border border-emerald-800'
                      : 'bg-amber-950 text-amber-300 border border-amber-800'
                  }`}
                >
                  {item.badge}
                </span>
              )}
            </button>
          );
        })}
      </div>

      <div className="p-3 bg-slate-800/60 rounded-lg border border-slate-800 text-[11px] text-slate-400 space-y-1">
        <div className="font-semibold text-slate-300 flex items-center gap-1.5">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
          Tenant Isolation Active
        </div>
        <div>All queries cryptographically bound to authenticated Organization ID.</div>
      </div>
    </aside>
  );
};
