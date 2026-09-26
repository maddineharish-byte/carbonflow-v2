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
} from 'lucide-react';
import { AuditDetail, AuditStatus, RoleName } from '../types.ts';

interface AuditViewProps {
  audit: AuditDetail | null;
  currentRole: RoleName | null;
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

export const AuditView: React.FC<AuditViewProps> = ({
  audit,
  currentRole,
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

  if (!audit) {
    return <div className="p-8 text-slate-400">Loading audit workspace...</div>;
  }

  const currentStageIndex = AUDIT_STAGES.indexOf(audit.status);
  const isCorrection = audit.status === 'CORRECTION_REQUESTED';

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
            Period: {audit.period?.name || 'FY2024'} | Governed by ISO 14064-3 Third-Party Verification Standards.
          </p>
        </div>

        {/* Transition Controls */}
        <div className="flex flex-wrap items-center gap-2">
          {audit.status === 'REVIEW' && (
            <>
              <button
                onClick={() => onTransition('CORRECTION_REQUESTED', 'Clarifications required on evidence attachments.')}
                className="flex items-center gap-1.5 px-3 py-2 bg-amber-950 hover:bg-amber-900 text-amber-300 border border-amber-800 text-xs font-semibold rounded-lg transition"
              >
                <RotateCcw className="w-3.5 h-3.5" />
                Request Correction
              </button>
              <button
                onClick={() => onTransition('APPROVED', 'All checklist items and finding reconciliations satisfied.')}
                className="flex items-center gap-1.5 px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
              >
                <Check className="w-4 h-4" />
                Approve Audit
              </button>
            </>
          )}

          {audit.status === 'APPROVED' && (
            <button
              onClick={() => onTransition('AUDIT_READY', 'Ready for final lock and external assurance submission.')}
              className="flex items-center gap-1.5 px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold rounded-lg shadow transition"
            >
              <ShieldCheck className="w-4 h-4" />
              Mark Audit Ready
            </button>
          )}

          {audit.status === 'AUDIT_READY' && (
            <button
              onClick={() => onTransition('LOCKED', 'Final inventory sign-off. Ledger frozen.')}
              className="flex items-center gap-1.5 px-4 py-2 bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold rounded-lg shadow transition"
            >
              <Lock className="w-4 h-4" />
              Lock Inventory Cycle
            </button>
          )}

          {audit.status === 'CORRECTION_REQUESTED' && (
            <button
              onClick={() => onTransition('DATA_COLLECTION', 'Reopened for data owner corrections.')}
              className="flex items-center gap-1.5 px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 text-xs font-semibold rounded-lg transition"
            >
              Re-open Data Collection
            </button>
          )}
        </div>
      </div>

      {/* 8-Stage Visual Stepper */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 overflow-x-auto shadow-sm">
        <div className="text-[10px] font-bold uppercase tracking-wider text-slate-500 mb-4">
          Lifecycle State Transition Machine
        </div>
        <div className="flex items-center min-w-[700px] justify-between relative">
          {AUDIT_STAGES.map((stage, idx) => {
            const isCompleted = currentStageIndex > idx;
            const isCurrent = audit.status === stage;

            return (
              <React.Fragment key={stage}>
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
                    {isCompleted ? '✓' : idx + 1}
                  </div>
                  <span
                    className={`text-[10px] font-semibold text-center whitespace-nowrap ${
                      isCurrent ? 'text-emerald-400' : isCompleted ? 'text-slate-300' : 'text-slate-500'
                    }`}
                  >
                    {stage.replace('_', ' ')}
                  </span>
                </div>
                {idx < AUDIT_STAGES.length - 1 && (
                  <div
                    className={`flex-1 h-0.5 mx-2 -mt-5 transition ${
                      currentStageIndex > idx ? 'bg-emerald-500' : 'bg-slate-800'
                    }`}
                  />
                )}
              </React.Fragment>
            );
          })}
        </div>
      </div>

      {/* Main Split View: Checklist & Findings / Comments */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
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
                <div key={item.id} className="p-3.5 hover:bg-slate-850/50 transition flex items-start gap-3">
                  <input
                    type="checkbox"
                    id={`chk-${item.id}`}
                    checked={item.isSatisfied}
                    onChange={(e) => onVerifyChecklist(item.id, e.target.checked)}
                    className="mt-1 w-4 h-4 rounded border-slate-700 text-emerald-600 focus:ring-emerald-500 cursor-pointer accent-emerald-500"
                  />
                  <div className="flex-1 text-xs">
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-white">{item.title}</span>
                      <span className="font-mono text-[10px] text-slate-500">{item.code}</span>
                    </div>
                    {item.notes && <p className="text-[11px] text-amber-400/90 mt-1">{item.notes}</p>}
                    {item.verifiedAt && (
                      <div className="text-[10px] text-slate-500 mt-1">
                        Verified at: {new Date(item.verifiedAt).toLocaleDateString()}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="p-3 bg-slate-850 border-t border-slate-800 text-[11px] text-slate-400">
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
                <div key={i} className="p-2.5 bg-slate-850 rounded-lg border border-slate-800 text-xs space-y-1">
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="font-semibold text-slate-200">
                      {c.userName} ({c.userRole})
                    </span>
                    <span className="text-slate-500">{new Date(c.createdAt).toLocaleTimeString()}</span>
                  </div>
                  <p className="text-slate-300">{c.commentText}</p>
                </div>
              ))}
            </div>

            <form onSubmit={handleCommentSubmit} className="p-3 border-t border-slate-800 flex gap-2">
              <input
                value={commentInput}
                onChange={(e) => setCommentInput(e.target.value)}
                placeholder="Post an assurance clarification or audit response..."
                className="flex-1 bg-slate-800 border border-slate-700 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-emerald-500"
              />
              <button
                type="submit"
                className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg flex items-center gap-1 transition"
              >
                <Send className="w-3.5 h-3.5" />
                Post
              </button>
            </form>
          </div>
        </div>
      </div>

      {/* Log Finding Modal */}
      {isFindingModalOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-xs flex items-center justify-center p-4 z-50">
          <div className="bg-slate-900 border border-slate-800 rounded-xl max-w-md w-full p-6 shadow-2xl space-y-4 text-xs">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <h3 className="text-sm font-bold text-white">Log Audit Review Finding</h3>
              <button onClick={() => setIsFindingModalOpen(false)} className="text-slate-400 hover:text-white">
                ✕
              </button>
            </div>

            <form onSubmit={handleFindingSubmit} className="space-y-3">
              <div>
                <label className="block text-slate-300 mb-1 font-medium">Finding Title</label>
                <input
                  required
                  value={findingTitle}
                  onChange={(e) => setFindingTitle(e.target.value)}
                  placeholder="e.g. Discrepancy in Q3 electricity invoices"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-medium">Severity</label>
                <select
                  value={findingSeverity}
                  onChange={(e) => setFindingSeverity(e.target.value as any)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  <option value="LOW">LOW</option>
                  <option value="MEDIUM">MEDIUM</option>
                  <option value="HIGH">HIGH</option>
                  <option value="CRITICAL">CRITICAL</option>
                </select>
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-medium">Detailed Description</label>
                <textarea
                  required
                  rows={3}
                  value={findingDesc}
                  onChange={(e) => setFindingDesc(e.target.value)}
                  placeholder="Explain the non-conformity or missing evidence..."
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>

              <div className="flex justify-end gap-2 pt-3 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setIsFindingModalOpen(false)}
                  className="px-3 py-1.5 rounded-lg text-slate-400 hover:text-white"
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
          </div>
        </div>
      )}
    </div>
  );
};
