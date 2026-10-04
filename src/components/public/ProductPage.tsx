/**
 * CarbonFlow — Public Product page.
 *
 * Explains the platform as an enterprise carbon audit and GHG management
 * product, walks the measurement lifecycle, and states plainly what CarbonFlow
 * is not: it is not an assurance provider, a certifier, a regulator, or a legal
 * adviser.
 */

import React from 'react';
import { CheckCircle2, MinusCircle } from 'lucide-react';
import { PublicLink, secondaryButton } from './PublicLayout.tsx';
import {
  ComparisonList,
  Container,
  CtaBand,
  PageHeader,
  Section,
  SectionHeading,
  StepList,
} from './PublicUI.tsx';

const LIFECYCLE_STAGES: Array<{ stage: string; detail: string }> = [
  {
    stage: 'Collect',
    detail:
      'Activity records are entered against a facility, department, legal entity, and reporting period, then linked to the documents that support them.',
  },
  {
    stage: 'Validate',
    detail:
      'Submissions are checked for units, scope assignment, boundary consistency, and evidence coverage before they are allowed to feed a calculation.',
  },
  {
    stage: 'Calculate',
    detail:
      'A deterministic decimal engine resolves the effective emission factor version and GWP set, freezes the calculation snapshot, and writes the resulting emission records.',
  },
  {
    stage: 'Review',
    detail:
      'The audit state machine moves the reporting period through its governed stages, with verification checklists and recorded reviewer comments.',
  },
  {
    stage: 'Emissions',
    detail:
      'Scope 1, Scope 2 location-based, and Scope 2 market-based results are kept as distinct lines, never silently aggregated into one number.',
  },
  {
    stage: 'Analyze',
    detail:
      'Breakdowns by facility, category, scope, and period are computed from the stored records rather than restated from a summary.',
  },
  {
    stage: 'Report',
    detail:
      'Reporting-period summaries, inventory snapshots, and CSV ledger exports are produced from the same underlying records.',
  },
  {
    stage: 'Reduce',
    detail:
      'Targets are set against a baseline and reduction projects are tracked against those targets, so reduction intent is recorded alongside measurement.',
  },
  {
    stage: 'Monitor',
    detail:
      'Targets and project progress are revisited each period against the measured position, and the audit history carries forward.',
  },
];

const CARBONFLOW_IS = [
  'A system of record for an organization\'s greenhouse gas information: activity data, its evidence, the calculations run over it, and the review performed on it.',
  'A preparation layer. It organises the information an assurance provider, certifier, or regulator needs so that engagement starts from a structured position rather than from raw documents.',
  'Tenant-isolated and role-governed. Every record belongs to one organization and one reporting context.',
  'Auditable in its own right: activity transitions, calculation snapshots, checklist verification, findings, corrections, and approvals are recorded rather than overwritten.',
];

const CARBONFLOW_IS_NOT = [
  'Not an assurance provider. CarbonFlow does not perform or grant assurance, verification, or attestation, and it issues no assurance conclusion.',
  'Not a certifier. CarbonFlow cannot certify a product, organization, or claim, and holds no certification authority.',
  'Not a regulator. CarbonFlow has no regulatory function, no enforcement role, and no standing with any authority.',
  'Not legal advice. Nothing in the platform is legal or regulatory advice, and a reporting judgement remains the organization\'s own responsibility.',
  'Not a guarantee of compliance. CarbonFlow supports compliance work; it cannot promise that any particular obligation has been met.',
];

