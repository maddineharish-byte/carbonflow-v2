/**
 * CarbonFlow — Destructive Action Confirmation
 *
 * Phase 10.11. Irreversible or governance-significant actions (locking an
 * inventory cycle, rejecting an audit, disabling a member, rejecting or
 * suspending an organization) require an explicit confirmation step that
 * names the consequence. The confirmation uses the shared accessible
 * `Modal`, so Escape, focus trapping and focus restoration are identical to
 * every other CarbonFlow dialog.
 */
import React from 'react';
import { AlertTriangle } from 'lucide-react';
import { Modal } from './Modal.tsx';

interface ConfirmDialogProps {
  /** Short imperative question, e.g. "Lock this inventory snapshot?" */
  title: string;
  /** What will happen, in approved CarbonFlow terminology. */
  body: React.ReactNode;
  /** Text of the confirming button; keep it specific and verb-first. */
  confirmLabel: string;
  /** Accessible name for the confirming button. */
  confirmAriaLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
  isBusy?: boolean;
}

export const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  title,
  body,
  confirmLabel,
  confirmAriaLabel,
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  isBusy = false,
}) => (
  <Modal
    title={title}
    onClose={onCancel}
    closeLabel={`${cancelLabel} — ${title}`}
    panelClassName="max-w-md"
    headerIcon={<AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" aria-hidden="true" />}
  >
    <div className="text-xs text-slate-300 space-y-2">
      <div>{body}</div>
      <p className="text-slate-500">This action is recorded against your authenticated session.</p>
    </div>
    <div className="flex flex-col-reverse sm:flex-row sm:justify-end gap-2 pt-3 border-t border-slate-800">
      <button
        type="button"
        onClick={onCancel}
        className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
      >
        {cancelLabel}
      </button>
      <button
        type="button"
        onClick={onConfirm}
        disabled={isBusy}
        aria-label={confirmAriaLabel}
        aria-busy={isBusy || undefined}
        className="px-4 py-2 bg-rose-600 hover:bg-rose-500 text-white font-semibold rounded-lg transition disabled:cursor-not-allowed disabled:opacity-60"
      >
        {confirmLabel}
      </button>
    </div>
  </Modal>
);

export default ConfirmDialog;