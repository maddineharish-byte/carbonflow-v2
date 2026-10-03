/**
 * CarbonFlow — Carbon Targets & Decarbonization Projects
 * Phase 8: target progress comes from the backend's computed block
 * (progressLocationPct / progressMarketPct) — never invented in the browser.
 * Reduction projects are planning records; they are never subtracted from
 * accounted emissions.
 */
import React, { useState } from 'react';
import { Target, CheckCircle, Calendar, Plus, Pencil } from 'lucide-react';
import {
  CarbonTarget,
  CarbonTargetInput,
  ReductionProject,
  ReductionProjectInput,
  ReportingPeriod,
  Facility,
} from '../types.ts';
import { hasPermission } from '../services/permissions.ts';
import { Modal } from './Modal.tsx';

interface TargetsViewProps {
  targets: CarbonTarget[];
  projects: ReductionProject[];
  periods: ReportingPeriod[];
  facilities: Facility[];
  permissions: string[];
  onCreateTarget: (data: CarbonTargetInput) => void;
  onUpdateTarget: (targetId: string, data: Partial<CarbonTargetInput>) => void;
  onCreateProject: (data: ReductionProjectInput) => void;
  onUpdateProject: (projectId: string, data: Partial<ReductionProjectInput>) => void;
}

const TARGET_STATUSES = ['ON_TRACK', 'BEHIND', 'ACHIEVED', 'EXPIRED'] as const;
const PROJECT_STATUSES = ['PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const;

export const TargetsView: React.FC<TargetsViewProps> = ({
  targets,
  projects,
  periods,
  facilities,
  permissions,
  onCreateTarget,
  onUpdateTarget,
  onCreateProject,
  onUpdateProject,
}) => {
  const [isTargetModalOpen, setIsTargetModalOpen] = useState(false);
  const [isProjectModalOpen, setIsProjectModalOpen] = useState(false);
  const [editingTarget, setEditingTarget] = useState<CarbonTarget | null>(null);
  const [editingProject, setEditingProject] = useState<ReductionProject | null>(null);

  // New target form state
  const [targetName, setTargetName] = useState('');
  const [baselinePeriodId, setBaselinePeriodId] = useState('');
  const [targetPeriodId, setTargetPeriodId] = useState('');
  const [baselineValue, setBaselineValue] = useState('');
  const [targetValue, setTargetValue] = useState('');
  const [reductionPct, setReductionPct] = useState('');

  // Edit target form state
  const [editStatus, setEditStatus] = useState<string>('ON_TRACK');
  const [editNotes, setEditNotes] = useState('');

  // New project form state
  const [projectName, setProjectName] = useState('');
  const [projectDesc, setProjectDesc] = useState('');
  const [projectFacilityId, setProjectFacilityId] = useState('');
  const [projectTargetId, setProjectTargetId] = useState('');
  const [projectStart, setProjectStart] = useState('');
  const [projectEnd, setProjectEnd] = useState('');

  // Edit project form state
  const [editProjectStatus, setEditProjectStatus] = useState<string>('PLANNED');
  const [editProjectDesc, setEditProjectDesc] = useState('');

  const canCreateTarget = hasPermission(permissions, 'targets.create');
  const canUpdateTarget = hasPermission(permissions, 'targets.update');
  const canCreateProject = hasPermission(permissions, 'reduction_projects.create');
  const canUpdateProject = hasPermission(permissions, 'reduction_projects.update');

  const totalExpectedReductions = projects.reduce((acc, p) => acc + p.expectedReductionT, 0);
  const totalActualReductions = projects.reduce((acc, p) => acc + p.actualReductionT, 0);

  const openEditTarget = (target: CarbonTarget) => {
    setEditingTarget(target);
    setEditStatus(target.status);
    setEditNotes(target.notes ?? '');
  };

  const openEditProject = (project: ReductionProject) => {
    setEditingProject(project);
    setEditProjectStatus(project.status);
    setEditProjectDesc(project.description ?? '');
  };

  const handleCreateTarget = (e: React.FormEvent) => {
    e.preventDefault();
    onCreateTarget({
      name: targetName,
      baselinePeriodId,
      targetPeriodId,
      baselineValueT: Number(baselineValue),
      targetValueT: Number(targetValue),
      reductionPercentage: Number(reductionPct),
    });
    setIsTargetModalOpen(false);
    setTargetName('');
    setBaselineValue('');
    setTargetValue('');
    setReductionPct('');
  };

  const handleUpdateTarget = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingTarget) return;
    onUpdateTarget(editingTarget.id, { status: editStatus, notes: editNotes });
    setEditingTarget(null);
  };

  const handleCreateProject = (e: React.FormEvent) => {
    e.preventDefault();
    onCreateProject({
      name: projectName,
      description: projectDesc,
      facilityId: projectFacilityId || undefined,
      targetId: projectTargetId || undefined,
      startDate: projectStart,
      endDate: projectEnd || undefined,
    });
    setIsProjectModalOpen(false);
    setProjectName('');
    setProjectDesc('');
    setProjectStart('');
    setProjectEnd('');
  };

  const handleUpdateProject = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingProject) return;
    onUpdateProject(editingProject.id, { status: editProjectStatus, description: editProjectDesc });
    setEditingProject(null);
  };

  const progressFor = (target: CarbonTarget): number | null => target.progressLocationPct;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Reduction Targets & Decarbonization Projects</h1>
          <p className="text-xs text-slate-400 mt-1">
            Science-based emissions trajectory monitoring and capital energy efficiency project accounting.
          </p>
        </div>
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
          {canCreateProject && (
            <button
              onClick={() => setIsProjectModalOpen(true)}
              aria-haspopup="dialog"
              className="flex items-center justify-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition"
            >
              <Plus className="w-4 h-4" aria-hidden="true" />
              New Project
            </button>
          )}
          {canCreateTarget && (
            <button
              onClick={() => setIsTargetModalOpen(true)}
              aria-haspopup="dialog"
              className="flex items-center justify-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
            >
              <Plus className="w-4 h-4" aria-hidden="true" />
              New Target
            </button>
          )}
        </div>
      </div>

      {/* Target Progress Cards */}
      {targets.length === 0 ? (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-12 text-center">
          <Target className="w-8 h-8 text-slate-600 mx-auto mb-3" />
          <div className="text-sm font-semibold text-slate-300">No carbon targets defined</div>
          <div className="text-xs text-slate-500 mt-1">
            {canCreateTarget
              ? 'Create a target with baseline and target reporting periods to track reduction progress.'
              : 'Your role cannot create targets. Contact a sustainability manager.'}
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          {targets.map((target) => {
            const progress = progressFor(target);
            return (
              <div key={target.id} className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm space-y-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2 min-w-0">
                    <Target className="w-5 h-5 shrink-0 text-emerald-400" aria-hidden="true" />
                    <h2 className="text-sm font-bold text-white">{target.name}</h2>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <span
                      className={`text-[10px] font-bold px-2 py-0.5 rounded border ${
                        target.status === 'ACHIEVED'
                          ? 'bg-emerald-950 text-emerald-300 border-emerald-800'
                          : target.status === 'BEHIND'
                          ? 'bg-amber-950 text-amber-300 border-amber-800'
                          : target.status === 'EXPIRED'
                          ? 'bg-slate-800 text-slate-400 border-slate-700'
                          : 'bg-sky-950 text-sky-300 border-sky-800'
                      }`}
                    >
                      {target.status}
                    </span>
                    {canUpdateTarget && (
                      <button
                        onClick={() => openEditTarget(target)}
                        aria-haspopup="dialog"
                        aria-label={`Edit target ${target.name}`}
                        className="p-1 text-slate-400 hover:text-sky-400 transition"
                        title="Edit target"
                      >
                        <Pencil className="w-3.5 h-3.5" aria-hidden="true" />
                      </button>
                    )}
                  </div>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-center py-2 bg-slate-800 rounded-lg border border-slate-800 text-xs">
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

                {/* Progress bar — backend-computed percentage, or an explicit no-data state */}
                <div className="space-y-1.5">
                  <div className="flex justify-between text-[11px] text-slate-400">
                    <span>Decarbonization Trajectory</span>
                    {progress != null ? (
                      <span className="font-semibold text-white">{progress.toFixed(1)}% realized</span>
                    ) : (
                      <span className="text-slate-500">No emissions data for target period yet</span>
                    )}
                  </div>
                  {/* Progress is exposed as a progressbar with real values. The gradient fill
                      is decorative; assistive technology reads the numeric
                      percentage, and the adjacent visible text states it too, so
                      the bar is never the only carrier of the information. */}
                  <div
                    role="progressbar"
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={progress != null ? Number(progress.toFixed(1)) : undefined}
                    aria-valuetext={
                      progress != null
                        ? `${progress.toFixed(1)} percent of target reduction realized`
                        : 'No emissions data for target period yet'
                    }
                    aria-label={`${target.name} decarbonization trajectory`}
                    className="w-full bg-slate-800 rounded-full h-2 overflow-hidden border border-slate-700"
                  >
                    <div
                      className="bg-gradient-to-r from-emerald-500 to-sky-400 h-2 rounded-full transition-all"
                      style={{ width: `${Math.min(100, Math.max(0, progress ?? 0))}%` }}
                    />
                  </div>
                  {target.hasPersistedEmissions && (
                    <div className="flex justify-between text-[10px] text-slate-500">
                      <span>Current (location): {target.currentLocationBasedT?.toFixed(2) ?? '—'} t</span>
                      <span>Current (market): {target.currentMarketBasedT?.toFixed(2) ?? '—'} t</span>
                    </div>
                  )}
                </div>

                {target.notes && <p className="text-[11px] text-slate-500">{target.notes}</p>}
              </div>
            );
          })}
        </div>
      )}

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

        {projects.length === 0 ? (
          <div className="p-12 text-center">
            <CheckCircle className="w-8 h-8 text-slate-600 mx-auto mb-3" />
            <div className="text-sm font-semibold text-slate-300">No reduction projects yet</div>
            <div className="text-xs text-slate-500 mt-1">
              Projects are planning records — they never modify accounted emissions.
            </div>
          </div>
        ) : (
          <div className="divide-y divide-slate-800">
            {projects.map((p) => (
              <div key={p.id} className="p-4 hover:bg-slate-800/50 transition flex flex-col md:flex-row justify-between gap-4">
                <div className="space-y-1 text-xs">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-bold text-white text-sm">{p.name}</span>
                    <span
                      className={`text-[10px] font-bold px-2 py-0.5 rounded ${
                        p.status === 'COMPLETED'
                          ? 'bg-emerald-950 text-emerald-300 border border-emerald-800'
                          : p.status === 'CANCELLED'
                          ? 'bg-slate-800 text-slate-400 border border-slate-700'
                          : 'bg-sky-950 text-sky-300 border border-sky-800'
                      }`}
                    >
                      {p.status}
                    </span>
                    {canUpdateProject && (
                      <button
                        onClick={() => openEditProject(p)}
                        aria-haspopup="dialog"
                        aria-label={`Edit project ${p.name}`}
                        className="p-0.5 text-slate-400 hover:text-sky-400 transition"
                        title="Edit project"
                      >
                        <Pencil className="w-3 h-3" aria-hidden="true" />
                      </button>
                    )}
                  </div>
                  {p.description && <p className="text-slate-400">{p.description}</p>}
                  <div className="text-[11px] text-slate-500 flex items-center gap-3 pt-1">
                    <span className="flex items-center gap-1">
                      <Calendar className="w-3.5 h-3.5" /> {p.startDate} to {p.endDate}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-6 shrink-0 text-left sm:text-right text-xs">
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
        )}
      </div>

      {/* New Target Modal */}
      {isTargetModalOpen && (
        <Modal
          title="Create Carbon Target"
          description="Target progress is computed by the backend from persisted emissions — it is never estimated in the browser."
          onClose={() => setIsTargetModalOpen(false)}
          closeLabel="Cancel creating carbon target"
        >
          <form onSubmit={handleCreateTarget} className="space-y-3 text-xs">
            <div>
              <label htmlFor="target-name" className="block text-slate-300 mb-1 font-medium">
                Target Name
              </label>
              <input
                id="target-name"
                required
                value={targetName}
                onChange={(e) => setTargetName(e.target.value)}
                placeholder="e.g. 20% reduction by FY2030"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="target-baseline-period" className="block text-slate-300 mb-1 font-medium">
                  Baseline Period
                </label>
                <select
                  id="target-baseline-period"
                  required
                  value={baselinePeriodId}
                  onChange={(e) => setBaselinePeriodId(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  <option value="">Select period…</option>
                  {periods.map((p) => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor="target-period" className="block text-slate-300 mb-1 font-medium">
                  Target Period
                </label>
                <select
                  id="target-period"
                  required
                  value={targetPeriodId}
                  onChange={(e) => setTargetPeriodId(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  <option value="">Select period…</option>
                  {periods.map((p) => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
              </div>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div>
                <label htmlFor="target-baseline-value" className="block text-slate-300 mb-1 font-medium">
                  Baseline (t)
                </label>
                <input
                  id="target-baseline-value"
                  required
                  type="number"
                  step="any"
                  min="0"
                  value={baselineValue}
                  onChange={(e) => setBaselineValue(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label htmlFor="target-value" className="block text-slate-300 mb-1 font-medium">
                  Target (t)
                </label>
                <input
                  id="target-value"
                  required
                  type="number"
                  step="any"
                  min="0"
                  value={targetValue}
                  onChange={(e) => setTargetValue(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label htmlFor="target-reduction-pct" className="block text-slate-300 mb-1 font-medium">
                  Reduction %
                </label>
                <input
                  id="target-reduction-pct"
                  required
                  type="number"
                  step="any"
                  min="0"
                  max="100"
                  value={reductionPct}
                  onChange={(e) => setReductionPct(e.target.value)}
                  aria-describedby="target-reduction-hint"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                />
                <p id="target-reduction-hint" className="mt-1 text-[11px] text-slate-500">
                  Whole or fractional percentage, 0 to 100.
                </p>
              </div>
            </div>
            <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-3 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setIsTargetModalOpen(false)}
                className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
              >
                Create Target
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Edit Target Modal */}
      {editingTarget && (
        <Modal
          title={`Edit Target — ${editingTarget.name}`}
          onClose={() => setEditingTarget(null)}
          closeLabel={`Cancel editing ${editingTarget.name}`}
          panelClassName="max-w-md"
        >
          <form onSubmit={handleUpdateTarget} className="space-y-3 text-xs">
            <div>
              <label htmlFor="edit-target-status" className="block text-slate-300 mb-1 font-medium">
                Status
              </label>
              <select
                id="edit-target-status"
                value={editStatus}
                onChange={(e) => setEditStatus(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              >
                {TARGET_STATUSES.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="edit-target-notes" className="block text-slate-300 mb-1 font-medium">
                Notes
              </label>
              <textarea
                id="edit-target-notes"
                rows={3}
                value={editNotes}
                onChange={(e) => setEditNotes(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-3 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setEditingTarget(null)}
                className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
              >
                Save Changes
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* New Project Modal */}
      {isProjectModalOpen && (
        <Modal
          title="Create Reduction Project"
          description="Projects are planning records — they never modify accounted emissions."
          onClose={() => setIsProjectModalOpen(false)}
          closeLabel="Cancel creating reduction project"
        >
          <form onSubmit={handleCreateProject} className="space-y-3 text-xs">
            <div>
              <label htmlFor="project-name" className="block text-slate-300 mb-1 font-medium">
                Project Name
              </label>
              <input
                id="project-name"
                required
                value={projectName}
                onChange={(e) => setProjectName(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label htmlFor="project-description" className="block text-slate-300 mb-1 font-medium">
                Description
              </label>
              <textarea
                id="project-description"
                rows={2}
                value={projectDesc}
                onChange={(e) => setProjectDesc(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="project-facility" className="block text-slate-300 mb-1 font-medium">
                  Facility (optional)
                </label>
                <select
                  id="project-facility"
                  value={projectFacilityId}
                  onChange={(e) => setProjectFacilityId(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  <option value="">None</option>
                  {facilities.map((f) => (
                    <option key={f.id} value={f.id}>{f.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor="project-target" className="block text-slate-300 mb-1 font-medium">
                  Linked Target (optional)
                </label>
                <select
                  id="project-target"
                  value={projectTargetId}
                  onChange={(e) => setProjectTargetId(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  <option value="">None</option>
                  {targets.map((t) => (
                    <option key={t.id} value={t.id}>{t.name}</option>
                  ))}
                </select>
              </div>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="project-start" className="block text-slate-300 mb-1 font-medium">
                  Start Date
                </label>
                <input
                  id="project-start"
                  required
                  type="date"
                  value={projectStart}
                  onChange={(e) => setProjectStart(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label htmlFor="project-end" className="block text-slate-300 mb-1 font-medium">
                  End Date (optional)
                </label>
                <input
                  id="project-end"
                  type="date"
                  value={projectEnd}
                  onChange={(e) => setProjectEnd(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
            </div>
            <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-3 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setIsProjectModalOpen(false)}
                className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
              >
                Create Project
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Edit Project Modal */}
      {editingProject && (
        <Modal
          title={`Edit Project — ${editingProject.name}`}
          onClose={() => setEditingProject(null)}
          closeLabel={`Cancel editing ${editingProject.name}`}
          panelClassName="max-w-md"
        >
          <form onSubmit={handleUpdateProject} className="space-y-3 text-xs">
            <div>
              <label htmlFor="edit-project-status" className="block text-slate-300 mb-1 font-medium">
                Status
              </label>
              <select
                id="edit-project-status"
                value={editProjectStatus}
                onChange={(e) => setEditProjectStatus(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              >
                {PROJECT_STATUSES.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="edit-project-description" className="block text-slate-300 mb-1 font-medium">
                Description
              </label>
              <textarea
                id="edit-project-description"
                rows={3}
                value={editProjectDesc}
                onChange={(e) => setEditProjectDesc(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-3 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setEditingProject(null)}
                className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
              >
                Save Changes
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
};
