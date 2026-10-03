/**
 * CarbonFlow — Audit Room & Governance Assurance Machine
 * 8-state transition visualizer, mandatory audit checklist verification, findings & comments.
 */
import React, { useState } from 'react';
import {
  FileCheck2,
  CheckCircle2,
  AlertTriangle,
  MessageSquare,
  Lock,
  ArrowRight,
  ShieldCheck,
  Send,
  RotateCcw,
  Check,
  Plus,
} from 'lucide-react';
import { AuditDetail, AuditStatus, RoleName, ReportingPeriod } from '../types.ts';
import { api } from '../services/api.ts';
import { hasPermission } from '../services/permissions.ts';
import { formatAuditInstant } from '../services/format.ts';
import { Modal } from './Modal.tsx';
import { ConfirmDialog } from './ConfirmDialog.tsx';

interface AuditViewProps {
  audit: AuditDetail | null;
  currentRole: RoleName | null;
  permissions: string[];
  periods: ReportingPeriod[];
  onCreateAudit: (reportingPeriodId: string) => void;
  onTransition: (targetState: AuditStatus, reason?: string) => void;
  onVerifyChecklist: (itemId: string, isSatisfied: boolean, notes?: string) => void;
  onCreateFinding: (data: any) => void;
  onResolveFinding: (findingId: string) => void;
  onAddComment: (text: string) => void;
}

const AUDIT_STAGES: AuditStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'DATA_COLLECTION',
  'VALIDATION',
  'REVIEW',
  'APPROVED',
  'AUDIT_READY',
  'LOCKED',
];

/**
 * The backend's legal transitions (AuditStateMachine). The frontend only
 * renders these buttons — the server still validates every request.
 */
const ALLOWED_TRANSITIONS: Record<string, AuditStatus[]> = {
  DRAFT: ['SUBMITTED'],
  SUBMITTED: ['DATA_COLLECTION'],
  DATA_COLLECTION: ['VALIDATION'],
  VALIDATION: ['REVIEW'],
  REVIEW: ['APPROVED', 'CORRECTION_REQUESTED', 'REJECTED'],
  CORRECTION_REQUESTED: ['DATA_COLLECTION'],
  REJECTED: ['DATA_COLLECTION'],
  APPROVED: ['AUDIT_READY'],
  AUDIT_READY: ['LOCKED'],
};

const TRANSITION_LABELS: Record<string, { label: string; reason: string; style: string }> = {
  SUBMITTED: { label: 'Submit Audit', reason: 'Audit package submitted for data collection.', style: 'bg-sky-600 hover:bg-sky-500 text-white' },
  DATA_COLLECTION: { label: 'Begin Validation', reason: 'Data collection complete; moving to validation.', style: 'bg-blue-600 hover:bg-blue-500 text-white' },
  VALIDATION: { label: 'Send to Review', reason: 'Validation complete; ready for reviewer assessment.', style: 'bg-indigo-600 hover:bg-indigo-500 text-white' },
  REVIEW: { label: 'Open Review', reason: 'Passed validation; entering review.', style: 'bg-indigo-600 hover:bg-indigo-500 text-white' },
  APPROVED: { label: 'Approve Audit', reason: 'All checklist items and finding reconciliations satisfied.', style: 'bg-emerald-600 hover:bg-emerald-500 text-white' },
  CORRECTION_REQUESTED: { label: 'Request Correction', reason: 'Clarifications required on evidence attachments.', style: 'bg-amber-950 hover:bg-amber-900 text-amber-300 border border-amber-800' },
  REJECTED: { label: 'Reject Audit', reason: 'Audit package rejected after review.', style: 'bg-rose-600 hover:bg-rose-500 text-white' },
  AUDIT_READY: { label: 'Mark Audit Ready', reason: 'Ready for final lock and assurance submission.', style: 'bg-blue-600 hover:bg-blue-500 text-white' },
  LOCKED: { label: 'Lock Inventory Cycle', reason: 'Final inventory sign-off. Ledger frozen.', style: 'bg-purple-600 hover:bg-purple-500 text-white' },
};

