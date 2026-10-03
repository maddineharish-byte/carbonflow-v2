/**
 * CarbonFlow — Accessible Modal Dialog
 *
 * Phase 10.11. Every dialog in CarbonFlow is rendered through this component
 * so the keyboard contract is implemented exactly once instead of per view:
 *
 *  - `role="dialog"` + `aria-modal="true"` + `aria-labelledby` / `aria-describedby`
 *  - focus moves into the dialog when it opens
 *  - Tab / Shift+Tab are trapped inside the dialog (no keyboard trap *out*)
 *  - Escape closes the dialog
 *  - focus is restored to the element that opened it
 *  - background scroll is locked while the dialog is open
 *  - the panel is height-capped and internally scrollable, so tall forms stay
 *    usable at 390x844 instead of being clipped by the viewport
 *
 * The backdrop is intentionally NOT click-to-close: CarbonFlow dialogs hold
 * partially entered accounting data, and an accidental backdrop click would
 * discard it silently. Escape and the explicit Close/Cancel controls are the
 * documented dismissal paths.
 */
import React, { useCallback, useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';
import { FOCUSABLE_SELECTOR, resolveTabTarget } from '../services/focusTrap.ts';

interface ModalProps {
  /** Rendered as the dialog's accessible name (via aria-labelledby). */
  title: React.ReactNode;
  /** Optional secondary description wired to aria-describedby. */
  description?: React.ReactNode;
  /** Invoked by Escape, the close button, and Cancel-style dismissals. */
  onClose: () => void;
  /** Visually hidden text for the close button. */
  closeLabel?: string;
  /** Tailwind max-width class for the panel, e.g. `max-w-lg`. */
  panelClassName?: string;
  /** Optional extra classes merged into the panel. */
  panelExtraClassName?: string;
  /** Icon rendered before the title in the header. */
  headerIcon?: React.ReactNode;
  children: React.ReactNode;
}

export const Modal: React.FC<ModalProps> = ({
  title,
  description,
  onClose,
  closeLabel = 'Close dialog',
  panelClassName = 'max-w-lg',
  panelExtraClassName = '',
  headerIcon,
  children,
}) => {
  const titleId = useId();
  const descriptionId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  // Move focus into the dialog on open, and hand it back to the opener on close.
  useEffect(() => {
    openerRef.current = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    if (panel) {
      const first = panel.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (first ?? panel).focus();
    }
    return () => {
      const opener = openerRef.current;
      if (opener && typeof opener.focus === 'function' && document.contains(opener)) {
        opener.focus();
      }
    };
  }, []);

  // Lock background scroll for the lifetime of the dialog.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        return;
      }

      if (event.key !== 'Tab') return;

      const panel = panelRef.current;
      if (!panel) return;

      const focusables = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
        (element) => element.offsetParent !== null || element === document.activeElement,
      );

      const active = document.activeElement as HTMLElement | null;
      const activeId = active && panel.contains(active) ? active.id || undefined : undefined;

      const { target, preventDefault } = resolveTabTarget(focusables, activeId, event.shiftKey);
      if (preventDefault) event.preventDefault();

      if (target === 'panel') {
        panel.focus();
        return;
      }
      (target as HTMLElement).focus();
    },
    [onClose],
  );

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-xs flex items-start sm:items-center justify-center p-3 sm:p-4 z-50 overflow-y-auto">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        className={`bg-slate-900 border border-slate-800 rounded-xl ${panelClassName} w-full p-4 sm:p-6 shadow-2xl space-y-4 my-auto max-h-[calc(100dvh-1.5rem)] sm:max-h-[calc(100dvh-2rem)] overflow-y-auto ${panelExtraClassName}`}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-800 pb-3">
          <div className="flex items-center gap-2 min-w-0">
            {headerIcon}
            <h2 id={titleId} className="text-sm font-bold text-white">
              {title}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label={closeLabel}
            className="shrink-0 p-1 -m-1 rounded text-slate-400 hover:text-white transition"
          >
            <X className="w-4 h-4" aria-hidden="true" />
          </button>
        </div>

        {description && (
          <p id={descriptionId} className="text-xs text-slate-400">
            {description}
          </p>
        )}

        {children}
      </div>
    </div>
  );
};

export default Modal;