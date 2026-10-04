/**
 * CarbonFlow — Public not-found page.
 *
 * Reachable for any unrecognised path. Two properties matter here beyond looking
 * correct:
 *
 *   1. It is a PUBLIC page. An unknown path must never fall through into the
 *      authenticated tree, and this page is rendered by the same public branch
 *      that renders the landing page.
 *   2. It discloses nothing. The requested path is echoed only as inert text for
 *      the reader's own benefit — never as HTML, and never alongside a stack
 *      trace, an internal route table, or any error detail from the backend.
 */

import React from 'react';
import { PublicLink, primaryButton, secondaryButton } from './PublicLayout.tsx';
import { Section } from './PublicUI.tsx';
import { PUBLIC_PAGES } from '../../services/router.ts';

export interface NotFoundPageProps {
  /** The normalised, unrecognised path. Rendered as text only. */
  path?: string;
}

export const NotFoundPage: React.FC<NotFoundPageProps> = ({ path }) => (
  <Section>
    <div className="max-w-3xl">
      <p className="text-xs font-bold uppercase tracking-[0.18em] text-brand-700">Error 404</p>
      <h1 className="mt-4 text-3xl font-bold tracking-tight text-ink-950 sm:text-4xl">
        This page does not exist
      </h1>
      <p className="mt-5 text-base leading-relaxed text-ink-700">
        The address you followed is not part of the CarbonFlow website. It may have
        been mistyped, or the link that brought you here may be out of date.
      </p>

      {path && (
        <p className="mt-4 text-sm text-ink-500">
          Requested address:{' '}
          <code className="break-all rounded bg-surface px-1.5 py-0.5 font-mono text-xs text-ink-700">
            {path}
          </code>
        </p>
      )}

      <div className="mt-8 flex flex-col gap-3 sm:flex-row">
        <PublicLink to="/" className={primaryButton}>
          Back to home
        </PublicLink>
        <PublicLink to="/contact" className={secondaryButton}>
          Contact
        </PublicLink>
      </div>

      <nav aria-label="Site sections" className="mt-12">
        <h2 className="text-xs font-bold uppercase tracking-wider text-ink-500">
          Everything on this site
        </h2>
        <ul className="mt-4 flex flex-wrap gap-2">
          {PUBLIC_PAGES.filter((page) => page.path !== '/').map((page) => (
            <li key={page.path}>
              <PublicLink
                to={page.path}
                className="inline-block rounded-lg border border-line bg-surface px-3 py-1.5 text-sm text-ink-700 transition hover:border-line-strong hover:text-ink-950"
              >
                {page.label}
              </PublicLink>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  </Section>
);