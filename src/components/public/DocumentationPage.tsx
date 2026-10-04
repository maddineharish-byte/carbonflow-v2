/**
 * CarbonFlow — Public Documentation page.
 *
 * A public entry point to product and technical documentation. It deliberately
 * exposes nothing operational: no credentials, no connection strings, no
 * recovery artifacts, no configuration values, and no organization data. Links
 * point at in-repository documentation by name and topic, described so a reader
 * knows what each covers before they ask for access to it.
 */

import React from 'react';
import { BookOpen, FileCode2, Database, GitBranch, Scale, ShieldCheck } from 'lucide-react';
import {
  CtaBand,
  PageHeader,
  Section,
  SectionHeading,
  cardGrid,
} from './PublicUI.tsx';
import { PublicLink, primaryButton, secondaryButton } from './PublicLayout.tsx';

const GUIDES: Array<{
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  body: string;
  to: string;
  linkLabel: string;
}> = [
  {
    icon: BookOpen,
    title: 'Getting started',
    body: 'The registration and sign-in path, what a pending organization can and cannot do, and what to do first once a workspace opens.',
    to: '/how-it-works',
    linkLabel: 'Read how it works',
  },
  {
    icon: FileCode2,
    title: 'API reference',
    body: 'The REST surface: request and response shapes, the standard envelope, the authentication endpoints, and the error codes each failure returns.',
    to: '/technology',
    linkLabel: 'See the architecture',
  },
  {
    icon: Database,
    title: 'Data model & calculations',
    body: 'The schema behind organizations, boundaries, activity, factors, calculations, audits, and evidence, and the rules the calculation engine applies.',
    to: '/features',
    linkLabel: 'Review the capabilities',
  },
  {
    icon: ShieldCheck,
    title: 'Security & access control',
    body: 'Authentication, token handling, role-based authorization, tenant isolation, and the controls this platform does not claim.',
    to: '/security',
    linkLabel: 'Read the security model',
  },
  {
    icon: Scale,
    title: 'Audit workflow reference',
    body: 'The audit state machine, what each transition requires, and how findings and correction requests gate forward progress.',
    to: '/compliance',
    linkLabel: 'Read the compliance position',
  },
  {
    icon: GitBranch,
    title: 'Architecture & decisions',
    body: 'How the layers divide responsibility, and the recorded reasoning behind choices such as plain JDBC over an ORM.',
    to: '/technology',
    linkLabel: 'Read the stack',
  },
];

const NOT_PUBLISHED: string[] = [
  'Deployment credentials, API keys, and database connection details.',
  'Configuration values, environment settings, and secret material of any kind.',
  'Recovery artifacts, backup sets, and operational runbooks containing internal detail.',
  'Any organization\'s carbon data, evidence, audit records, user records, or reporting history.',
  'Security-sensitive operational information about the running deployment.',
];

export const DocumentationPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="Documentation"
        title="Documentation entry point"
        lede="CarbonFlow's product and technical documentation is maintained with the platform itself, so it describes what the software actually does rather than what it was intended to do. This page is the public index into it."
      />
      <div className="mt-8 flex flex-col gap-3 sm:flex-row">
        <PublicLink to="/how-it-works" className={primaryButton}>
          Start with how it works
        </PublicLink>
        <PublicLink to="/technology" className={secondaryButton}>
          Architecture and API
        </PublicLink>
      </div>
    </Section>

    <Section tone="muted" labelledBy="guides-heading">
      <SectionHeading
        id="guides-heading"
        title="Where to begin"
        description="Each topic below is covered on this site in public, non-sensitive detail."
      />
      <div className={`mt-10 ${cardGrid}`}>
        {GUIDES.map((guide) => (
          <div key={guide.title} className="flex flex-col rounded-xl border border-line bg-surface p-5">
            <span
              aria-hidden="true"
              className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-brand-700"
            >
              <guide.icon className="h-4 w-4" />
            </span>
            <h3 className="mt-4 text-base font-semibold text-ink-950">{guide.title}</h3>
            <p className="mt-2 flex-1 text-sm leading-relaxed text-ink-500">{guide.body}</p>
            <div className="mt-4">
              <PublicLink
                to={guide.to}
                className="inline-flex items-center text-sm font-semibold text-brand-700 transition hover:text-brand-800"
              >
                {guide.linkLabel}
                <span aria-hidden="true" className="ml-1.5">
                  &rarr;
                </span>
              </PublicLink>
            </div>
          </div>
        ))}
      </div>
    </Section>

    <Section labelledBy="model-heading">
      <SectionHeading
        id="model-heading"
        title="The reference model, stated plainly"
        description="A short vocabulary so the rest of the documentation is readable without the workspace open."
      />
      <dl className="mt-10 grid gap-4 sm:grid-cols-2">
        {[
          {
            term: 'Organization',
            definition:
              'The reporting entity. It owns every other record and is the isolation boundary for all data access.',
          },
          {
            term: 'Boundary & facility',
            definition:
              'The legal entities, departments, and facilities inside the consolidation boundary that activity data must resolve against.',
          },
          {
            term: 'Reporting period',
            definition:
              'A named interval with explicit start and end dates. Activity, calculations, emissions, and snapshots all resolve against a period.',
          },
          {
            term: 'Activity data',
            definition:
              'A measured quantity for a facility and period, with a scope assignment and a unit, submitted before it may feed a calculation.',
          },
          {
            term: 'Emission factor & GWP set',
            definition:
              'Versioned reference data. A calculation records the effective versions it used, so a past result can be explained.',
          },
          {
            term: 'Calculation & emission record',
            definition:
              'A frozen snapshot of the inputs and the resulting emission line, with Scope 2 kept as separate location and market lines.',
          },
          {
            term: 'Evidence record',
            definition:
              'An uploaded document, versioned, hashed with SHA-256 over the stored bytes, and linked to the record it substantiates.',
          },
          {
            term: 'Carbon audit',
            definition:
              'The review of a reporting period, moving through a governed state machine whose transitions each require a permission.',
          },
          {
            term: 'Finding & correction request',
            definition:
              'A logged review issue and the tracked work to resolve it. A correction cannot be requested without a finding.',
          },
          {
            term: 'Inventory snapshot',
            definition:
              'A per-period position of the emissions inventory that can be locked and used as the comparison basis for later periods.',
          },
        ].map((entry) => (
          <div key={entry.term} className="rounded-xl border border-line bg-surface p-5">
            <dt className="text-sm font-semibold text-ink-950">{entry.term}</dt>
            <dd className="mt-2 text-sm leading-relaxed text-ink-500">{entry.definition}</dd>
          </div>
        ))}
      </dl>
    </Section>

    <Section tone="muted" labelledBy="exclusions-heading">
      <SectionHeading
        id="exclusions-heading"
        title="Not published here"
        description="Some material is deliberately kept out of any public documentation. If you need one of these, it is provided through an authorized channel rather than published."
      />
      <ul className="mt-8 space-y-3 max-w-3xl">
        {NOT_PUBLISHED.map((item) => (
          <li key={item} className="flex items-start gap-3 rounded-lg border border-line bg-surface px-4 py-3">
            <span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-line-strong" />
            <span className="text-sm leading-relaxed text-ink-500">{item}</span>
          </li>
        ))}
      </ul>
    </Section>

    <CtaBand
      title="The fastest documentation is your own data"
      body="Register your organization to open a workspace and read the terminology against something real."
    />
  </>
);