/**
 * CarbonFlow — Executive Dashboard View
 * Features strict Dual-Reporting presentation, GHG breakdown, and audit readiness health.
 */
import React, { useState } from 'react';
import {
  ShieldAlert,
  ShieldCheck,
  TrendingDown,
  Building2,
  FileCheck,
  Flame,
  Zap,
  Camera,
  Download,
  CheckCircle2,
  AlertCircle,
  Activity,
  Layers,
  ArrowDownRight,
  Sparkles,
} from 'lucide-react';
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  PieChart,
  Pie,
  Cell,
  Legend,
} from 'recharts';
import { DashboardSummary, ReportingPeriod } from '../types.ts';
import { api } from '../services/api.ts';
import { TrendInsightsSection } from './TrendInsightsSection.tsx';

interface DashboardViewProps {
  data: DashboardSummary | null;
  periods: ReportingPeriod[];
  onNavigate: (view: any) => void;
  onSnapshot: (periodId: string) => void;
}

const COLORS = ['#10b981', '#0ea5e9', '#f59e0b', '#8b5cf6', '#ec4899'];

export const DashboardView: React.FC<DashboardViewProps> = ({ data, periods, onNavigate, onSnapshot }) => {
  const [isExporting, setIsExporting] = useState(false);
  const [exportStatus, setExportStatus] = useState<{ success: boolean; message: string } | null>(null);
  const [trendViewMode, setTrendViewMode] = useState<'ALL' | 'DUAL_SCOPE2' | 'SCOPE1' | 'TOTALS'>('ALL');
  const [snapshotPeriodId, setSnapshotPeriodId] = useState('');

  if (!data) {
    return (
      <div className="p-8 text-slate-400" role="status">
        Loading enterprise metrics...
      </div>
    );
  }

  const { emissions, auditStatus, auditHealth, categories, facilities } = data;

  // Real reporting periods only — the backend returns the tenant's actual
  // periods (zero-filled, never synthetic). No data → empty state below.
  const trendData = data.periodTrends ?? [];

  const hasTrendData = trendData.length > 0;
  const totalPeriods = trendData.length;
  const firstPeriod = trendData[0];
  const latestPeriod = trendData[totalPeriods - 1];
  const cumulativeMarketTonnes = trendData.reduce((acc, p) => acc + p.totalMarketBasedTonnes, 0);
  const avgMarketTonnes = totalPeriods > 0 ? (cumulativeMarketTonnes / totalPeriods) : 0;
  const netReductionPct = firstPeriod && firstPeriod.totalMarketBasedTonnes > 0
    ? (((firstPeriod.totalMarketBasedTonnes - latestPeriod.totalMarketBasedTonnes) / firstPeriod.totalMarketBasedTonnes) * 100).toFixed(1)
    : '0.0';
  const totalAvoidedMarketTonnes = trendData.reduce(
    (acc, p) => acc + Math.max(0, p.totalLocationBasedTonnes - p.totalMarketBasedTonnes),
    0
  );

  const handleExportCsv = async () => {
    try {
      setIsExporting(true);
      setExportStatus(null);
      await api.exportEmissionReportCsv();
      setExportStatus({ success: true, message: 'Emission inventory report exported successfully.' });
      setTimeout(() => setExportStatus(null), 3500);
    } catch (err: any) {
      setExportStatus({ success: false, message: err?.message || 'Failed to export emission report.' });
      setTimeout(() => setExportStatus(null), 4500);
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Top Banner: Dual-Reporting & Audit State */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 sm:p-6 flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4 shadow-sm">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-bold text-white tracking-tight">GHG Accounting & Audit Readiness</h1>
            <span
              className={`text-xs font-bold px-2.5 py-0.5 rounded-full border ${
                auditStatus === 'LOCKED'
                  ? 'bg-emerald-950 text-emerald-300 border-emerald-800'
                  : auditStatus === 'APPROVED'
                  ? 'bg-blue-950 text-blue-300 border-blue-800'
                  : 'bg-amber-950 text-amber-300 border-amber-800'
              }`}
            >
              CYCLE: {auditStatus}
            </span>
          </div>
          <p className="text-xs text-slate-400 mt-1 max-w-3xl">
            GHG Protocol Corporate Standard dual-reporting presentation. Scope 2 Location and Market-based
            emissions are reported separately to ensure non-aggregation integrity.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3 w-full sm:w-auto">
          <button
            onClick={handleExportCsv}
            disabled={isExporting}
            className="flex-1 sm:flex-none flex items-center justify-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 active:bg-slate-900 text-slate-200 hover:text-white text-xs font-semibold rounded-lg border border-slate-700 hover:border-slate-600 transition disabled:opacity-50 disabled:cursor-not-allowed"
            title="Download complete audited emissions inventory report as CSV"
            aria-busy={isExporting || undefined}
          >
            <Download
              className={`w-4 h-4 text-emerald-400 ${isExporting ? 'animate-bounce' : ''}`}
              aria-hidden="true"
            />
            <span>{isExporting ? 'Exporting CSV...' : 'Export CSV'}</span>
          </button>
          <label htmlFor="dashboard-snapshot-period" className="sr-only">
            Reporting period for snapshot
          </label>
          <select
            id="dashboard-snapshot-period"
            value={snapshotPeriodId || periods[0]?.id || ''}
            onChange={(e) => setSnapshotPeriodId(e.target.value)}
            className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-2 text-xs text-white focus:outline-none"
            aria-label="Reporting period for snapshot"
          >
            {periods.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
          <button
            onClick={() => {
              const periodId = snapshotPeriodId || periods[0]?.id;
              if (periodId) onSnapshot(periodId);
            }}
            disabled={periods.length === 0}
            title={
              periods.length === 0
                ? 'Create a reporting period before generating a snapshot'
                : 'Generate an immutable inventory snapshot'
            }
            className="flex-1 sm:flex-none flex items-center justify-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Camera className="w-4 h-4" aria-hidden="true" />
            Create Snapshot
          </button>
          <button
            onClick={() => onNavigate('AUDIT')}
            className="flex-1 sm:flex-none flex items-center justify-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition"
          >
            <FileCheck className="w-4 h-4 text-sky-400" aria-hidden="true" />
            Audit Room
          </button>
        </div>
      </div>

      {/* Export result is announced to screen readers as it appears. */}
      {exportStatus && (
        <div
          role={exportStatus.success ? 'status' : 'alert'}
          className={`flex items-center gap-2 px-4 py-2.5 rounded-lg text-xs font-medium border ${
            exportStatus.success
              ? 'bg-emerald-950/80 border-emerald-800 text-emerald-300'
              : 'bg-rose-950/80 border-rose-800 text-rose-300'
          }`}
        >
          {exportStatus.success ? (
            <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" aria-hidden="true" />
          ) : (
            <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" aria-hidden="true" />
          )}
          <span>{exportStatus.message}</span>
        </div>
      )}

      {/* Primary KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
        {/* Scope 1 */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">Scope 1 Direct</span>
            <div className="w-8 h-8 rounded-lg bg-orange-500/10 border border-orange-500/30 flex items-center justify-center text-orange-400">
              <Flame className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3">
            <div className="text-2xl font-bold text-white tracking-tight">
              {emissions.scope1Tonnes.toLocaleString()} <span className="text-xs font-normal text-slate-400">tCO₂e</span>
            </div>
            <div className="text-[11px] text-slate-500 mt-1">Stationary, mobile & fugitive leaks</div>
          </div>
        </div>

        {/* Scope 2 Location-Based */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">Scope 2 (Location)</span>
            <div className="w-8 h-8 rounded-lg bg-sky-500/10 border border-sky-500/30 flex items-center justify-center text-sky-400">
              <Zap className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3">
            <div className="text-2xl font-bold text-sky-400 tracking-tight">
              {emissions.scope2LocationTonnes.toLocaleString()}{' '}
              <span className="text-xs font-normal text-slate-400">tCO₂e</span>
            </div>
            <div className="text-[11px] text-slate-500 mt-1">Regional grid average emission factors</div>
          </div>
        </div>

        {/* Scope 2 Market-Based */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">Scope 2 (Market)</span>
            <div className="w-8 h-8 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
              <Zap className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3">
            <div className="text-2xl font-bold text-emerald-400 tracking-tight">
              {emissions.scope2MarketTonnes.toLocaleString()}{' '}
              <span className="text-xs font-normal text-slate-400">tCO₂e</span>
            </div>
            <div className="text-[11px] text-slate-500 mt-1">Supplier contracts & PPA certificates</div>
          </div>
        </div>

        {/* Audit Readiness Metric */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">Audit Checklist</span>
            <div className="w-8 h-8 rounded-lg bg-indigo-500/10 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
              <ShieldCheck className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3">
            <div className="text-2xl font-bold text-white tracking-tight">
              {auditHealth.checklistSatisfied} / {auditHealth.checklistTotal}
            </div>
            <div className="text-[11px] text-slate-500 mt-1 flex items-center gap-1.5">
              {auditHealth.openFindingsCount > 0 ? (
                <span className="text-amber-400 font-medium flex items-center gap-1">
                  <ShieldAlert className="w-3 h-3" /> {auditHealth.openFindingsCount} open finding(s)
                </span>
              ) : (
                <span className="text-emerald-400 font-medium">All findings resolved</span>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Dual Reporting Highlight Box */}
      <div className="bg-gradient-to-r from-slate-900 via-slate-900 to-slate-800 border border-slate-800 rounded-xl p-6 shadow-sm">
        <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-3">
          Dual-Reporting Totals (Non-Aggregated Presentation)
        </div>
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
          <div className="p-4 bg-slate-800/60 rounded-lg border border-slate-700/80">
            <div className="text-xs font-semibold text-slate-400">Total Emissions (Location-Based Approach)</div>
            <div className="text-3xl font-extrabold text-white mt-1">
              {emissions.totalLocationBasedTonnes.toLocaleString()}{' '}
              <span className="text-sm font-normal text-slate-400">tCO₂e</span>
            </div>
            <div className="text-[11px] text-slate-400 mt-2">
              Formula: Scope 1 ({emissions.scope1Tonnes} t) + Scope 2 Location ({emissions.scope2LocationTonnes} t)
            </div>
          </div>

          <div className="p-4 bg-slate-800/60 rounded-lg border border-slate-700/80">
            <div className="text-xs font-semibold text-slate-400">Total Emissions (Market-Based Approach)</div>
            <div className="text-3xl font-extrabold text-emerald-400 mt-1">
              {emissions.totalMarketBasedTonnes.toLocaleString()}{' '}
              <span className="text-sm font-normal text-slate-400">tCO₂e</span>
            </div>
            <div className="text-[11px] text-slate-400 mt-2">
              Formula: Scope 1 ({emissions.scope1Tonnes} t) + Scope 2 Market ({emissions.scope2MarketTonnes} t)
            </div>
          </div>
        </div>
      </div>

      {/* Reporting Periods Trend Visualizer — real periods only, empty state when none */}
      {hasTrendData ? (
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 shadow-sm">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 mb-6">
          <div>
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
                <Activity className="w-4 h-4" />
              </div>
              <h2 className="text-base font-bold text-white tracking-tight">
                Emissions Trajectory — Last 12 Reporting Periods
              </h2>
            </div>
            <p className="text-xs text-slate-400 mt-1">
              Multi-period historical trends comparing Scope 1 direct, Scope 2 Location-based, and Scope 2 Market-based emissions
            </p>
          </div>

          {/* Quick Metrics & Filter Controls */}
          <div className="flex flex-wrap items-center gap-2.5">
            {/* Stream filter. Implemented as a single-select radiogroup so that
                "which series are shown" is conveyed by state, not by the
                highlighted background alone. */}
            <div
              role="radiogroup"
              aria-label="Filter emissions trajectory streams"
              className="flex flex-wrap items-center bg-slate-950 p-1 rounded-lg border border-slate-800 text-xs"
            >
              {(
                [
                  { mode: 'ALL', label: 'All Streams' },
                  { mode: 'DUAL_SCOPE2', label: 'Scope 2 Dual' },
                  { mode: 'SCOPE1', label: 'Scope 1 Direct' },
                  { mode: 'TOTALS', label: 'Total Impact' },
                ] as const
              ).map(({ mode, label }) => (
                <button
                  key={mode}
                  type="button"
                  role="radio"
                  aria-checked={trendViewMode === mode}
                  onClick={() => setTrendViewMode(mode)}
                  className={`px-3 py-1.5 rounded-md font-medium transition ${
                    trendViewMode === mode
                      ? 'bg-emerald-600 text-white shadow-sm'
                      : 'text-slate-400 hover:text-white'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Period Stats Bar */}
        <div className="grid grid-cols-2 xl:grid-cols-4 gap-3 mb-5 p-3.5 bg-slate-950/60 rounded-lg border border-slate-800/80">
          <div>
            <div className="text-[11px] font-medium text-slate-400">{totalPeriods}-Period Net Trend</div>
            <div className="text-sm font-bold text-emerald-400 flex items-center gap-1 mt-0.5">
              <ArrowDownRight className="w-3.5 h-3.5" />
              <span>{netReductionPct}% reduction</span>
            </div>
          </div>
          <div>
            <div className="text-[11px] font-medium text-slate-400">{totalPeriods}-Period Cumulative (Market)</div>
            <div className="text-sm font-bold text-white mt-0.5">
              {cumulativeMarketTonnes.toLocaleString(undefined, { maximumFractionDigits: 1 })}{' '}
              <span className="text-[10px] text-slate-400 font-normal">tCO₂e</span>
            </div>
          </div>
          <div>
            <div className="text-[11px] font-medium text-slate-400">Average per Period</div>
            <div className="text-sm font-bold text-sky-400 mt-0.5">
              {avgMarketTonnes.toFixed(1)}{' '}
              <span className="text-[10px] text-slate-400 font-normal">tCO₂e / cycle</span>
            </div>
          </div>
          <div>
            <div className="text-[11px] font-medium text-slate-400">Market Decoupling Savings</div>
            <div className="text-sm font-bold text-emerald-400 flex items-center gap-1 mt-0.5">
              <Sparkles className="w-3 h-3 text-emerald-400" />
              <span>{totalAvoidedMarketTonnes.toFixed(1)} t avoided</span>
            </div>
          </div>
        </div>

        {/* Recharts LineChart */}
        <div
          className="h-72 w-full"
          role="img"
          aria-label={`Emissions trajectory line chart across ${totalPeriods} reporting periods, comparing Scope 1 direct, Scope 2 location-based and Scope 2 market-based emissions. The same values are listed in the period statistics above.`}
        >
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={trendData} margin={{ top: 12, right: 20, left: -15, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#334155" opacity={0.6} />
              <XAxis
                dataKey="shortName"
                stroke="#94a3b8"
                fontSize={11}
                tickLine={false}
                axisLine={{ stroke: '#475569' }}
              />
              <YAxis
                stroke="#94a3b8"
                fontSize={11}
                tickLine={false}
                axisLine={{ stroke: '#475569' }}
                tickFormatter={(val) => `${val} t`}
              />
              <Tooltip
                content={({ active, payload, label }) => {
                  if (active && payload && payload.length) {
                    const item = payload[0].payload;
                    const avoided = Number((item.totalLocationBasedTonnes - item.totalMarketBasedTonnes).toFixed(2));
                    return (
                      <div className="bg-slate-950/95 border border-slate-700/80 backdrop-blur-md rounded-xl p-3.5 text-xs shadow-2xl min-w-[220px]">
                        <div className="flex items-center justify-between pb-2 mb-2 border-b border-slate-800">
                          <span className="font-bold text-white text-[13px]">{item.periodName || label}</span>
                          <span className="text-[10px] text-slate-400 bg-slate-800/80 px-2 py-0.5 rounded-full">
                            Cycle {item.shortName}
                          </span>
                        </div>
                        <div className="space-y-1.5">
                          {payload.map((entry: any, index: number) => (
                            <div key={`entry-${index}`} className="flex items-center justify-between gap-4">
                              <span className="flex items-center gap-1.5 text-slate-300">
                                <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: entry.color }} />
                                <span>{entry.name}:</span>
                              </span>
                              <span className="font-semibold text-white tracking-tight">
                                {Number(entry.value).toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 2 })}{' '}
                                tCO₂e
                              </span>
                            </div>
                          ))}
                          {avoided > 0 && (
                            <div className="pt-2 mt-1.5 border-t border-slate-800 flex items-center justify-between text-emerald-400">
                              <span className="text-[11px] font-medium flex items-center gap-1">
                                <Sparkles className="w-3 h-3" /> Market Avoided Delta:
                              </span>
                              <span className="font-bold text-[11px]">-{avoided.toLocaleString()} tCO₂e</span>
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  }
                  return null;
                }}
              />
              <Legend wrapperStyle={{ fontSize: '11px', paddingTop: '10px' }} />

              {/* Scope 1 Direct Line */}
              {(trendViewMode === 'ALL' || trendViewMode === 'SCOPE1') && (
                <Line
                  type="monotone"
                  dataKey="scope1Tonnes"
                  name="Scope 1 Direct"
                  stroke="#f97316"
                  strokeWidth={2.5}
                  dot={{ r: 3.5, fill: '#f97316', strokeWidth: 1 }}
                  activeDot={{ r: 6, stroke: '#fff', strokeWidth: 2 }}
                />
              )}

              {/* Scope 2 Location Line */}
              {(trendViewMode === 'ALL' || trendViewMode === 'DUAL_SCOPE2') && (
                <Line
                  type="monotone"
                  dataKey="scope2LocationTonnes"
                  name="Scope 2 Location"
                  stroke="#0ea5e9"
                  strokeWidth={2.5}
                  dot={{ r: 3.5, fill: '#0ea5e9', strokeWidth: 1 }}
                  activeDot={{ r: 6, stroke: '#fff', strokeWidth: 2 }}
                />
              )}

              {/* Scope 2 Market Line */}
              {(trendViewMode === 'ALL' || trendViewMode === 'DUAL_SCOPE2') && (
                <Line
                  type="monotone"
                  dataKey="scope2MarketTonnes"
                  name="Scope 2 Market"
                  stroke="#10b981"
                  strokeWidth={2.5}
                  dot={{ r: 3.5, fill: '#10b981', strokeWidth: 1 }}
                  activeDot={{ r: 6, stroke: '#fff', strokeWidth: 2 }}
                />
              )}

              {/* Total Location-Based Line */}
              {(trendViewMode === 'ALL' || trendViewMode === 'TOTALS' || trendViewMode === 'DUAL_SCOPE2') && (
                <Line
                  type="monotone"
                  dataKey="totalLocationBasedTonnes"
                  name="Total (Location-Based)"
                  stroke="#a855f7"
                  strokeWidth={2}
                  strokeDasharray="4 4"
                  dot={{ r: 3, fill: '#a855f7' }}
                  activeDot={{ r: 5, stroke: '#fff', strokeWidth: 2 }}
                />
              )}

              {/* Total Market-Based Line */}
              {(trendViewMode === 'ALL' || trendViewMode === 'TOTALS' || trendViewMode === 'DUAL_SCOPE2') && (
                <Line
                  type="monotone"
                  dataKey="totalMarketBasedTonnes"
                  name="Total (Market-Based)"
                  stroke="#34d399"
                  strokeWidth={2}
                  strokeDasharray="2 2"
                  dot={{ r: 3, fill: '#34d399' }}
                  activeDot={{ r: 5, stroke: '#fff', strokeWidth: 2 }}
                />
              )}
            </LineChart>
          </ResponsiveContainer>
        </div>
      </div>
      ) : (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 shadow-sm">
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <Activity className="w-8 h-8 text-slate-600 mb-3" />
            <div className="text-sm font-semibold text-slate-300">No reporting periods with data yet</div>
            <div className="text-xs text-slate-500 mt-1 max-w-sm">
              Trends appear here once reporting periods exist and activity data has been calculated.
            </div>
            <button
              onClick={() => onNavigate('ACTIVITY_DATA')}
              className="mt-4 px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg transition"
            >
              Log Activity Data
            </button>
          </div>
        </div>
      )}

      {/* AI Trend Insights & Anomaly Detection Section */}
      <TrendInsightsSection onNavigateToTargets={() => onNavigate('TARGETS')} />

      {/* Analytical Charts */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* Facility Emissions Chart */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-4">
            <div>
              <div className="text-sm font-bold text-white">Facility Emissions Breakdown</div>
              <div className="text-xs text-slate-400">Scope 1 and Scope 2 split per reporting site</div>
            </div>
            <Building2 className="w-4 h-4 text-slate-500" />
          </div>

          <div
              className="h-64 w-full"
              role="img"
              aria-label={`Grouped bar chart of Scope 1 direct and Scope 2 electricity emissions for ${facilities.length} reporting facilities.`}
            >
            {facilities.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center">
                <Building2 className="w-8 h-8 text-slate-600 mb-2" aria-hidden="true" />
                <div className="text-xs text-slate-500">No facility data yet — add facilities and log activity data.</div>
              </div>
            ) : (
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={facilities} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                <XAxis dataKey="code" stroke="#94a3b8" fontSize={11} />
                <YAxis stroke="#94a3b8" fontSize={11} />
                <Tooltip
                  contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                />
                <Legend wrapperStyle={{ fontSize: '11px', paddingTop: '10px' }} />
                <Bar dataKey="scope1Tonnes" name="Scope 1 Direct" fill="#f97316" radius={[4, 4, 0, 0]} />
                <Bar dataKey="scope2Tonnes" name="Scope 2 Electricity" fill="#0ea5e9" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
            )}
          </div>
        </div>

        {/* Category Share Chart */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
          <div className="flex items-center justify-between mb-4">
            <div>
              <div className="text-sm font-bold text-white">Emissions by Source Category</div>
              <div className="text-xs text-slate-400">Combustion, Electricity, Refrigerants</div>
            </div>
            <TrendingDown className="w-4 h-4 text-slate-500" />
          </div>

          <div
              className="h-64 w-full flex items-center justify-center"
              role="img"
              aria-label={`Pie chart of emissions share by source category across ${categories.length} categories.`}
            >
            {categories.length > 0 ? (
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={categories}
                    dataKey="tonnes"
                    nameKey="category"
                    cx="50%"
                    cy="50%"
                    outerRadius={80}
                    label={(entry: any) => `${((entry.percent || 0) * 100).toFixed(0)}%`}
                    labelLine={false}
                  >
                    {categories.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip
                    formatter={(val: any) => [`${val} tCO2e`, 'Emissions']}
                    contentStyle={{ backgroundColor: '#0f172a', borderColor: '#334155', borderRadius: '8px', fontSize: '12px' }}
                  />
                  <Legend wrapperStyle={{ fontSize: '11px' }} />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <div className="text-xs text-slate-500">No emission category data found.</div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
