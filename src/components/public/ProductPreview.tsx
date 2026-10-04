/**
 * CarbonFlow — Platform preview.
 *
 * A realistic static representation of the authenticated workspace's chrome,
 * built from the same vocabulary the app uses in its sidebar (Executive
 * Dashboard, Activity Data Collection, Calculations & Ledger, Evidence Vault,
 * Audit & Governance, Reports). It contains NO production-looking numbers and
 * is explicitly labelled as a demo preview, so it cannot be mistaken for a
 * claim about real data.
 */

import React from 'react';
import {
  Activity,
  ClipboardCheck,
  Database,
  FileBarChart2,
  FileText,
  LayoutDashboard,
} from 'lucide-react';

const NAV = [
  { icon: LayoutDashboard, label: 'Executive Dashboard' },
  { icon: Database, label: 'Activity Data' },
  { icon: Activity, label: 'Calculations' },
  { icon: FileText, label: 'Evidence Vault' },
  { icon: ClipboardCheck, label: 'Audit & Governance' },
  { icon: FileBarChart2, label: 'Reports' },
];

const ROWS: Array<{ label: string; state: string; tone: 'ready' | 'review' | 'track' }> = [
  { label: 'Activity records', state: 'Submitted', tone: 'ready' },
  { label: 'Calculation snapshots', state: 'Frozen', tone: 'ready' },
  { label: 'Evidence versions', state: 'Hash-verified', tone: 'ready' },
  { label: 'Scope 2 (Location)', state: 'Presented separately', tone: 'track' },
  { label: 'Scope 2 (Market)', state: 'Presented separately', tone: 'track' },
  { label: 'Audit cycle', state: 'In review', tone: 'review' },
];

const toneDot: Record<'ready' | 'review' | 'track', string> = {
  ready: 'bg-brand-500',
  review: 'bg-amber-500',
  track: 'bg-ink-400',
};

export const ProductPreview: React.FC = () => (
  <div
    role="img"
    aria-label="A static illustration of the CarbonFlow workspace: a sidebar with the main sections and a panel listing submission and review statuses. No real data is shown."
    className="overflow-hidden rounded-2xl border border-line bg-surface shadow-[0_16px_48px_rgba(20,26,22,0.10)]"
  >
    {/* Window chrome */}
    <div className="flex items-center gap-1.5 border-b border-line bg-sand-100 px-4 py-3">
      <span aria-hidden="true" className="h-2.5 w-2.5 rounded-full bg-line-strong" />
      <span aria-hidden="true" className="h-2.5 w-2.5 rounded-full bg-line-strong" />
      <span aria-hidden="true" className="h-2.5 w-2.5 rounded-full bg-line-strong" />
      <span className="ml-3 text-[11px] font-medium text-ink-500">carbonflow / organization workspace</span>
      <span className="ml-auto rounded-full border border-brand-200 bg-brand-50 px-2.5 py-0.5 text-[10px] font-semibold text-brand-700">
        Demo preview
      </span>
    </div>

    <div className="grid grid-cols-1 md:grid-cols-[minmax(0,14rem)_minmax(0,1fr)]">
      {/* Sidebar */}
      <nav aria-label="Preview navigation" className="hidden border-r border-line bg-sand-50 p-4 md:block">
        <p className="px-2 text-[10px] font-bold uppercase tracking-[0.18em] text-ink-400">
          Workspace
        </p>
        <ul className="mt-3 space-y-1">
          {NAV.map((item) => (
            <li key={item.label}>
              <span className="flex items-center gap-3 rounded-lg px-2 py-2 text-xs font-semibold text-ink-700">
                <item.icon className="h-4 w-4 text-ink-400" aria-hidden="true" />
                {item.label}
              </span>
            </li>
          ))}
        </ul>
        <p className="mt-6 rounded-lg border border-line bg-surface p-3 text-[11px] leading-relaxed text-ink-500">
          Demo overview — the live app renders your organization\'s own records.
        </p>
      </nav>

      {/* Main area */}
      <div className="p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-ink-400">Audit cycle</p>
            <h3 className="mt-1 text-lg font-bold tracking-tight text-ink-950">Reporting position</h3>
          </div>
          <span className="rounded-full border border-amber-200 bg-amber-50 px-3 py-1 text-[11px] font-semibold text-amber-700">
            In review
          </span>
        </div>

        <dl className="mt-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
          {[
            { label: 'Activity', value: 'Submitted' },
            { label: 'Evidence', value: 'Hash-verified' },
            { label: 'Snapshot', value: 'Locked' },
            { label: 'Findings', value: 'Tracked' },
          ].map((stat) => (
            <div key={stat.label} className="rounded-xl border border-line bg-sand-50 p-3">
              <dt className="text-[11px] font-semibold uppercase tracking-wider text-ink-400">{stat.label}</dt>
              <dd className="mt-1 text-sm font-semibold text-ink-900">{stat.value}</dd>
            </div>
          ))}
        </dl>

        <ul className="mt-5 divide-y divide-line rounded-xl border border-line">
          {ROWS.map((row) => (
            <li key={row.label} className="flex items-center justify-between gap-4 px-4 py-3">
              <span className="flex items-center gap-3 text-sm text-ink-900">
                <span aria-hidden="true" className={`h-2 w-2 rounded-full ${toneDot[row.tone]}`} />
                {row.label}
              </span>
              <span className="text-xs font-semibold uppercase tracking-wider text-ink-500">{row.state}</span>
            </li>
          ))}
        </ul>

        <p className="mt-4 text-[11px] leading-relaxed text-ink-400">
          Illustration only. Labels match the approved workspace terminology; statuses
          and counts are placeholders, not live metrics.
        </p>
      </div>
    </div>
  </div>
);

/** Small bar chart placeholder for analytics visuals — demo only. */
export const MiniTrendBars: React.FC = () => (
  <div
    aria-hidden="true"
    className="flex h-20 items-end gap-1.5"
  >
    {[38, 62, 48, 74, 56, 88, 64].map((height, index) => (
      <span
        key={index}
        className="flex-1 rounded-sm bg-brand-200"
        style={{ height: `${height}%` }}
      />
    ))}
  </div>
);