/**
 * CarbonFlow — Focus trap primitives
 *
 * Extracted from `Modal.tsx` so the tab-cycling behaviour can be unit tested
 * without a DOM implementation. CarbonFlow's test runner is `node:test` with
 * server rendering only, so the logic that decides "what is focusable" and
 * "where does Tab go next" lives here as pure functions.
 */

/** Selects every element that can receive keyboard focus inside a dialog. */
export const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

export interface FocusTrapResult {
  /** Element focus should move to. */
  target: FocusableLike | 'panel';
  /** True when the browser default must be suppressed. */
  preventDefault: boolean;
}

/** The subset of HTMLElement this module needs; keeps tests DOM-free. */
export interface FocusableLike {
  id?: string;
  disabled?: boolean;
  tabIndex?: number;
  tagName?: string;
}

/**
 * Resolves where Tab / Shift+Tab should move focus inside a dialog.
 *
 * Wrapping from the last element back to the first (and from the first back to
 * the last) is the point of a focus trap: it prevents keyboard users from
 * reaching background content while a modal is open. It is not a keyboard
 * trap, because Escape and the explicit Close/Cancel controls remain reachable.
 *
 * @param focusables Focusable elements inside the dialog, in DOM order.
 * @param activeId `id` of the currently focused element, if it is inside the dialog.
 * @param shiftKey True for Shift+Tab.
 */
export function resolveTabTarget(
  focusables: FocusableLike[],
  activeId: string | undefined,
  shiftKey: boolean,
): FocusTrapResult {
  if (focusables.length === 0) {
    // Nothing focusable: keep focus on the dialog itself rather than letting
    // it escape to the background page.
    return { target: 'panel', preventDefault: true };
  }

  const first = focusables[0];
  const last = focusables[focusables.length - 1];

  // Focus is on the dialog panel itself (no activeId) — pull it into the trap.
  if (activeId === undefined) {
    return { target: shiftKey ? last : first, preventDefault: true };
  }

  if (shiftKey) {
    if (first.id === activeId) {
      return { target: last, preventDefault: true };
    }
    return { target: focusables[Math.max(0, indexOfId(focusables, activeId) - 1)], preventDefault: false };
  }

  if (last.id === activeId) {
    return { target: first, preventDefault: true };
  }

  return { target: focusables[Math.min(focusables.length - 1, indexOfId(focusables, activeId) + 1)], preventDefault: false };
}

function indexOfId(focusables: FocusableLike[], id: string): number {
  return focusables.findIndex((element) => element.id === id);
}