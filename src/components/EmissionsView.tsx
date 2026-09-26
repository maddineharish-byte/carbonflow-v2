/**
 * CarbonFlow — Emissions Ledger & Deterministic Calculation Lineage
 */
import React, { useState } from 'react';
import { Calculator, Hash, ShieldCheck, Eye, Download, Info } from 'lucide-react';
import { ActivityDataItem, Calculation } from '../types.ts';
import { api } from '../services/api.ts';

interface EmissionsViewProps {
  activities: ActivityDataItem[];
  onRefresh: () => void;
}

export const EmissionsView: React.FC<EmissionsViewProps> = ({ activities }) => {
  const [selectedCalc, setSelectedCalc] = useState<{ calc: Calculation; act: ActivityDataItem } | null>(null);
  const [isExporting, setIsExporting] = useState(false);

  const handleExport = async () => {
    try {
      setIsExporting(true);
      await api.exportEmissionReportCsv();
    } catch (err) {
      console.error('Failed to export CSV:', err);
    } finally {
      setIsExporting(false);
    }
  };

  const calculatedItems = activities.filter((a) => a.calculation);

  const totalScope1 = calculatedItems
    .filter((a) => a.scope === 'SCOPE_1')
    .reduce((acc, a) => acc + (a.calculation?.totalCo2eTonnes || 0), 0);

  const totalScope2Loc = calculatedItems
    .filter((a) => a.scope === 'SCOPE_2' && a.category === 'ELECTRICITY_LOCATION')
    .reduce((acc, a) => acc + (a.calculation?.totalCo2eTonnes || 0), 0);

  const totalScope2Mkt = calculatedItems
    .filter((a) => a.scope === 'SCOPE_2' && a.category === 'ELECTRICITY_MARKET')
    .reduce((acc, a) => acc + (a.calculation?.totalCo2eTonnes || 0), 0);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Emissions Ledger & Calculation Lineage</h1>
          <p className="text-xs text-slate-400 mt-1">
            Deterministic decimal calculation audit trace with immutable cryptographic hashes (SHA-256).
          </p>
        </div>

        <button
          onClick={handleExport}
          disabled={isExporting}
          className="flex items-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition self-start disabled:opacity-50"
        >
          <Download className={`w-4 h-4 text-emerald-400 ${isExporting ? 'animate-bounce' : ''}`} />
          {isExporting ? 'Exporting...' : 'Export Ledger CSV'}
        </button>
      </div>

      {/* Dual Reporting Ledger Summary */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-sm">
          <div className="text-xs font-semibold uppercase text-slate-400">Total Scope 1 (Direct)</div>
          <div className="text-2xl font-bold text-white mt-1">
            {totalScope1.toFixed(4)} <span className="text-xs font-normal text-slate-400">tCO₂e</span>
          </div>
          <div className="text-[11px] text-slate-500 mt-1">Stationary + Mobile + Fugitive</div>
        </div>

        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-sm">
          <div className="text-xs font-semibold uppercase text-slate-400">Total Scope 2 (Location-Based)</div>
          <div className="text-2xl font-bold text-sky-400 mt-1">
            {totalScope2Loc.toFixed(4)} <span className="text-xs font-normal text-slate-400">tCO₂e</span>
          </div>
          <div className="text-[11px] text-slate-500 mt-1">Grid average emissions</div>
        </div>

        <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 shadow-sm">
          <div className="text-xs font-semibold uppercase text-slate-400">Total Scope 2 (Market-Based)</div>
          <div className="text-2xl font-bold text-emerald-400 mt-1">
            {totalScope2Mkt.toFixed(4)} <span className="text-xs font-normal text-slate-400">tCO₂e</span>
          </div>
          <div className="text-[11px] text-slate-500 mt-1">Contractual green instruments (PPA/RECs)</div>
        </div>
      </div>

      {/* Ledger Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Calculator className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">Audited Emissions Ledger ({calculatedItems.length})</h2>
          </div>
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            <span>Cryptographically Verified</span>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs text-slate-300">
            <thead className="bg-slate-850 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th className="px-4 py-3">Facility</th>
                <th className="px-4 py-3">Scope & Source</th>
                <th className="px-4 py-3 text-right">Raw Activity</th>
                <th className="px-4 py-3">Factor Applied</th>
                <th className="px-4 py-3">GWP Standard</th>
                <th className="px-4 py-3 text-right">Calculated tCO₂e</th>
                <th className="px-4 py-3">Audit Hash</th>
                <th className="px-4 py-3 text-right">Trace</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {calculatedItems.map((act) => {
                const calc = act.calculation!;
                return (
                  <tr key={act.id} className="hover:bg-slate-850/50 transition">
                    <td className="px-4 py-3 font-semibold text-white">{act.facilityName}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-bold mr-1.5 ${
                          act.scope === 'SCOPE_1'
                            ? 'bg-orange-950 text-orange-300 border border-orange-800'
                            : 'bg-sky-950 text-sky-300 border border-sky-800'
                        }`}
                      >
                        {act.scope}
                      </span>
                      <span className="text-slate-400">{act.activityType}</span>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-slate-200">
                      {act.quantity.toLocaleString()} {act.unit}
                    </td>
                    <td className="px-4 py-3 text-slate-400 text-[11px]">
                      {calc.factorSource} (v{calc.factorVersion})
                    </td>
                    <td className="px-4 py-3 text-slate-400 text-[11px]">{calc.gwpName}</td>
                    <td className="px-4 py-3 text-right font-mono font-bold text-emerald-400">
                      {calc.totalCo2eTonnes.toFixed(4)}
                    </td>
                    <td className="px-4 py-3 font-mono text-[10px] text-slate-500">
                      {calc.calculationHash.slice(0, 10)}...
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => setSelectedCalc({ calc, act })}
                        className="flex items-center gap-1 ml-auto px-2 py-1 bg-slate-800 hover:bg-slate-700 text-sky-400 rounded text-[11px] font-medium transition"
                      >
                        <Eye className="w-3.5 h-3.5" />
                        Lineage
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {/* Calculation Lineage Modal */}
      {selectedCalc && (
        <div className="fixed inset-0 bg-black/75 backdrop-blur-xs flex items-center justify-center p-4 z-50">
          <div className="bg-slate-900 border border-slate-800 rounded-xl max-w-2xl w-full p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div className="flex items-center gap-2">
                <Hash className="w-4 h-4 text-emerald-400" />
                <h3 className="text-sm font-bold text-white">Calculation Audit Lineage Snapshot</h3>
              </div>
              <button onClick={() => setSelectedCalc(null)} className="text-slate-400 hover:text-white">
                ✕
              </button>
            </div>

            <div className="space-y-4 text-xs">
              {/* Hash Banner */}
              <div className="p-3 bg-slate-800/80 rounded-lg border border-slate-700 space-y-1">
                <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
                  Immutable Cryptographic SHA-256 Hash
                </div>
                <div className="font-mono text-[11px] text-emerald-400 break-all">
                  {selectedCalc.calc.calculationHash}
                </div>
              </div>

              {/* Mathematical Equation Trace */}
              <div className="grid grid-cols-2 gap-4">
                <div className="p-3 bg-slate-850 rounded-lg border border-slate-800 space-y-1">
                  <div className="text-slate-400 font-medium">Activity Input</div>
                  <div className="font-mono text-white text-sm">
                    {selectedCalc.act.quantity.toLocaleString()} {selectedCalc.act.unit}
                  </div>
                  <div className="text-[11px] text-slate-500">Source: {selectedCalc.act.source}</div>
                </div>

                <div className="p-3 bg-slate-850 rounded-lg border border-slate-800 space-y-1">
                  <div className="text-slate-400 font-medium">Factor Reference</div>
                  <div className="font-mono text-white text-sm">{selectedCalc.calc.factorSource}</div>
                  <div className="text-[11px] text-slate-500">
                    Version {selectedCalc.calc.factorVersion} | Unit: {selectedCalc.calc.factorUnit}
                  </div>
                </div>
              </div>

              {/* GHG Breakdown by Gas */}
              <div className="space-y-2">
                <div className="font-semibold text-white">Individual Gas Breakdown & GWP Multipliers</div>
                <table className="w-full text-left bg-slate-850 rounded-lg overflow-hidden">
                  <thead className="bg-slate-800 text-slate-400 text-[10px] uppercase">
                    <tr>
                      <th className="px-3 py-2">Gas</th>
                      <th className="px-3 py-2 text-right">Raw Emission (kg)</th>
                      <th className="px-3 py-2 text-right">GWP Applied</th>
                      <th className="px-3 py-2 text-right">CO₂e (kg)</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800 text-[11px]">
                    {selectedCalc.calc.gasResults.map((gas, i) => (
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

              {/* Net Output */}
              <div className="p-3 bg-emerald-950/40 border border-emerald-800/60 rounded-lg flex items-center justify-between">
                <div>
                  <div className="text-xs font-bold text-emerald-300">Total Net Calculated Emissions</div>
                  <div className="text-[11px] text-emerald-400/80">
                    Calculated at: {new Date(selectedCalc.calc.calculatedAt).toLocaleString()}
                  </div>
                </div>
                <div className="text-right">
                  <div className="text-xl font-extrabold text-white font-mono">
                    {selectedCalc.calc.totalCo2eTonnes.toFixed(4)}{' '}
                    <span className="text-xs font-normal text-slate-300">tCO₂e</span>
                  </div>
                  <div className="text-[11px] font-mono text-slate-400">
                    ({selectedCalc.calc.totalCo2eKg.toLocaleString()} kgCO₂e)
                  </div>
                </div>
              </div>
            </div>

            <div className="pt-3 border-t border-slate-800 flex justify-end">
              <button
                onClick={() => setSelectedCalc(null)}
                className="px-4 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg transition"
              >
                Close Snapshot
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