export const AuditView: React.FC<AuditViewProps> = ({
  audit,
  currentRole,
  permissions,
  periods,
  onCreateAudit,
  onTransition,
  onVerifyChecklist,
  onCreateFinding,
  onResolveFinding,
  onAddComment,
}) => {
  const [commentInput, setCommentInput] = useState('');
  const [isFindingModalOpen, setIsFindingModalOpen] = useState(false);
  const [findingTitle, setFindingTitle] = useState('');
  const [findingDesc, setFindingDesc] = useState('');
  const [findingSeverity, setFindingSeverity] = useState<'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'>('MEDIUM');
  const [newAuditPeriodId, setNewAuditPeriodId] = useState('');
  /** A destructive transition awaiting confirmation, if any. */
  const [pendingTransition, setPendingTransition] = useState<AuditStatus | null>(null);

  const canCreateAudit = hasPermission(permissions, 'audits.create');

  /**
   * Rejection and final lock change the audit's legal state and cannot be
   * undone from the UI, so they are confirmed. Forward transitions
   * (submit, collect, validate, review) stay single-click.
   */
  const DESTRUCTIVE_TRANSITIONS: AuditStatus[] = ['REJECTED', 'LOCKED', 'CORRECTION_REQUESTED'];

  if (!audit) {
    return (
      <div className="space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div>
            <h1 className="text-xl font-bold text-white tracking-tight">Audit & Assurance Room</h1>
            <p className="text-xs text-slate-400 mt-1">No audit is currently in progress for this organization.</p>
          </div>
          {canCreateAudit && (
            /* Disabled states explain themselves: the button is inert until a
               period is chosen, and the reason is stated in the hint. */
            <div className="flex flex-col sm:flex-row sm:items-center gap-2">
              <div className="flex flex-col gap-1">
                <label htmlFor="new-audit-period" className="sr-only">
                  Reporting period for new audit
                </label>
                <select
                  id="new-audit-period"
                  value={newAuditPeriodId}
                  onChange={(e) => setNewAuditPeriodId(e.target.value)}
                  aria-describedby="new-audit-period-hint"
                  className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-2 text-xs text-white focus:outline-none"
                  aria-label="Reporting period for new audit"
                >
                  <option value="">Select period…</option>
                  {periods.map((p) => (
                    <option key={p.id} value={p.id}>{p.name}</option>
                  ))}
                </select>
                {!newAuditPeriodId && (
                  <span id="new-audit-period-hint" className="text-[10px] text-slate-500">
                    Select a reporting period to enable initiation.
                  </span>
                )}
              </div>
              <button
                onClick={() => {
                  if (newAuditPeriodId) onCreateAudit(newAuditPeriodId);
                }}
                disabled={!newAuditPeriodId}
                title={newAuditPeriodId ? 'Initiate the governed audit workflow' : 'Select a reporting period first'}
                aria-describedby={!newAuditPeriodId ? 'new-audit-period-hint' : undefined}
                className="flex items-center justify-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus className="w-4 h-4" aria-hidden="true" />
                Initiate Audit
              </button>
            </div>
          )}
        </div>
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-12 text-center">
          <FileCheck2 className="w-8 h-8 text-slate-600 mx-auto mb-3" />
          <div className="text-sm font-semibold text-slate-300">No audit workspace</div>
          <div className="text-xs text-slate-500 mt-1 max-w-sm mx-auto">
            {canCreateAudit
              ? 'Initiate an audit against a reporting period to begin the governed workflow.'
              : 'Your role cannot initiate audits. Contact a sustainability manager or carbon accountant.'}
          </div>
        </div>
      </div>
    );
  }

  const currentStageIndex = AUDIT_STAGES.indexOf(audit.status);
  const isCorrection = audit.status === 'CORRECTION_REQUESTED';
  const allowedTargets = ALLOWED_TRANSITIONS[audit.status] ?? [];

  const handleCommentSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!commentInput.trim()) return;
    onAddComment(commentInput.trim());
    setCommentInput('');
  };

  const handleFindingSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!findingTitle || !findingDesc) return;
    onCreateFinding({
      title: findingTitle,
      description: findingDesc,
      severity: findingSeverity,
    });
    setIsFindingModalOpen(false);
    setFindingTitle('');
    setFindingDesc('');
  };

  return (
    <div className="space-y-6">
      {/* Top Banner */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-bold text-white tracking-tight">Audit & Assurance Room</h1>
            <span
              className={`text-xs font-bold px-2.5 py-0.5 rounded-full border ${
                audit.status === 'LOCKED'
                  ? 'bg-emerald-950 text-emerald-300 border-emerald-800'
                  : 'bg-amber-950 text-amber-300 border-amber-800'
              }`}
            >
              STATE: {audit.status}
            </span>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Period: {audit.period?.name ?? '—'} | State transitions are governed by the backend audit state machine.
          </p>
        </div>

        {/* Transition Controls — rendered from the backend's legal transitions;
            the server remains the authority on what is allowed. */}
        <div className="flex flex-wrap items-center gap-2">
          {allowedTargets.map((target) => {
            const config = TRANSITION_LABELS[target];
            return (
              <button
                key={target}
                onClick={() =>
                  DESTRUCTIVE_TRANSITIONS.includes(target)
                    ? setPendingTransition(target)
                    : onTransition(target, config.reason)
                }
                aria-haspopup={DESTRUCTIVE_TRANSITIONS.includes(target) ? 'dialog' : undefined}
                className={`flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-semibold rounded-lg shadow transition ${config.style}`}
              >
                {target === 'CORRECTION_REQUESTED' ? (
                  <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
                ) : target === 'APPROVED' ? (
                  <Check className="w-4 h-4" aria-hidden="true" />
                ) : target === 'LOCKED' ? (
                  <Lock className="w-4 h-4" aria-hidden="true" />
                ) : (
                  <ArrowRight className="w-3.5 h-3.5" aria-hidden="true" />
                )}
                {config.label}
              </button>
            );
          })}
          {allowedTargets.length === 0 && (
            <span className="text-xs text-slate-500">This audit is in a terminal state.</span>
          )}
        </div>
      </div>

      {/* 8-Stage Visual Stepper */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 overflow-x-auto shadow-sm">
        <div className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mb-4">
          Lifecycle State Transition Machine
        </div>
        {/* The stepper is a status list, not a set of controls: each stage is
            announced as completed, current, or pending so the state is never
            communicated by colour alone. */}
        <ol className="flex items-center min-w-[700px] justify-between relative list-none p-0 m-0">
          {AUDIT_STAGES.map((stage, idx) => {
            const isCompleted = currentStageIndex > idx;
            const isCurrent = audit.status === stage;
            const stageState = isCurrent ? 'current' : isCompleted ? 'completed' : 'pending';

            return (
              <li key={stage} className="contents">
                <div className="flex flex-col items-center gap-1.5 z-10">
                  <div
                    className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold transition ${
                      isCurrent
                        ? 'bg-emerald-500 text-slate-950 ring-4 ring-emerald-500/20'
                        : isCompleted
                        ? 'bg-emerald-950 text-emerald-400 border border-emerald-800'
                        : 'bg-slate-800 text-slate-500 border border-slate-700'
                    }`}
                  >
                    <span aria-hidden="true">{isCompleted ? '✓' : idx + 1}</span>
                  </div>
                  <span
                    className={`text-[10px] font-semibold text-center whitespace-nowrap ${
                      isCurrent ? 'text-emerald-400' : isCompleted ? 'text-slate-300' : 'text-slate-500'
                    }`}
                  >
                    {stage.replace('_', ' ')}
                    {/* Explicit state text — the fill colour is decorative. */}
                    <span className="sr-only">, {stageState}</span>
                  </span>
                </div>
                {idx < AUDIT_STAGES.length - 1 && (
                  <div
                    aria-hidden="true"
                    className={`flex-1 h-0.5 mx-2 -mt-5 transition ${
                      currentStageIndex > idx ? 'bg-emerald-500' : 'bg-slate-800'
                    }`}
                  />
                )}
              </li>
            );
          })}
        </ol>
      </div>

      {/* Main Split View: Checklist & Findings / Comments */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* Left: Mandatory Checklist */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm flex flex-col justify-between">
          <div>
            <div className="p-4 border-b border-slate-800 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <FileCheck2 className="w-4 h-4 text-emerald-400" />
                <h2 className="text-sm font-bold text-white">Mandatory Pre-Approval Checklist</h2>
              </div>
              <span className="text-xs font-medium text-slate-400">
                {audit.checklist.filter((i) => i.isSatisfied).length} / {audit.checklist.length} Complete
              </span>
            </div>

            <div className="divide-y divide-slate-800 max-h-[500px] overflow-y-auto">
              {audit.checklist.map((item) => (
                <div key={item.id} className="p-3.5 hover:bg-slate-800/50 transition flex items-start gap-3">
                  {/* The checkbox is labelled by the visible item title, so a screen reader
                      announces what is being ticked rather than an unnamed box. */}
                  <input
                    type="checkbox"
                    id={`chk-${item.id}`}
                    checked={item.isSatisfied}
                    onChange={(e) => onVerifyChecklist(item.id, e.target.checked)}
                    aria-describedby={`chk-${item.id}-code`}
                    className="mt-1 w-4 h-4 shrink-0 rounded border-slate-700 text-emerald-600 focus:ring-emerald-500 cursor-pointer accent-emerald-500"
                  />
                  <div className="flex-1 text-xs">
                    <div className="flex items-start justify-between gap-2">
                      <label htmlFor={`chk-${item.id}`} className="font-semibold text-white cursor-pointer">
                        {item.title}
                      </label>
                      <span id={`chk-${item.id}-code`} className="font-mono text-[10px] text-slate-500 shrink-0">
                        {item.code}
                      </span>
                    </div>
                    {item.notes && <p className="text-[11px] text-amber-400/90 mt-1">{item.notes}</p>}
                    {item.verifiedAt && (
                      <div className="text-[10px] text-slate-500 mt-1">
                        Verified at: {formatAuditInstant(item.verifiedAt)}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="p-3 bg-slate-800 border-t border-slate-800 text-[11px] text-slate-400">
            Rule: 100% of mandatory checklist items must be satisfied before transitioning to APPROVED.
          </div>
        </div>

        {/* Right: Review Findings & Comments Feed */}
        <div className="space-y-6">
          {/* Findings Card */}
          <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
            <div className="p-4 border-b border-slate-800 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <AlertTriangle className="w-4 h-4 text-amber-400" />
                <h2 className="text-sm font-bold text-white">Review Findings ({audit.findings.length})</h2>
              </div>
              <button
                onClick={() => setIsFindingModalOpen(true)}
                aria-haspopup="dialog"
                className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-xs font-semibold border border-slate-700 transition"
              >
                Log Finding
              </button>
            </div>

            <div className="divide-y divide-slate-800 max-h-56 overflow-y-auto">
              {audit.findings.map((f) => (
                <div key={f.id} className="p-3.5 text-xs space-y-1">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span
                        className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                          f.severity === 'CRITICAL' || f.severity === 'HIGH'
                            ? 'bg-rose-950 text-rose-300 border border-rose-800'
                            : 'bg-amber-950 text-amber-300 border border-amber-800'
                        }`}
                      >
                        {f.severity}
                      </span>
                      <span className="font-semibold text-white">{f.title}</span>
                    </div>
                    {f.status === 'OPEN' ? (
                      <button
                        onClick={() => onResolveFinding(f.id)}
                        aria-label={`Resolve finding ${f.title}, severity ${f.severity}`}
                        className="text-[11px] px-2 py-0.5 rounded bg-emerald-950 text-emerald-300 border border-emerald-800 hover:bg-emerald-900 transition"
                      >
                        Resolve
                      </button>
                    ) : (
                      <span className="text-[10px] font-bold text-emerald-400">RESOLVED</span>
                    )}
                  </div>
                  <p className="text-slate-400 text-[11px]">{f.description}</p>
                </div>
              ))}
            </div>
          </div>

          {/* Review Comments Discussion Feed */}
          <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm flex flex-col">
            <div className="p-4 border-b border-slate-800 flex items-center gap-2">
              <MessageSquare className="w-4 h-4 text-sky-400" />
              <h2 className="text-sm font-bold text-white">Auditor & Management Discussion</h2>
            </div>

            <div className="p-4 space-y-3 max-h-56 overflow-y-auto">
              {audit.comments.map((c, i) => (
                <div key={i} className="p-2.5 bg-slate-800 rounded-lg border border-slate-800 text-xs space-y-1">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="font-semibold text-slate-200">
                      {c.userName} ({c.userRole})
                    </span>
                    <span className="text-slate-500">{formatAuditInstant(c.createdAt)}</span>
                  </div>
                  <p className="text-slate-300">{c.commentText}</p>
                </div>
              ))}
            </div>

            <form onSubmit={handleCommentSubmit} className="p-3 border-t border-slate-800 flex gap-2">
              <label htmlFor="audit-comment-input" className="sr-only">
                Post an assurance clarification or audit response
              </label>
              <input
                id="audit-comment-input"
                value={commentInput}
                onChange={(e) => setCommentInput(e.target.value)}
                placeholder="Post an assurance clarification or audit response..."
                className="flex-1 min-w-0 bg-slate-800 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-emerald-500"
              />
              <button
                type="submit"
                disabled={!commentInput.trim()}
                aria-label="Post comment"
                className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg flex items-center gap-1 transition disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Send className="w-3.5 h-3.5" aria-hidden="true" />
                Post
              </button>
            </form>
          </div>
        </div>
      </div>

      {/* Log Finding Modal */}
      {isFindingModalOpen && (
        <Modal
          title="Log Audit Review Finding"
          onClose={() => setIsFindingModalOpen(false)}
          closeLabel="Cancel logging audit finding"
          panelClassName="max-w-md"
        >
          <form onSubmit={handleFindingSubmit} className="space-y-3 text-xs">
            <div>
              <label htmlFor="finding-title" className="block text-slate-300 mb-1 font-medium">
                Finding Title
              </label>
              <input
                id="finding-title"
                required
                value={findingTitle}
                onChange={(e) => setFindingTitle(e.target.value)}
                placeholder="e.g. Discrepancy in Q3 electricity invoices"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div>
              <label htmlFor="finding-severity" className="block text-slate-300 mb-1 font-medium">
                Severity
              </label>
              <select
                id="finding-severity"
                value={findingSeverity}
                onChange={(e) => setFindingSeverity(e.target.value as any)}
                aria-describedby="finding-severity-hint"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              >
                <option value="LOW">LOW</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="HIGH">HIGH</option>
                <option value="CRITICAL">CRITICAL</option>
              </select>
              <p id="finding-severity-hint" className="mt-1 text-[11px] text-slate-500">
                Severity drives the audit readiness indicator on the dashboard.
              </p>
            </div>

            <div>
              <label htmlFor="finding-description" className="block text-slate-300 mb-1 font-medium">
                Detailed Description
              </label>
              <textarea
                id="finding-description"
                required
                rows={3}
                value={findingDesc}
                onChange={(e) => setFindingDesc(e.target.value)}
                placeholder="Explain the non-conformity or missing evidence..."
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-3 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setIsFindingModalOpen(false)}
                className="px-3 py-1.5 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-1.5 bg-amber-600 hover:bg-amber-500 text-white font-semibold rounded-lg transition"
              >
                Record Finding
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Destructive transition confirmation */}
      {pendingTransition && (
        <ConfirmDialog
          title={`${TRANSITION_LABELS[pendingTransition].label}?`}
          body={
            pendingTransition === 'LOCKED' ? (
              <>
                Locking the inventory cycle freezes the ledger for this reporting period. Emissions records and
                inventory snapshots can no longer be edited or recalculated from CarbonFlow.
              </>
            ) : pendingTransition === 'REJECTED' ? (
              <>
                Rejecting returns this audit package to data collection. The rejection is recorded permanently in
                the audit trail against your role.
              </>
            ) : (
              <>
                Requesting a correction returns this audit to data collection and flags outstanding evidence for the
                reporting team.
              </>
            )
          }
          confirmLabel={TRANSITION_LABELS[pendingTransition].label}
          confirmAriaLabel={`Confirm: ${TRANSITION_LABELS[pendingTransition].label}`}
          onConfirm={() => {
            onTransition(pendingTransition, TRANSITION_LABELS[pendingTransition].reason);
            setPendingTransition(null);
          }}
          onCancel={() => setPendingTransition(null)}
        />
      )}
    </div>
  );
};
