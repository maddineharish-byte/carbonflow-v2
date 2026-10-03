/**
 * CarbonFlow — Emission Factors & Global Warming Potential (GWP) Reference
 */
import React from 'react';
import { Layers, Globe2, BookOpen, ShieldCheck } from 'lucide-react';
import { EmissionFactor, GwpSet } from '../types.ts';

interface FactorsViewProps {
  gwpSets: GwpSet[];
  emissionFactors: EmissionFactor[];
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

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {gwpSets.length === 0 ? (
            <div className="lg:col-span-3 bg-slate-900 border border-slate-800 rounded-xl p-8 text-center text-xs text-slate-500">
              No GWP reference sets available.
            </div>
          ) : (
            gwpSets.map((set) => (
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
                {set.values.length === 0 ? (
                  <div className="text-slate-500">No GWP values published for this set.</div>
                ) : (
                  set.values.map((value) => (
                    <div key={value.gas} className="flex justify-between text-slate-300">
                      <span>{value.gas}</span>
                      <span className="font-bold text-emerald-400">
                        {Number(value.gwp100yr).toLocaleString()}
                      </span>
                    </div>
                  ))
                )}
              </div>
            </div>
          )))}
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

        <div
          className="overflow-x-auto"
          tabIndex={0}
          role="group"
          aria-label="Emission factor directory, scrollable"
        >
          <table className="w-full min-w-[60rem] text-left text-xs text-slate-300">
            <caption className="sr-only">
              Canonical emission factor versions with input units, source year, geography and active status.
            </caption>
            <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th scope="col" className="px-4 py-3">Scope</th>
                <th scope="col" className="px-4 py-3">Activity Type</th>
                <th scope="col" className="px-4 py-3">Fuel / Emission Source</th>
                <th scope="col" className="px-4 py-3">Input Unit</th>
                <th scope="col" className="px-4 py-3">Factor Value</th>
                <th scope="col" className="px-4 py-3">Source &amp; Year</th>
                <th scope="col" className="px-4 py-3">Geography</th>
                <th scope="col" className="px-4 py-3">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {emissionFactors.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12 text-center">
                    <Layers className="w-8 h-8 text-slate-600 mx-auto mb-3" />
                    <div className="text-sm font-semibold text-slate-300">No emission factors published</div>
                    <div className="text-xs text-slate-500 mt-1">
                      The factor library is managed by platform administrators.
                    </div>
                  </td>
                </tr>
              ) : (
                emissionFactors.map((f) => {
                const activeVersion = f.versions?.find((v: any) => v.status === 'ACTIVE') || f.versions?.[0];
                return (
                  <tr key={f.id} className="hover:bg-slate-800/50 transition">
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
              })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
