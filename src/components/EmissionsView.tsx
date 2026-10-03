/**
 * CarbonFlow — Emissions Ledger & Calculation Lineage
 * Phase 8: consumes the authoritative Java ledger (`GET /emissions` →
 * `{records, summary}`) — totals, Scope 2 classification and provenance all
 * come from the backend; nothing is recalculated in the browser.
 */
import React, { useState, useEffect, useCallback } from 'react';
import { Calculator, Hash, ShieldCheck, Eye, Download, RefreshCw, AlertTriangle } from 'lucide-react';
import { EmissionRecord, Facility, Calculation, ReportingPeriod } from '../types.ts';
import { formatQuantity, formatAuditInstant } from '../services/format.ts';
import { Modal } from './Modal.tsx';
import { api } from '../services/api.ts';
import { hasPermission } from '../services/permissions.ts';

interface EmissionsViewProps {
  permissions: string[];
  periods: ReportingPeriod[];
}

interface EmissionsResult {
  records: EmissionRecord[];
  summary: {
    scope1Tonnes: number;
    scope2LocationTonnes: number;
    scope2MarketTonnes: number;
    totalLocationBasedTonnes: number;
    totalMarketBasedTonnes: number;
  };
}

export const EmissionsView: React.FC<EmissionsViewProps> = ({ permissions, periods }) => {
  const [result, setResult] = useState<EmissionsResult | null>(null);
  const [facilities, setFacilities] = useState<Facility[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isExporting, setIsExporting] = useState(false);
  const [selectedCalc, setSelectedCalc] = useState<Calculation | null>(null);
  const [calcLoading, setCalcLoading] = useState(false);
  const [periodFilter, setPeriodFilter] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [emissions, facilityList] = await Promise.all([
        api.getEmissions(periodFilter || undefined),
        api.getFacilities().catch(() => []),
      ]);
      setResult(emissions);
      setFacilities(facilityList);
    } catch (err: any) {
      setError(err?.message || 'Failed to load the emission ledger.');
    } finally {
      setLoading(false);
    }
    // periodFilter is read inside load, so it must be a dependency. Without it
    // the memoised loader never refetches and the period filter silently does
    // nothing until the view is remounted.
  }, [periodFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleExport = async () => {
    try {
      setIsExporting(true);
      await api.exportEmissionReportCsv(periodFilter ? { periodId: periodFilter } : undefined);
    } catch (err) {
      console.error('Failed to export CSV:', err);
    } finally {
      setIsExporting(false);
    }
  };

  const handleTrace = async (calculationId: string) => {
    setCalcLoading(true);
    try {
      const calc = await api.getCalculation(calculationId);
      setSelectedCalc(calc);
    } catch (err: any) {
      setError(err?.message || 'Failed to load the calculation snapshot.');
    } finally {
      setCalcLoading(false);
    }
  };

  const facilityName = (id: string) =>
    facilities.find((f) => f.id === id)?.name || 'Unknown facility';

  if (loading) {
    return (
      <div className="p-8 text-slate-400" role="status">
        Loading emissions ledger…
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-6">
        <div
          role="alert"
          className="p-4 rounded-lg bg-rose-500/10 border border-rose-500/30 flex items-center gap-3"
        >
          <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0" aria-hidden="true" />
          <span className="text-xs text-rose-200">{error}</span>
        </div>
        <button
          onClick={() => void load()}
          className="flex items-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition"
        >
          <RefreshCw className="w-4 h-4" aria-hidden="true" /> Retry
        </button>
      </div>
    );
  }

  const summary = result?.summary;
  const records = result?.records ?? [];
  const canExport = hasPermission(permissions, 'reports.read');

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Emissions Ledger & Calculation Lineage</h1>
          <p className="text-xs text-slate-400 mt-1">
            Deterministic decimal calculation audit trace with SHA-256 integrity checksums.
          </p>
        </div>
        {canExport && (
          <div className="flex items-center gap-2">
            <select
              value={periodFilter}
              onChange={(e) => setPeriodFilter(e.target.value)}
              className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-2 text-xs text-white focus:outline-none"
              aria-label="Filter ledger by reporting period"
            >
              <option value="">All periods</option>
              {periods.map((p) => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
            <button
              onClick={handleExport}
              disabled={isExporting}
              className="flex items-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition self-start disabled:opacity-50"
            >
              <Download className={`w-4 h-4 text-emerald-400 ${isExporting ? 'animate-bounce' : ''}`} />
              {isExporting ? 'Exporting...' : 'Export Ledger CSV'}
            </button>
          </div>
        )}
      </div>

      {/* Dual Reporting Ledger Summary (backend totals) */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-sm">
          <div className="text-xs font-semibold uppercase text-slate-400">Total Scope 1 (Direct)</div>
          <div className="text-2xl font-bold text-white mt-1">
            {formatQuantity(summary?.scope1Tonnes, 4)} <span className="text-xs font-normal text-slate-400">tCO₂e</span>
          </div>
          <div className="text-[11px] text-slate-500 mt-1">Stationary + Mobile + Fugitive</div>
        </div>

        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-sm">
          <div className="text-xs font-semibold uppercase text-slate-400">Total Scope 2 (Location-Based)</div>
          <div className="text-2xl font-bold text-sky-400 mt-1">
            {formatQuantity(summary?.scope2LocationTonnes, 4)} <span className="text-xs font-normal text-slate-400">tCO₂e</span>
          </div>
          <div className="text-[11px] text-slate-500 mt-1">Grid average emissions</div>
        </div>

        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-sm">
          <div className="text-xs font-semibold uppercase text-slate-400">Total Scope 2 (Market-Based)</div>
          <div className="text-2xl font-bold text-emerald-400 mt-1">
            {formatQuantity(summary?.scope2MarketTonnes, 4)} <span className="text-xs font-normal text-slate-400">tCO₂e</span>
          </div>
          <div className="text-[11px] text-slate-500 mt-1">Contractual green instruments (PPA/RECs)</div>
        </div>
      </div>

      {/* Ledger Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Calculator className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">Audited Emissions Ledger ({records.length})</h2>
          </div>
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            <span>SHA-256 Integrity Checksums</span>
          </div>
        </div>

        <div
          className="overflow-x-auto"
          tabIndex={0}
          role="group"
          aria-label="Audited emissions ledger, scrollable"
        >
          <table className="w-full min-w-[48rem] text-left text-xs text-slate-300">
            <caption className="sr-only">
              Audited emissions ledger. Scope 1 and Scope 2 rows are shown separately and are never aggregated.
            </caption>
            <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th scope="col" className="px-4 py-3">Facility</th>
                <th scope="col" className="px-4 py-3">Scope &amp; Source</th>
                <th scope="col" className="px-4 py-3">Scope 2 Method</th>
                <th scope="col" className="px-4 py-3 text-right">Calculated tCO₂e</th>
                <th scope="col" className="px-4 py-3">Audit Hash</th>
                <th scope="col" className="px-4 py-3 text-right">Trace</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {records.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-12 text-center">
                    <Calculator className="w-8 h-8 text-slate-600 mx-auto mb-3" />
                    <div className="text-sm font-semibold text-slate-300">No emission records yet</div>
                    <div className="text-xs text-slate-500 mt-1">
                      Run a calculation on activity data to populate the ledger.
                    </div>
                  </td>
                </tr>
              ) : (
                records.map((record) => (
                  <tr key={record.id} className="hover:bg-slate-800/50 transition">
                    <td className="px-4 py-3 font-semibold text-white">{facilityName(record.facilityId)}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-bold mr-1.5 ${
                          record.scope === 'SCOPE_1'
                            ? 'bg-orange-950 text-orange-300 border border-orange-800'
                            : 'bg-sky-950 text-sky-300 border border-sky-800'
                        }`}
                      >
                        {record.scope}
                      </span>
                      <span className="text-slate-400">{record.category}</span>
                    </td>
                    <td className="px-4 py-3 text-slate-400 text-[11px]">
                      {record.scope2Type ?? '—'}
                    </td>
                    <td className="px-4 py-3 text-right font-mono font-bold text-emerald-400">
                      {formatQuantity(record.co2eTonnes, 4)}
                    </td>
                    <td className="px-4 py-3 font-mono text-[10px] text-slate-500">
                      #{record.calculationId.slice(0, 8)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => handleTrace(record.calculationId)}
                        aria-haspopup="dialog"
                        aria-label={`View calculation lineage for ${facilityName(record.facilityId)}, ${formatQuantity(record.co2eTonnes, 4)} tCO2e`}
                        className="flex items-center gap-1 ml-auto px-2 py-1 bg-slate-800 hover:bg-slate-700 text-sky-400 rounded text-[11px] font-medium transition"
                      >
                        <Eye className="w-3.5 h-3.5" aria-hidden="true" />
                        Lineage
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Calculation Lineage Modal (backend snapshot) */}
      {selectedCalc && (
        <Modal
          title="Calculation Audit Lineage Snapshot"
          description="Deterministic calculation trace with SHA-256 integrity checksum, as computed by the backend."
          onClose={() => setSelectedCalc(null)}
          closeLabel="Close calculation lineage snapshot"
          panelClassName="max-w-2xl"
          headerIcon={<Hash className="w-4 h-4 text-emerald-400 shrink-0" aria-hidden="true" />}
        >
          {calcLoading ? (
              <div className="py-8 text-center text-slate-400 text-sm" role="status">
                Loading calculation snapshot…
              </div>
            ) : (
              <div className="space-y-4 text-xs">
                {/* Hash Banner */}
                <div className="p-3 bg-slate-800/80 rounded-lg border border-slate-700 space-y-1">
                  <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
                    SHA-256 Integrity Checksum
                  </div>
                  <div className="font-mono text-[11px] text-emerald-400 break-all">
                    {selectedCalc.calculationHash}
                  </div>
                </div>

                {/* Mathematical Equation Trace */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="p-3 bg-slate-800 rounded-lg border border-slate-800 space-y-1">
                    <div className="text-slate-400 font-medium">Original Activity Input</div>
                    <div className="font-mono text-white text-sm">
                      {selectedCalc.originalQuantity != null
                        ? Number(selectedCalc.originalQuantity).toLocaleString()
                        : '—'}{' '}
                      {selectedCalc.originalUnit}
                    </div>
                    <div className="text-[11px] text-slate-500">
                      Normalized: {selectedCalc.normalizedQuantity != null
                        ? Number(selectedCalc.normalizedQuantity).toLocaleString()
                        : '—'}{' '}
                      {selectedCalc.normalizedUnit} (×{selectedCalc.conversionFactor ?? '—'})
                    </div>
                  </div>

                  <div className="p-3 bg-slate-800 rounded-lg border border-slate-800 space-y-1">
                    <div className="text-slate-400 font-medium">Factor Reference</div>
                    <div className="font-mono text-white text-sm">{selectedCalc.factorSource}</div>
                    <div className="text-[11px] text-slate-500">
                      Version {selectedCalc.factorVersion} | Unit: {selectedCalc.factorUnit}
                    </div>
                  </div>
                </div>

                {/* GHG Breakdown by Gas */}
                <div className="space-y-2">
                  <div className="font-semibold text-white">Individual Gas Breakdown & GWP Multipliers</div>
                  <div className="overflow-x-auto rounded-lg">
                    <table className="w-full min-w-[24rem] text-left bg-slate-900 rounded-lg overflow-hidden">
                    <caption className="sr-only">
                      Per-gas raw emissions, applied GWP multiplier and resulting CO2e for this calculation.
                    </caption>
                    <thead className="bg-slate-800 text-slate-400 text-[10px] uppercase">
                      <tr>
                        <th scope="col" className="px-3 py-2">Gas</th>
                        <th scope="col" className="px-3 py-2 text-right">Raw Emission (kg)</th>
                        <th scope="col" className="px-3 py-2 text-right">GWP Applied</th>
                        <th scope="col" className="px-3 py-2 text-right">CO₂e (kg)</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-800 text-[11px]">
                      {selectedCalc.gasResults.map((gas, i) => (
                        <tr key={i}>
                          <td className="px-3 py-2 font-mono font-medium text-white">{gas.gas}</td>
                          <td className="px-3 py-2 text-right font-mono text-slate-300">
                            {gas.rawGasEmissionKg.toLocaleString()}
                          </td>
                          <td className="px-3 py-2 text-right font-mono text-sky-400">{gas.gwpApplied}</td>
                          <td className="px-3 py-2 text-right font-mono font-bold text-emerald-400">
                            {gas.co2eKg.toLocaleString()}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  </div>
                </div>

                {/* Net Output */}
                <div className="p-3 bg-emerald-950/40 border border-emerald-800/60 rounded-lg flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <div className="text-xs font-bold text-emerald-300">Total Net Calculated Emissions</div>
                    <div className="text-[11px] text-emerald-400/80">
                      Calculated at: {formatAuditInstant(selectedCalc.calculatedAt)}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-xl font-extrabold text-white font-mono">
                      {formatQuantity(selectedCalc.totalCo2eTonnes, 4)}{' '}
                      <span className="text-xs font-normal text-slate-300">tCO₂e</span>
                    </div>
                    <div className="text-[11px] font-mono text-slate-400">
                      ({selectedCalc.totalCo2eKg.toLocaleString()} kgCO₂e)
                    </div>
                  </div>
                </div>
              </div>
            )}

            <div className="pt-3 border-t border-slate-800 flex justify-end">
              <button
                onClick={() => setSelectedCalc(null)}
                className="px-4 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg transition"
              >
                Close Snapshot
              </button>
            </div>
        </Modal>
      )}
    </div>
  );
};
