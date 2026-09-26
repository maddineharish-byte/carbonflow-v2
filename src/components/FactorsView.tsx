/**
 * CarbonFlow — Emission Factors & Global Warming Potential (GWP) Reference
 */
import React from 'react';
import { Layers, Globe2, BookOpen, ShieldCheck } from 'lucide-react';

interface FactorsViewProps {
  gwpSets: any[];
  emissionFactors: any[];
}

export const FactorsView: React.FC<FactorsViewProps> = ({ gwpSets, emissionFactors }) => {
  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-white tracking-tight">Emission Factors & GWP Reference Catalogs</h1>
        <p className="text-xs text-slate-400 mt-1">
          Standardized scientific factor sets and IPCC Assessment Reports with immutable version control.
        </p>
      </div>

      {/* GWP Sets Grid */}
      <div className="space-y-3">
        <div className="flex items-center gap-2">
          <Globe2 className="w-4 h-4 text-emerald-400" />
          <h2 className="text-sm font-bold text-white">IPCC Global Warming Potential (GWP) Reference Sets</h2>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {gwpSets.map((set) => (
            <div
              key={set.id}
              className={`bg-slate-900 rounded-xl p-5 border ${
                set.isDefault ? 'border-emerald-500/50 shadow-emerald-950/20 shadow-lg' : 'border-slate-800'
              }`}
            >
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-bold text-white">{set.code}</span>
                {set.isDefault && (
                  <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-emerald-950 text-emerald-300 border border-emerald-800">
                    DEFAULT ACTIVE
                  </span>
                )}
              </div>
              <div className="text-xs font-semibold text-slate-300 mt-1">{set.name}</div>
              <div className="text-[11px] text-slate-500 mt-0.5">Published: {set.publicationYear}</div>

              <div className="mt-4 pt-3 border-t border-slate-800 space-y-1.5 text-xs font-mono">
                <div className="text-[10px] uppercase font-bold text-slate-500">100-Year Horizon Multipliers:</div>
                <div className="flex justify-between text-slate-300">
                  <span>Carbon Dioxide (CO₂)</span>
                  <span className="font-bold text-white">1.0</span>
                </div>
                <div className="flex justify-between text-slate-300">
                  <span>Methane (CH₄)</span>
                  <span className="font-bold text-emerald-400">
                    {set.code === 'IPCC_AR6' ? '27.9' : set.code === 'IPCC_AR5' ? '28.0' : '25.0'}
                  </span>
                </div>
                <div className="flex justify-between text-slate-300">
                  <span>Nitrous Oxide (N₂O)</span>
                  <span className="font-bold text-sky-400">
                    {set.code === 'IPCC_AR6' ? '273.0' : set.code === 'IPCC_AR5' ? '265.0' : '298.0'}
                  </span>
                </div>
                <div className="flex justify-between text-slate-300">
                  <span>Sulfur Hexafluoride (SF₆)</span>
                  <span className="font-bold text-amber-400">25,200.0</span>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Emission Factors Catalog */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Layers className="w-4 h-4 text-sky-400" />
            <h2 className="text-sm font-bold text-white">Canonical Emission Factor Versions Directory</h2>
          </div>
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            <span>Regulatory Authority Sourced</span>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs text-slate-300">
            <thead className="bg-slate-850 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th className="px-4 py-3">Scope</th>
                <th className="px-4 py-3">Activity Type</th>
                <th className="px-4 py-3">Fuel / Emission Source</th>
                <th className="px-4 py-3">Input Unit</th>
                <th className="px-4 py-3">Factor Value</th>
                <th className="px-4 py-3">Source & Year</th>
                <th className="px-4 py-3">Geography</th>
                <th className="px-4 py-3">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {emissionFactors.map((f) => {
                const activeVersion = f.versions?.find((v: any) => v.status === 'ACTIVE') || f.versions?.[0];
                return (
                  <tr key={f.id} className="hover:bg-slate-850/50 transition">
                    <td className="px-4 py-3">
                      <span
                        className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-bold ${
                          f.scope === 'SCOPE_1'
                            ? 'bg-orange-950 text-orange-300 border border-orange-800'
                            : 'bg-sky-950 text-sky-300 border border-sky-800'
                        }`}
                      >
                        {f.scope}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-mono font-medium text-white">{f.activityType}</td>
                    <td className="px-4 py-3 text-slate-300">{f.fuelOrActivity}</td>
                    <td className="px-4 py-3 font-mono text-slate-400">{f.inputUnit}</td>
                    <td className="px-4 py-3 font-mono font-bold text-emerald-400">
                      {activeVersion ? `${activeVersion.co2eFactor} ${activeVersion.factorUnit}` : '—'}
                    </td>
                    <td className="px-4 py-3 text-slate-300 text-[11px]">
                      {activeVersion ? `${activeVersion.source} (${activeVersion.sourceYear})` : '—'}
                    </td>
                    <td className="px-4 py-3 text-slate-400 text-[11px]">{activeVersion?.geography || 'GLOBAL'}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-emerald-950 text-emerald-300 border border-emerald-800">
                        {activeVersion?.status || 'ACTIVE'}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
