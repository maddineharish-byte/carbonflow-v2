/**
 * CarbonFlow — Carbon Targets & Decarbonization Projects
 */
import React from 'react';
import { Target, TrendingDown, CheckCircle, Calendar, Plus } from 'lucide-react';
import { TargetItem, ReductionProjectItem } from '../types.ts';

interface TargetsViewProps {
  targets: TargetItem[];
  projects: ReductionProjectItem[];
}

export const TargetsView: React.FC<TargetsViewProps> = ({ targets, projects }) => {
  const totalExpectedReductions = projects.reduce((acc, p) => acc + p.expectedReductionT, 0);
  const totalActualReductions = projects.reduce((acc, p) => acc + p.actualReductionT, 0);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-white tracking-tight">Reduction Targets & Decarbonization Projects</h1>
        <p className="text-xs text-slate-400 mt-1">
          Science-based emissions trajectory monitoring and capital energy efficiency project accounting.
        </p>
      </div>

      {/* Target Progress Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {targets.map((target) => (
          <div key={target.id} className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Target className="w-5 h-5 text-emerald-400" />
                <h2 className="text-sm font-bold text-white">{target.name}</h2>
              </div>
              <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-emerald-950 text-emerald-300 border border-emerald-800">
                {target.status}
              </span>
            </div>

            <div className="grid grid-cols-3 gap-3 text-center py-2 bg-slate-850 rounded-lg border border-slate-800 text-xs">
              <div>
                <div className="text-slate-400 text-[11px]">Baseline</div>
                <div className="text-white font-bold font-mono mt-0.5">{target.baselineValueT} t</div>
              </div>
              <div>
                <div className="text-slate-400 text-[11px]">Target Level</div>
                <div className="text-emerald-400 font-bold font-mono mt-0.5">{target.targetValueT} t</div>
              </div>
              <div>
                <div className="text-slate-400 text-[11px]">Reduction %</div>
                <div className="text-sky-400 font-bold font-mono mt-0.5">-{target.reductionPercentage}%</div>
              </div>
            </div>

            {/* Visual Progress Bar */}
            <div className="space-y-1.5">
              <div className="flex justify-between text-[11px] text-slate-400">
                <span>Decarbonization Trajectory</span>
                <span className="font-semibold text-white">45% Realized</span>
              </div>
              <div className="w-full bg-slate-850 rounded-full h-2 overflow-hidden border border-slate-700">
                <div className="bg-gradient-to-r from-emerald-500 to-sky-400 h-2 rounded-full w-[45%]" />
              </div>
            </div>

            {target.notes && <p className="text-[11px] text-slate-500">{target.notes}</p>}
          </div>
        ))}
      </div>

      {/* Decarbonization Projects Section */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div>
            <h2 className="text-sm font-bold text-white">Decarbonization Project Pipeline</h2>
            <div className="text-xs text-slate-400">
              Total expected reductions: {totalExpectedReductions.toFixed(1)} tCO₂e | Actual achieved:{' '}
              <span className="text-emerald-400 font-semibold">{totalActualReductions.toFixed(1)} tCO₂e</span>
            </div>
          </div>
        </div>

        <div className="divide-y divide-slate-800">
          {projects.map((p) => (
            <div key={p.id} className="p-4 hover:bg-slate-850/50 transition flex flex-col md:flex-row justify-between gap-4">
              <div className="space-y-1 text-xs">
                <div className="flex items-center gap-2">
                  <span className="font-bold text-white text-sm">{p.name}</span>
                  <span
                    className={`text-[10px] font-bold px-2 py-0.5 rounded ${
                      p.status === 'COMPLETED'
                        ? 'bg-emerald-950 text-emerald-300 border border-emerald-800'
                        : 'bg-sky-950 text-sky-300 border border-sky-800'
                    }`}
                  >
                    {p.status}
                  </span>
                </div>
                <p className="text-slate-400">{p.description}</p>
                <div className="text-[11px] text-slate-500 flex items-center gap-3 pt-1">
                  <span className="flex items-center gap-1">
                    <Calendar className="w-3.5 h-3.5" /> {p.startDate} to {p.endDate}
                  </span>
                </div>
              </div>

              <div className="flex items-center gap-6 shrink-0 text-right text-xs">
                <div>
                  <div className="text-[11px] text-slate-500">Expected Reduction</div>
                  <div className="font-mono font-semibold text-slate-300">-{p.expectedReductionT} tCO₂e/yr</div>
                </div>
                <div>
                  <div className="text-[11px] text-slate-500">Actual Achieved</div>
                  <div className="font-mono font-bold text-emerald-400">-{p.actualReductionT} tCO₂e/yr</div>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
