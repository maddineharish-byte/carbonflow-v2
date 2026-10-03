/**
 * CarbonFlow — Analytics & Breakdowns (Phase 7 backend, workstream 8.14)
 * Period summaries and dimension breakdowns are computed by the backend from
 * persisted emission records; the browser only formats them for display.
 * Scope 2 location/market perspectives are always shown side by side.
 */
import React, { useState, useEffect, useCallback } from 'react';
import { ChartLine, AlertTriangle, RefreshCw, FileBarChart, Lock } from 'lucide-react';
import { Breakdown, PeriodSummary, ReportingPeriod } from '../types.ts';
import { api } from '../services/api.ts';
import { formatQuantity } from '../services/format.ts';

interface AnalyticsViewProps {
  periods: ReportingPeriod[];
}

const DIMENSIONS = ['facility', 'legal_entity', 'scope', 'category', 'period'] as const;
type Dimension = (typeof DIMENSIONS)[number];

export const AnalyticsView: React.FC<AnalyticsViewProps> = ({ periods }) => {
  const [selectedPeriodId, setSelectedPeriodId] = useState('');
  const [summary, setSummary] = useState<PeriodSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summaryError, setSummaryError] = useState<string | null>(null);

  const [dimension, setDimension] = useState<Dimension>('scope');
  const [breakdownPeriodId, setBreakdownPeriodId] = useState('');
  const [breakdown, setBreakdown] = useState<Breakdown | null>(null);
  const [breakdownLoading, setBreakdownLoading] = useState(false);
  const [breakdownError, setBreakdownError] = useState<string | null>(null);

  const loadSummary = useCallback(async () => {
    if (!selectedPeriodId) {
      setSummary(null);
      return;
    }
    setSummaryLoading(true);
    setSummaryError(null);
    try {
      setSummary(await api.getPeriodSummary(selectedPeriodId));
    } catch (err: any) {
      setSummaryError(err?.message || 'Failed to load the period summary.');
      setSummary(null);
    } finally {
      setSummaryLoading(false);
    }
  }, [selectedPeriodId]);

  const loadBreakdown = useCallback(async () => {
    setBreakdownLoading(true);
    setBreakdownError(null);
    try {
      setBreakdown(await api.getBreakdown(dimension, breakdownPeriodId || undefined));
    } catch (err: any) {
      setBreakdownError(err?.message || 'Failed to load the breakdown.');
      setBreakdown(null);
    } finally {
      setBreakdownLoading(false);
    }
  }, [dimension, breakdownPeriodId]);

  useEffect(() => {
    void loadSummary();
  }, [loadSummary]);

  useEffect(() => {
    void loadBreakdown();
  }, [loadBreakdown]);

  const formatTonnes = (value: number) => formatQuantity(value, 4);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-white tracking-tight">Analytics & Breakdowns</h1>
        <p className="text-xs text-slate-400 mt-1">
          Backend-computed period summaries and dimension breakdowns over persisted emission records.
        </p>
      </div>

      {/* Period Summary */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-4">
          <div className="flex items-center gap-2">
            <FileBarChart className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">Reporting Period Summary</h2>
          </div>
          <label htmlFor="analytics-summary-period" className="sr-only">
            Reporting period for summary
          </label>
          <select
            id="analytics-summary-period"
            value={selectedPeriodId}
            onChange={(e) => setSelectedPeriodId(e.target.value)}
            className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white focus:outline-none"
            aria-label="Reporting period for summary"
          >
            <option value="">Select period…</option>
            {periods.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
        </div>

        {summaryLoading && (
          <div className="py-6 text-center text-slate-400 text-xs" role="status">
            Loading summary…
          </div>
        )}

        {summaryError && (
          <div
            role="alert"
            className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200"
          >
            <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" aria-hidden="true" />
            {summaryError}
          </div>
        )}

        {!selectedPeriodId && !summaryLoading && (
          <div className="py-6 text-center text-slate-500 text-xs">Select a reporting period to view its summary.</div>
        )}

        {summary && !summaryLoading && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 text-xs">
              <div className="p-3 bg-slate-800 rounded-lg border border-slate-800">
                <div className="text-slate-400">Period</div>
                <div className="font-bold text-white mt-0.5">{summary.period.name}</div>
                <div className="text-[10px] text-slate-500">{summary.period.status}</div>
              </div>
              <div className="p-3 bg-slate-800 rounded-lg border border-slate-800">
                <div className="text-slate-400">Emission Records</div>
                <div className="font-bold text-white mt-0.5">{summary.counts.emissionRecords}</div>
                <div className="text-[10px] text-slate-500">
                  {summary.counts.activityData} activities · {summary.counts.calculations} calculations
                </div>
              </div>
              <div className="p-3 bg-slate-800 rounded-lg border border-slate-800">
                <div className="text-slate-400">Facility Coverage</div>
                <div className="font-bold text-white mt-0.5">
                  {summary.coverage.facilitiesWithEmissions}/{summary.coverage.facilitiesTotal}
                </div>
                <div className="text-[10px] text-slate-500">facilities with emissions</div>
              </div>
              <div className="p-3 bg-slate-800 rounded-lg border border-slate-800">
                <div className="text-slate-400">Governance</div>
                <div className="font-bold text-white mt-0.5 flex items-center gap-1">
                  {summary.governance.locked && <Lock className="w-3 h-3 text-amber-400" />}
                  {summary.governance.locked ? 'Locked' : 'Open'}
                </div>
                <div className="text-[10px] text-slate-500">
                  {summary.governance.auditStatus ? `Audit: ${summary.governance.auditStatus}` : 'No audit'}
                </div>
              </div>
            </div>

            <div>
              <div className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mb-2">
                Ledger Totals (tCO₂e, both Scope 2 perspectives separate)
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-6 gap-2 text-xs">
                {[
                  { label: 'Scope 1', value: summary.totals.scope1Tonnes, color: 'text-orange-400' },
                  { label: 'Scope 2 Location', value: summary.totals.scope2LocationTonnes, color: 'text-sky-400' },
                  { label: 'Scope 2 Market', value: summary.totals.scope2MarketTonnes, color: 'text-emerald-400' },
                  { label: 'Total (Location)', value: summary.totals.totalLocationBasedTonnes, color: 'text-white' },
                  { label: 'Total (Market)', value: summary.totals.totalMarketBasedTonnes, color: 'text-white' },
                  { label: 'Scope 3', value: summary.totals.scope3Tonnes, color: 'text-slate-400' },
                ].map((item) => (
                  <div key={item.label} className="p-3 bg-slate-800 rounded-lg border border-slate-800">
                    <div className="text-slate-400 text-[10px]">{item.label}</div>
                    <div className={`font-mono font-bold mt-0.5 ${item.color}`}>{formatTonnes(item.value)}</div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Dimension Breakdown */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-4">
          <div className="flex items-center gap-2">
            <ChartLine className="w-4 h-4 text-sky-400" />
            <h2 className="text-sm font-bold text-white">Dimension Breakdown</h2>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="analytics-dimension" className="sr-only">
              Breakdown dimension
            </label>
            <select
              id="analytics-dimension"
              value={dimension}
              onChange={(e) => setDimension(e.target.value as Dimension)}
              className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white focus:outline-none"
              aria-label="Breakdown dimension"
            >
              {DIMENSIONS.map((d) => (
                <option key={d} value={d}>{d.replace('_', ' ')}</option>
              ))}
            </select>
            {dimension !== 'period' && (
              <>
                <label htmlFor="analytics-breakdown-period" className="sr-only">
                  Optional period filter
                </label>
                <select
                  id="analytics-breakdown-period"
                  value={breakdownPeriodId}
                  onChange={(e) => setBreakdownPeriodId(e.target.value)}
                  className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-1.5 text-xs text-white focus:outline-none"
                  aria-label="Optional period filter"
                >
                  <option value="">All periods</option>
                  {periods.map((p) => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </>
            )}
            <button
              onClick={() => void loadBreakdown()}
              disabled={breakdownLoading}
              className="p-1.5 text-slate-400 hover:text-white rounded-md hover:bg-slate-800 transition disabled:cursor-not-allowed disabled:opacity-50"
              title="Refresh breakdown"
              aria-label="Refresh breakdown"
              aria-busy={breakdownLoading || undefined}
            >
              <RefreshCw
                className={`w-4 h-4 ${breakdownLoading ? 'animate-spin text-emerald-400' : ''}`}
                aria-hidden="true"
              />
            </button>
          </div>
        </div>

        {breakdownLoading && (
          <div className="py-6 text-center text-slate-400 text-xs" role="status">
            Loading breakdown…
          </div>
        )}

        {breakdownError && (
          <div
            role="alert"
            className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200"
          >
            <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" aria-hidden="true" />
            {breakdownError}
          </div>
        )}

        {breakdown && !breakdownLoading && (
          <>
            {breakdown.rows.length === 0 ? (
              <div className="py-6 text-center text-slate-500 text-xs">
                No rows for this dimension yet — log and calculate activity data first.
              </div>
            ) : (
              <div
                className="overflow-x-auto"
                tabIndex={0}
                role="group"
                aria-label="Dimension breakdown table, scrollable"
              >
                <table className="w-full min-w-[48rem] text-left text-xs text-slate-300">
                  <caption className="sr-only">
                    Emissions broken down by {breakdown.dimension.replace('_', ' ')}. Scope 2 location and market
                    perspectives are reported separately.
                  </caption>
                  <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
                    <tr>
                      <th scope="col" className="px-4 py-3">{breakdown.dimension.replace('_', ' ')}</th>
                      <th scope="col" className="px-4 py-3 text-right">Scope 1</th>
                      <th scope="col" className="px-4 py-3 text-right">Scope 2 Location</th>
                      <th scope="col" className="px-4 py-3 text-right">Scope 2 Market</th>
                      <th scope="col" className="px-4 py-3 text-right">Total (Location)</th>
                      <th scope="col" className="px-4 py-3 text-right">Total (Market)</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800">
                    {breakdown.rows.map((row) => (
                      <tr key={row.key} className="hover:bg-slate-800/50 transition">
                        <td className="px-4 py-3 font-semibold text-white">{row.label}</td>
                        <td className="px-4 py-3 text-right font-mono">{formatTonnes(row.scope1Tonnes)}</td>
                        <td className="px-4 py-3 text-right font-mono text-sky-400">{formatTonnes(row.scope2LocationTonnes)}</td>
                        <td className="px-4 py-3 text-right font-mono text-emerald-400">{formatTonnes(row.scope2MarketTonnes)}</td>
                        <td className="px-4 py-3 text-right font-mono">{formatTonnes(row.totalLocationBasedTonnes)}</td>
                        <td className="px-4 py-3 text-right font-mono">{formatTonnes(row.totalMarketBasedTonnes)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};
