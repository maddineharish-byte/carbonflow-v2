/**
 * CarbonFlow — Public "How It Works" page.
 *
 * Describes the movement of information through the platform. It is written
 * entirely from the outside: what a record is, what makes it move forward, and
 * who is involved. No organization data, no records, no identifiers appear here,
 * because this page is reachable without a session.
 */

import React from 'react';
import { Building2, FileCheck2, FolderLock, History, ListChecks, ShieldCheck, Wrench } from 'lucide-react';
import {
  ComparisonList,
  CtaBand,
  FeatureCard,
  PageHeader,
  Section,
  SectionHeading,
  StepList,
  cardGrid,
} from './PublicUI.tsx';

const FLOW = [
  'Organization',
  'Carbon Data',
  'Evidence',
  'Carbon Audit',
  'Validation',
  'Findings',
  'Corrective Actions',
  'Compliance Tracking',
  'Audit History',
  'Reports & Management',
];

const STAGES: Array<{
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  body: string;
  produces: string;
}> = [
  {
    icon: Building2,
    title: 'Organization',
    body: 'The reporting entity is defined first: its consolidation approach, and the legal entities, departments, and facilities inside its boundary. Reporting periods are created with explicit start and end dates. Nothing can be measured before somewhere to put it exists.',
    produces: 'Produces: a boundary model and a set of reporting periods.',
  },
  {
    icon: FolderLock,
    title: 'Carbon data',
    body: 'Activity records are entered against a facility, department, scope, and period. Each record carries its quantity, unit, and scope assignment, and moves through an explicit submission step rather than becoming calculable the moment it is typed.',
    produces: 'Produces: submitted activity records with resolved scope and period.',
  },
  {
    icon: Wrench,
    title: 'Evidence',
    body: 'The document behind a number is uploaded, validated for size and type, checked against its own magic bytes, hashed with SHA-256 over the stored bytes, and kept in versions. Evidence is then linked to the activity record it supports.',
    produces: 'Produces: hashed, versioned evidence linked to the records it substantiates.',
  },
  {
    icon: FileCheck2,
    title: 'Carbon audit',
    body: 'An audit is opened against a reporting period and moves through a governed state machine: draft, submitted, data collection, validation, review, and then approval, audit-ready, and lock. Each transition is a permission-checked step that records who performed it.',
    produces: 'Produces: an audit record with a controlled, non-skipping state history.',
  },
  {
    icon: ListChecks,
    title: 'Validation',
    body: 'Checklist items are verified individually with the satisfaction state and the reviewer note recorded against the audit. Validation is a recorded activity, not an opinion held in someone\'s head.',
    produces: 'Produces: a verification checklist with per-item state and reviewer notes.',
  },
  {
    icon: FileCheck2,
    title: 'Findings',
    body: 'A reviewer logs a finding against the audit with its severity and detail. The audit cannot be declared audit-ready while unresolved high-severity findings remain, so an open finding cannot be quietly left behind.',
    produces: 'Produces: logged findings that gate the audit\'s forward progress.',
  },
  {
    icon: Wrench,
    title: 'Corrective actions',
    body: 'A correction request cannot be opened without a logged finding. Corrections are tracked as their own records, and an audit that enters correction returns to data collection so the correction is made against the data rather than around it.',
    produces: 'Produces: tracked correction requests with an explicit lifecycle.',
  },
  {
    icon: ListChecks,
    title: 'Compliance tracking',
    body: 'The state of the reporting effort — checklist satisfaction, open findings, outstanding corrections, and the current audit cycle — is readable at any point, so preparation status is a fact rather than a status update.',
    produces: 'Produces: a readable preparation position across requirements and open items.',
  },
  {
    icon: History,
    title: 'Audit history',
    body: 'Approvals, lock events, findings, comments, correction records, and evidence versions are persisted with the audit, so the previous period is not a memory exercise. CarbonFlow keeps this history in these governance records; it does not present one consolidated event ledger across every entity.',
    produces: 'Produces: retained governance history attached to the audit.',
  },
  {
    icon: ShieldCheck,
    title: 'Reports & management',
    body: 'Period summaries, breakdowns, inventory snapshots, and CSV ledger exports are computed from the stored records. Targets and reduction projects are then measured against the reported position, and the cycle begins again for the next period.',
    produces: 'Produces: reports and a measured position that feeds the next period.',
  },
];

