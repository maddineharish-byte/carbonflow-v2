/**
 * CarbonFlow — Trend Insights & Anomaly Detection Component
 * Powered by the backend's deterministic in-process analytics engine
 * (no external LLM): period-over-period deltas, thresholds and
 * rule-based observations computed from persisted emission records.
 */
import React, { useState, useEffect } from 'react';
import {
  Sparkles,
  AlertTriangle,
  Lightbulb,
  RefreshCw,
  ArrowRight,
  Clock,
  Target,
  Gauge,
  CheckCircle2,
  TrendingDown,
  TrendingUp,
  Minus,
  Activity,
  Zap,
  Building,
  Truck,
  Leaf,
} from 'lucide-react';
import { TrendInsightsResponse, EmissionAnomaly, ReductionOpportunity } from '../types.ts';
import { api } from '../services/api.ts';

interface TrendInsightsSectionProps {
  onNavigateToTargets?: () => void;
}

export const TrendInsightsSection: React.FC<TrendInsightsSectionProps> = ({ onNavigateToTargets }) => {
  const [insights, setInsights] = useState<TrendInsightsResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'ALL' | 'ANOMALIES' | 'OPPORTUNITIES'>('ALL');

  const TAB_ORDER: ('ALL' | 'ANOMALIES' | 'OPPORTUNITIES')[] = ['ALL', 'ANOMALIES', 'OPPORTUNITIES'];
  const TAB_IDS: Record<string, string> = {
    ALL: 'insights-tab-all',
    ANOMALIES: 'insights-tab-anomalies',
    OPPORTUNITIES: 'insights-tab-opportunities',
  };

  /** Roving-tabindex arrow-key navigation for the analysis tablist. */
  const handleTabKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>, current: 'ALL' | 'ANOMALIES' | 'OPPORTUNITIES') => {
    const delta = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    if (delta === 0 && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();

    let nextIndex: number;
    if (event.key === 'Home') nextIndex = 0;
    else if (event.key === 'End') nextIndex = TAB_ORDER.length - 1;
    else nextIndex = (TAB_ORDER.indexOf(current) + delta + TAB_ORDER.length) % TAB_ORDER.length;

    const next = TAB_ORDER[nextIndex];
    setActiveTab(next);
    document.getElementById(TAB_IDS[next])?.focus();
  };

  const fetchInsights = async (isManualRefresh = false) => {
    try {
      if (isManualRefresh) {
        setRefreshing(true);
      } else {
        setLoading(true);
      }
      setError(null);
      const data = await api.getTrendInsights(isManualRefresh);
      setInsights(data);
    } catch (err: any) {
      console.error('Failed to load trend insights:', err);
      setError(err?.message || 'Failed to analyze trend data.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchInsights();
  }, []);

  const getAnomalyBadgeColor = (type: EmissionAnomaly['type']) => {
    switch (type) {
      case 'SPIKE':
        return 'bg-amber-500/10 text-amber-400 border-amber-500/30';
      case 'DIVERGENCE':
        return 'bg-purple-500/10 text-purple-400 border-purple-500/30';
      case 'DRIFT':
        return 'bg-sky-500/10 text-sky-400 border-sky-500/30';
      case 'UNUSUAL_PATTERN':
      default:
        return 'bg-blue-500/10 text-blue-400 border-blue-500/30';
    }
  };

  const getSeverityBadge = (severity: EmissionAnomaly['severity']) => {
    switch (severity) {
      case 'HIGH':
        return 'bg-rose-500/10 text-rose-400 border-rose-500/30';
      case 'MEDIUM':
        return 'bg-amber-500/10 text-amber-400 border-amber-500/30';
      case 'LOW':
      default:
        return 'bg-slate-700/50 text-slate-300 border-slate-600';
    }
  };

  const getCategoryIcon = (category: ReductionOpportunity['category']) => {
    switch (category) {
      case 'RENEWABLE_PROCUREMENT':
        return <Zap className="w-3.5 h-3.5 text-emerald-400" />;
      case 'ENERGY_EFFICIENCY':
        return <Building className="w-3.5 h-3.5 text-sky-400" />;
      case 'FLEET_ELECTRIFICATION':
        return <Truck className="w-3.5 h-3.5 text-amber-400" />;
      case 'PROCESS_OPTIMIZATION':
      case 'SUPPLY_CHAIN':
      default:
        return <Leaf className="w-3.5 h-3.5 text-teal-400" />;
    }
  };

  const totalEstimatedReduction = insights?.reductionOpportunities?.reduce(
    (acc, item) => acc + (item.estimatedReductionTonnes || 0),
    0
  ) || 0;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 sm:p-6 shadow-sm">
      {/* Section Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-5 border-b border-slate-800">
        <div className="flex items-start gap-3">
          <div className="w-9 h-9 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400 shrink-0 mt-0.5">
            <Sparkles className="w-4 h-4" />
          </div>
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h2 className="text-base font-bold text-white tracking-tight">
                Trend Insights & Anomaly Intelligence
              </h2>
              <span className="inline-flex items-center gap-1 px-2 py-0.5 text-[11px] font-medium bg-emerald-950/70 border border-emerald-600/40 text-emerald-400 rounded-full">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
                Deterministic Engine
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-1">
              Automated corporate greenhouse gas trajectory analysis, pattern recognition, and reduction modeling
            </p>
          </div>
        </div>

        {/* Action Controls */}
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Tabs pattern: single-select with roving tabindex, so arrow keys
              move between tabs and the active tab is announced via
              aria-selected rather than by its background colour alone. */}
          <div
            role="tablist"
            aria-label="Trend analysis views"
            className="flex flex-wrap items-center bg-slate-950 p-1 rounded-lg border border-slate-800 text-xs"
          >
            <button
              type="button"
              role="tab"
              id="insights-tab-all"
              aria-selected={activeTab === 'ALL'}
              aria-controls="insights-panel"
              tabIndex={activeTab === 'ALL' ? 0 : -1}
              onClick={() => setActiveTab('ALL')}
              onKeyDown={(e) => handleTabKeyDown(e, 'ALL')}
              className={`px-3 py-1.5 rounded-md font-medium transition ${
                activeTab === 'ALL'
                  ? 'bg-emerald-600 text-white shadow-sm'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              All Analysis
            </button>
            <button
              type="button"
              role="tab"
              id="insights-tab-anomalies"
              aria-selected={activeTab === 'ANOMALIES'}
              aria-controls="insights-panel"
              tabIndex={activeTab === 'ANOMALIES' ? 0 : -1}
              onClick={() => setActiveTab('ANOMALIES')}
              onKeyDown={(e) => handleTabKeyDown(e, 'ANOMALIES')}
              className={`px-3 py-1.5 rounded-md font-medium transition flex items-center gap-1.5 ${
                activeTab === 'ANOMALIES'
                  ? 'bg-emerald-600 text-white shadow-sm'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              <span>Anomalies</span>
              {insights?.anomalies && (
                <span className="px-1.5 py-0.2 rounded-full text-[10px] bg-slate-800 text-slate-200">
                  {insights.anomalies.length}
                </span>
              )}
            </button>
            <button
              type="button"
              role="tab"
              id="insights-tab-opportunities"
              aria-selected={activeTab === 'OPPORTUNITIES'}
              aria-controls="insights-panel"
              tabIndex={activeTab === 'OPPORTUNITIES' ? 0 : -1}
              onClick={() => setActiveTab('OPPORTUNITIES')}
              onKeyDown={(e) => handleTabKeyDown(e, 'OPPORTUNITIES')}
              className={`px-3 py-1.5 rounded-md font-medium transition flex items-center gap-1.5 ${
                activeTab === 'OPPORTUNITIES'
                  ? 'bg-emerald-600 text-white shadow-sm'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              <span>Reduction Opportunities</span>
              {insights?.reductionOpportunities && (
                <span className="px-1.5 py-0.2 rounded-full text-[10px] bg-emerald-900/60 text-emerald-300">
                  {insights.reductionOpportunities.length}
                </span>
              )}
            </button>
          </div>

          <button
            type="button"
            onClick={() => fetchInsights(true)}
            disabled={loading || refreshing}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-300 bg-slate-800 hover:bg-slate-700 hover:text-white border border-slate-700 rounded-lg transition disabled:opacity-50 disabled:cursor-not-allowed"
            title="Re-run trend analysis"
            aria-label="Re-run trend analysis"
            aria-busy={refreshing || undefined}
          >
            <RefreshCw
              className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin text-emerald-400' : ''}`}
              aria-hidden="true"
            />
            <span className="hidden md:inline">{refreshing ? 'Analyzing...' : 'Refresh'}</span>
          </button>
        </div>
      </div>

      {/* Loading Skeleton */}
      {loading && !insights && (
        <div className="py-10 flex flex-col items-center justify-center text-center" role="status">
          <div className="w-12 h-12 rounded-full bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400 mb-3 animate-pulse">
            <Sparkles className="w-6 h-6" />
          </div>
          <div className="text-sm font-semibold text-white">Analyzing emissions trajectory…</div>
          <div className="text-xs text-slate-400 max-w-sm mt-1">
            Evaluating Scope 1 stationary fuel profiles, Scope 2 contractual market instruments, and seasonal variances
          </div>
        </div>
      )}

      {/* Error Banner */}
      {error && !loading && (
        <div
          role="alert"
          className="mt-4 p-4 rounded-lg bg-rose-500/10 border border-rose-500/30 flex items-start gap-3"
        >
          <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" aria-hidden="true" />
          <div>
            <div className="text-xs font-semibold text-rose-300">Analysis Incomplete</div>
            <div className="text-xs text-rose-300/90 mt-0.5">{error}</div>
            <button
              type="button"
              onClick={() => fetchInsights(true)}
              className="mt-2 text-xs text-rose-200 underline font-medium hover:text-white rounded"
            >
              Re-run trend analysis
            </button>
          </div>
        </div>
      )}

      {/* Content */}
      {insights && (
        <div
          id="insights-panel"
          role="tabpanel"
          aria-labelledby={TAB_IDS[activeTab]}
          tabIndex={0}
          className="space-y-6 mt-5"
        >
          {/* Executive Summary Card */}
          <div className="p-4 rounded-xl bg-slate-950/70 border border-slate-800 relative overflow-hidden">
            <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3 pb-3 border-b border-slate-800/80">
              <div className="flex items-center gap-2.5">
                <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                  Trajectory Synthesis:
                </span>
                <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
                  {insights.summary.overallTrajectory === 'DECLINING' && <TrendingDown className="w-3.5 h-3.5" />}
                  {insights.summary.overallTrajectory === 'INCREASING' && <TrendingUp className="w-3.5 h-3.5" />}
                  {insights.summary.overallTrajectory === 'PLATEAUING' && <Minus className="w-3.5 h-3.5" />}
                  {insights.summary.overallTrajectory}
                </span>
                <span className="text-xs text-slate-400">
                  • {insights.summary.periodRange}
                </span>
              </div>

              <div className="flex items-center gap-3 text-xs text-slate-400">
                <div className="flex items-center gap-1.5">
                  <Gauge className="w-3.5 h-3.5 text-emerald-400" />
                  <span>Audit Confidence: <strong className="text-white">{insights.summary.confidenceScore}%</strong></span>
                </div>
                {totalEstimatedReduction > 0 && (
                  <div className="flex items-center gap-1.5 pl-3 border-l border-slate-800">
                    <Target className="w-3.5 h-3.5 text-sky-400" />
                    <span>Identified Opportunity: <strong className="text-emerald-400">{totalEstimatedReduction.toFixed(1)} tCO₂e/yr</strong></span>
                  </div>
                )}
              </div>
            </div>

            <div className="mt-3">
              <h3 className="text-sm font-semibold text-white">
                {insights.summary.headline}
              </h3>
              <ul className="mt-2.5 space-y-1.5">
                {insights.summary.keyObservations.map((obs, idx) => (
                  <li key={idx} className="flex items-start gap-2 text-xs text-slate-300">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 shrink-0 mt-1.5"></span>
                    <span>{obs}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>

          {/* Grid of Anomalies & Opportunities */}
          {(activeTab === 'ALL' || activeTab === 'ANOMALIES') && (
            <div>
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <AlertTriangle className="w-4 h-4 text-amber-400" />
                  <h3 className="text-sm font-bold text-white tracking-tight">
                    Detected Anomalies & Variance Signals ({insights.anomalies.length})
                  </h3>
                </div>
                <span className="text-[11px] text-slate-400">
                  Deviations from seasonal thermal and production baselines
                </span>
              </div>

              <div className="grid grid-cols-1 xl:grid-cols-3 gap-3.5">
                {insights.anomalies.map((anomaly) => (
                  <div
                    key={anomaly.id}
                    className="p-4 rounded-xl bg-slate-950/50 border border-slate-800 hover:border-slate-700 transition flex flex-col justify-between"
                  >
                    <div>
                      <div className="flex items-center justify-between gap-2 mb-2">
                        <span className={`px-2 py-0.5 rounded text-[10px] font-bold border uppercase tracking-wider ${getAnomalyBadgeColor(anomaly.type)}`}>
                          {anomaly.type}
                        </span>
                        <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold border ${getSeverityBadge(anomaly.severity)}`}>
                          {anomaly.severity} SEVERITY
                        </span>
                      </div>

                      <h4 className="text-xs font-bold text-white leading-snug">
                        {anomaly.title}
                      </h4>

                      <p className="text-xs text-slate-400 mt-2 leading-relaxed">
                        {anomaly.description}
                      </p>
                    </div>

                    <div className="mt-3.5 pt-3 border-t border-slate-800/80 flex items-center justify-between text-[11px]">
                      <div>
                        <span className="text-slate-400 block text-[10px] uppercase font-semibold">Scope & Period</span>
                        <span className="text-slate-300 font-medium">{anomaly.scope} • {anomaly.affectedPeriod}</span>
                      </div>
                      <div className="text-right">
                        <span className="text-slate-400 block text-[10px] uppercase font-semibold">Impact</span>
                        <span className="text-amber-400 font-bold">{anomaly.metricImpact}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Reduction Opportunities Section */}
          {(activeTab === 'ALL' || activeTab === 'OPPORTUNITIES') && (
            <div>
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <Lightbulb className="w-4 h-4 text-emerald-400" />
                  <h3 className="text-sm font-bold text-white tracking-tight">
                    Prioritized Reduction Opportunities & Decarbonization Actions ({insights.reductionOpportunities.length})
                  </h3>
                </div>
                {onNavigateToTargets && (
                  <button
                    type="button"
                    onClick={onNavigateToTargets}
                    className="text-xs text-emerald-400 hover:text-emerald-300 font-medium flex items-center gap-1 transition"
                  >
                    <span>View Reduction Targets</span>
                    <ArrowRight className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>

              <div className="grid grid-cols-1 lg:grid-cols-2 gap-3.5">
                {insights.reductionOpportunities.map((opp) => (
                  <div
                    key={opp.id}
                    className="p-4 rounded-xl bg-slate-950/50 border border-slate-800 hover:border-slate-700 transition flex flex-col justify-between"
                  >
                    <div>
                      <div className="flex items-center justify-between gap-2 mb-2">
                        <div className="flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] font-semibold bg-slate-900 border border-slate-700 text-slate-300">
                          {getCategoryIcon(opp.category)}
                          <span>{opp.category.replace('_', ' ')}</span>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <span className={`px-2 py-0.5 rounded text-[10px] font-bold border ${
                            opp.priority === 'HIGH'
                              ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                              : 'bg-sky-500/10 text-sky-400 border-sky-500/30'
                          }`}>
                            {opp.priority} PRIORITY
                          </span>
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-800 text-slate-300 border border-slate-700">
                            {opp.feasibility} FEASIBILITY
                          </span>
                        </div>
                      </div>

                      <h4 className="text-xs font-bold text-white mt-1">
                        {opp.title}
                      </h4>

                      <p className="text-xs text-slate-400 mt-2 leading-relaxed">
                        {opp.description}
                      </p>

                      <div className="mt-3 p-2.5 rounded-lg bg-slate-900/80 border border-slate-800/80 text-[11px] text-slate-400">
                        <span className="font-semibold text-slate-300">GHG Standard Guidance: </span>
                        {opp.ghgProtocolGuidance}
                      </div>
                    </div>

                    <div className="mt-3.5 pt-3 border-t border-slate-800/80 flex items-center justify-between text-xs">
                      <div>
                        <span className="text-[10px] uppercase font-semibold text-slate-400 block">Est. Reduction</span>
                        <span className="text-sm font-extrabold text-emerald-400">
                          -{opp.estimatedReductionTonnes.toFixed(1)} <span className="text-xs font-normal text-slate-400">tCO₂e / yr</span>
                        </span>
                      </div>
                      <div className="text-right">
                        <span className="text-[10px] uppercase font-semibold text-slate-400 block">Payback Horizon</span>
                        <span className="font-semibold text-white flex items-center justify-end gap-1">
                          <Clock className="w-3 h-3 text-slate-400" />
                          <span>{opp.paybackPeriod}</span>
                        </span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Model Attribution Footnote */}
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 text-[11px] text-slate-400 pt-2 border-t border-slate-800/60">
            <span className="flex items-center gap-1.5">
              <CheckCircle2 className="w-3.5 h-3.5 shrink-0 text-emerald-400" aria-hidden="true" />
              <span>Aligned with GHG Protocol Scope 1 &amp; Scope 2 Guidance (Dual-Reporting Standard)</span>
            </span>
            <span className="shrink-0">Engine: {insights.modelUsed}</span>
          </div>
        </div>
      )}
    </div>
  );
};
