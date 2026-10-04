/**
 * CarbonFlow — Interactive workflow explorer.
 *
 * Replaces the old static vertical list. Each stage is a focusable control that
 * reveals a one-line description; hover and keyboard focus behave identically.
 * No animation beyond a border/elevation transition, fully respected by the
 * reduced-motion rule in index.css.
 */

import React, { useState } from 'react';
import { ArrowRight } from 'lucide-react';

export interface WorkflowExplorerProps {
  /** Step id plus short display label and explanation. */
  steps: Array<{ label: string; detail: string }>;
  /** Colour roles for the numbered badges, indexed by step. */
  variant?: 'default';
}

export const WorkflowExplorer: React.FC<WorkflowExplorerProps> = ({ steps }) => {
  const [active, setActive] = useState(0);

  return (
    <div>
      <ol className="grid grid-cols-1 gap-3 sm:grid-cols-3 lg:grid-cols-9">
        {steps.map((step, index) => (
          <li key={step.label} className="relative">
            <button
              type="button"
              aria-pressed={active === index}
              aria-describedby={`workflow-step-${index}-detail`}
              onClick={() => setActive(index)}
              onFocus={() => setActive(index)}
              onMouseEnter={() => setActive(index)}
              className={`group flex h-full w-full flex-col items-start gap-2 rounded-xl border p-3 text-left transition ${
                active === index
                  ? 'border-brand-400 bg-brand-50 shadow-[0_4px_16px_rgba(20,26,22,0.08)]'
                  : 'border-line bg-surface hover:border-brand-300 hover:bg-brand-50/40'
              }`}
            >
              <span
                aria-hidden="true"
                className={`flex h-7 w-7 items-center justify-center rounded-lg text-[11px] font-bold transition ${
                  active === index ? 'bg-brand-700 text-ink-950' : 'bg-sand-100 text-ink-500 group-hover:text-brand-700'
                }`}
              >
                {index + 1}
              </span>
              <span className="text-xs font-bold uppercase leading-tight tracking-wide text-ink-900">
                {step.label}
              </span>
            </button>
            {active === index && index < steps.length - 1 && (
              <ArrowRight
                aria-hidden="true"
                className="absolute -right-3 top-5 hidden h-5 w-5 text-brand-300 lg:block"
              />
            )}
          </li>
        ))}
      </ol>

      <div
        id={`workflow-step-${active}-detail`}
        role="status"
        aria-live="polite"
        aria-atomic="true"
        className="mt-4 rounded-xl border border-line bg-surface p-5"
      >
        <p className="text-xs font-bold uppercase tracking-[0.18em] text-brand-700">
          {steps[active].label}
        </p>
        <p className="mt-2 text-sm leading-relaxed text-ink-700">{steps[active].detail}</p>
      </div>
    </div>
  );
};