export const ProductPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="Product"
        title="An enterprise carbon audit and GHG management platform"
        lede="CarbonFlow organizes an organization's greenhouse gas information into a governed lifecycle: data is collected with its evidence, calculated deterministically, reviewed through a controlled audit process, reported, and then carried forward to the next period."
      />
      <div className="mt-8">
        <PublicLink to="/register" className={secondaryButton}>
          Get Started
        </PublicLink>
      </div>
    </Section>

    <Section tone="muted" labelledBy="lifecycle-detail-heading">
      <SectionHeading
        id="lifecycle-detail-heading"
        title="The lifecycle CarbonFlow follows"
        description="Each stage has a defined entry condition and produces the record the next stage consumes. Information moves forward; it is not re-entered from scratch each year."
      />
      <ol className="mt-10 space-y-3">
        {LIFECYCLE_STAGES.map((item, index) => (
          <li
            key={item.stage}
            className="grid gap-2 rounded-xl border border-line bg-surface p-5 sm:grid-cols-[minmax(0,10rem)_minmax(0,1fr)] sm:gap-6"
          >
            <div className="flex items-center gap-3">
              <span
                aria-hidden="true"
                className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-brand-50 text-[11px] font-bold text-brand-700"
              >
                {index + 1}
              </span>
              <h3 className="text-sm font-bold uppercase tracking-wider text-ink-950">
                {item.stage}
              </h3>
            </div>
            <p className="text-sm leading-relaxed text-ink-500">{item.detail}</p>
          </li>
        ))}
      </ol>
    </Section>

    <Section labelledBy="positioning-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-start">
        <div>
          <h2
            id="positioning-heading"
            className="flex items-center gap-2 text-2xl font-bold tracking-tight text-ink-950 sm:text-3xl"
          >
            <CheckCircle2 className="h-6 w-6 shrink-0 text-brand-700" aria-hidden="true" />
            What CarbonFlow is
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-ink-500">
            CarbonFlow sits upstream of any external assessment. Its job is to make
            the organization's position complete, evidenced, and internally
            reviewable before anyone else is asked to look at it.
          </p>
          <div className="mt-6">
            <ComparisonList items={CARBONFLOW_IS} />
          </div>
        </div>

        <div>
          <h2 className="flex items-center gap-2 text-2xl font-bold tracking-tight text-ink-950 sm:text-3xl">
            <MinusCircle className="h-6 w-6 shrink-0 text-ink-400" aria-hidden="true" />
            What CarbonFlow is not
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-ink-500">
            These boundaries are deliberate and are enforced by what the platform
            actually does: it records and manages information, and it confers no
            external standing of any kind.
          </p>
          <div className="mt-6">
            <ComparisonList items={CARBONFLOW_IS_NOT} />
          </div>
        </div>
      </div>
    </Section>

    <Section tone="muted" labelledBy="workspace-heading">
      <SectionHeading
        id="workspace-heading"
        title="One workspace, held to one set of rules"
        description="The authenticated workspace CarbonFlow opens after sign-in is organised as a single tenant-scoped application rather than a collection of disconnected tools."
      />
      <div className="mt-10 grid gap-4 sm:gap-5 sm:grid-cols-2 lg:grid-cols-4">
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="text-sm font-semibold text-ink-950">Boundaries first</h3>
          <p className="mt-2 text-sm leading-relaxed text-ink-500">
            Legal entities, departments, facilities, and reporting periods are
            defined before data is collected, so a number always has somewhere to
            belong.
          </p>
        </div>
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="text-sm font-semibold text-ink-950">Evidence is part of the data</h3>
          <p className="mt-2 text-sm leading-relaxed text-ink-500">
            Documents are linked to the activity they support and hashed on
            upload, rather than being referenced by a filename.
          </p>
        </div>
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="text-sm font-semibold text-ink-950">Review is a process, not a person</h3>
          <p className="mt-2 text-sm leading-relaxed text-ink-500">
            Audit state transitions, checklist verification, findings, and
            corrections are recorded steps with required permissions.
          </p>
        </div>
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="text-sm font-semibold text-ink-950">Reporting follows the record</h3>
          <p className="mt-2 text-sm leading-relaxed text-ink-500">
            Summaries, snapshots, and exports are derived from stored records, so
            a report cannot drift from the underlying data.
          </p>
        </div>
      </div>
    </Section>

    <Section labelledBy="next-heading">
      <SectionHeading
        id="next-heading"
        title="Where the workflow starts"
        description="Registration opens an organization in a pending state for platform review. Sign-in is available immediately for organizations already approved."
      />
      <div className="mt-10">
        <StepList
          ordered
          steps={[
            'Submit the organization registration.',
            'A platform administrator reviews and activates the organization.',
            'Sign in with the account created during registration.',
            'Land in the organization\'s GHG workspace.',
          ]}
        />
      </div>
    </Section>

    <CtaBand
      title="Open a CarbonFlow workspace for your organization"
      body="Registration takes a few minutes. Your organization is created in a pending state and reviewed before any account can sign in."
    />
  </>
);