const PRINCIPLES = [
  'A record only moves forward when its precondition is met. Submission precedes calculation, a finding precedes a correction request, and a correction returns the audit to data collection.',
  'The inputs used are kept with the result. A calculation stores the factor version and GWP values it actually used, so a past number can be explained rather than only reproduced.',
  'Scope lines are not merged. Scope 1, Scope 2 location-based, and Scope 2 market-based remain distinct so that a reader is never handed a single figure that silently combines two different methods.',
  'Nothing is invented. Where the organization has not supplied data, the workspace shows an empty state; it does not fabricate a figure to fill the space.',
];

export const HowItWorksPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="How It Works"
        title="How information moves through CarbonFlow"
        lede="CarbonFlow is a sequence, not a collection of screens. Each stage consumes the record the previous stage produced, and each has an explicit precondition. That is what makes the result reviewable: a reviewer can follow a number back to the document that produced it."
      />
      <div className="mt-10">
        <StepList steps={FLOW} />
      </div>
    </Section>

    <Section tone="muted" labelledBy="stages-heading">
      <SectionHeading
        id="stages-heading"
        title="Stage by stage"
        description="What each stage does, and what it leaves behind for the stage after it."
      />
      <ol className="mt-10 space-y-4">
        {STAGES.map((stage, index) => (
          <li key={stage.title} className="rounded-xl border border-line bg-surface p-5 sm:p-6">
            <div className="flex items-start gap-4">
              <span
                aria-hidden="true"
                className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-[11px] font-bold text-brand-700"
              >
                {index + 1}
              </span>
              <div className="min-w-0">
                <h3 className="flex items-center gap-2 text-base font-semibold text-ink-950">
                  <stage.icon className="h-4 w-4 shrink-0 text-brand-700" aria-hidden="true" />
                  {stage.title}
                </h3>
                <p className="mt-2 text-sm leading-relaxed text-ink-500">{stage.body}</p>
                <p className="mt-3 text-xs font-medium uppercase tracking-wider text-ink-400">
                  {stage.produces}
                </p>
              </div>
            </div>
          </li>
        ))}
      </ol>
    </Section>

    <Section labelledBy="principles-heading">
      <SectionHeading
        id="principles-heading"
        title="Why the sequence is enforced"
        description="These are the invariants that make the output defensible rather than merely computed."
      />
      <div className="mt-8 max-w-3xl">
        <ComparisonList items={PRINCIPLES} />
      </div>
    </Section>

    <Section tone="muted" labelledBy="access-heading">
      <SectionHeading
        id="access-heading"
        title="Who is involved"
        description="The same records are seen through different roles. Hiding a section is a usability measure; the permission check on the request is the security boundary."
      />
      <div className={`mt-10 ${cardGrid}`}>
        <FeatureCard icon={Building2} title="Data owners">
          Supply the activity records and the documents behind them for the
          facilities and departments they own.
        </FeatureCard>
        <FeatureCard icon={Wrench} title="Carbon accountants">
          Run calculations against the effective factor versions and GWP sets,
          and maintain the reporting position.
        </FeatureCard>
        <FeatureCard icon={FileCheck2} title="Reviewers">
          Drive the audit through its states, verify the checklist, log findings,
          and request corrections.
        </FeatureCard>
        <FeatureCard icon={ListChecks} title="Management">
          Read the dashboard, snapshots, and reports without being able to alter
          the underlying records.
        </FeatureCard>
        <FeatureCard icon={ShieldCheck} title="Company administrators">
          Manage users and roles inside their own organization, and cannot approve
          their own organization.
        </FeatureCard>
        <FeatureCard icon={History} title="Platform administrators">
          Review and activate each newly registered organization before any
          account in it can sign in.
        </FeatureCard>
      </div>
    </Section>

    <CtaBand
      title="Run one period through the whole sequence"
      body="Register your organization to open a workspace. It is created pending, reviewed by a platform administrator, and activated before sign-in."
    />
  </>
);