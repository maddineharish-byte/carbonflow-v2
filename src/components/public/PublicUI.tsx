/**
 * CarbonFlow — Public website building blocks.
 *
 * Small, consistent primitives: a warm surface with hairline borders, a deep
 * carbon-green CTA, and eyebrow/heading/section rhythm that keeps the eleven
 * public pages visually unified without any page looking template-generated.
 */

import React from 'react';
import { PublicLink, primaryButton, secondaryButton } from './PublicLayout.tsx';

/** Page-width container with the public site's gutter. */
export const Container: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className = '',
}) => (
  <div className={`mx-auto w-full max-w-7xl px-4 sm:px-6 lg:px-8 ${className}`}>{children}</div>
);

export interface SectionProps {
  id?: string;
  children: React.ReactNode;
  className?: string;
  /** `muted` alternates the band so long pages stay readable. */
  tone?: 'default' | 'muted';
  labelledBy?: string;
}

export const Section: React.FC<SectionProps> = ({
  id,
  children,
  className = '',
  tone = 'default',
  labelledBy,
}) => (
  <section
    id={id}
    aria-labelledby={labelledBy}
    className={`py-16 sm:py-20 ${tone === 'muted' ? 'border-y border-line bg-sand-100/60' : ''} ${className}`}
  >
    <Container>{children}</Container>
  </section>
);

/** Small uppercase eyebrow above a heading. */
export const Eyebrow: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="text-xs font-bold uppercase tracking-[0.18em] text-brand-700">{children}</p>
);

export interface PageHeaderProps {
  eyebrow: string;
  title: string;
  lede: string;
}

export const PageHeader: React.FC<PageHeaderProps> = ({ eyebrow, title, lede }) => (
  <div className="max-w-3xl">
    <Eyebrow>{eyebrow}</Eyebrow>
    <h1 className="mt-4 text-3xl font-bold tracking-tight text-ink-950 sm:text-4xl">{title}</h1>
    <p className="mt-5 text-base leading-relaxed text-ink-700 sm:text-lg">{lede}</p>
  </div>
);

/** Section heading with an optional supporting sentence. */
export const SectionHeading: React.FC<{
  id?: string;
  title: string;
  description?: string;
}> = ({ id, title, description }) => (
  <div className="max-w-3xl">
    <h2 id={id} className="text-2xl font-bold tracking-tight text-ink-950 sm:text-3xl">
      {title}
    </h2>
    {description && <p className="mt-4 text-sm leading-relaxed text-ink-700 sm:text-base">{description}</p>}
  </div>
);

export const cardSurface =
  'rounded-xl border border-line bg-surface p-5 transition hover:border-line-strong hover:shadow-[0_6px_20px_rgba(20,26,22,0.05)] sm:p-6';

export interface FeatureCardProps {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  children: React.ReactNode;
}

/** Icon + heading + prose card. The icon is decorative; the heading names it. */
export const FeatureCard: React.FC<FeatureCardProps> = ({ icon: Icon, title, children }) => (
  <div className={cardSurface}>
    <span
      aria-hidden="true"
      className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-brand-700"
    >
      <Icon className="h-4.5 w-4.5" />
    </span>
    <h3 className="mt-4 text-base font-semibold text-ink-950">{title}</h3>
    <div className="mt-2 text-sm leading-relaxed text-ink-500">{children}</div>
  </div>
);

export const cardGrid = 'grid gap-4 sm:gap-5 sm:grid-cols-2 lg:grid-cols-3';

/** Ordered or unordered readable list of steps / stages. */
export const StepList: React.FC<{ steps: string[]; ordered?: boolean }> = ({
  steps,
  ordered = true,
}) => {
  const Tag = (ordered ? 'ol' : 'ul') as 'ol' | 'ul';
  return (
    <Tag className="space-y-2">
      {steps.map((step, index) => (
        <li
          key={step}
          className="flex items-start gap-3 rounded-lg border border-line bg-surface px-4 py-3"
        >
          {ordered ? (
            <span
              aria-hidden="true"
              className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-brand-50 text-[11px] font-bold text-brand-700"
            >
              {index + 1}
            </span>
          ) : null}
          <span className="text-sm font-medium text-ink-900">{step}</span>
        </li>
      ))}
    </Tag>
  );
};

/** Prose + CTA band used at the bottom of the public pages. */
export const CtaBand: React.FC<{ title: string; body: string }> = ({ title, body }) => (
  <section aria-labelledby="cta-heading" className="border-t border-line bg-brand-950 py-16 sm:py-20">
    <Container>
      <div className="flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
        <div className="max-w-2xl">
          <h2 id="cta-heading" className="text-2xl font-bold tracking-tight text-white sm:text-3xl">
            {title}
          </h2>
          <p className="mt-3 text-sm leading-relaxed text-brand-100">{body}</p>
        </div>
        <div className="flex flex-col gap-3 sm:flex-row lg:shrink-0">
          <PublicLink
            to="/register"
            className="inline-flex items-center justify-center gap-2 rounded-lg bg-white px-4 py-2.5 text-sm font-semibold text-brand-950 shadow transition hover:bg-brand-50"
          >
            Get Started
          </PublicLink>
          <PublicLink
            to="/login"
            className="inline-flex items-center justify-center gap-2 rounded-lg border border-brand-700 bg-transparent px-4 py-2.5 text-sm font-semibold text-white transition hover:border-brand-400"
          >
            Sign In
          </PublicLink>
        </div>
      </div>
    </Container>
  </section>
);

/** "This" bullet list used on the Product/Compliance pages. */
export const ComparisonList: React.FC<{ items: string[] }> = ({ items }) => (
  <ul className="space-y-2.5">
    {items.map((item) => (
      <li key={item} className="flex items-start gap-3 text-sm leading-relaxed text-ink-700">
        <span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
        <span>{item}</span>
      </li>
    ))}
  </ul>
);