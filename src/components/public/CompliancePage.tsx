/**
 * CarbonFlow — Public Compliance page.
 *
 * The central constraint of this page: CarbonFlow does NOT guarantee compliance
 * and never says it does. It helps an organization organise the information a
 * reporting obligation refers to, keep the evidence, track the state of the
 * work, and retain the history.
 *
 * CarbonFlow is a global platform. No jurisdiction's rules are hardcoded here or
 * anywhere in the product, and no single market is presented as the target: the
 * schema stores no default country or currency, and the reference data (GWP
 * sets, emission factor versions) is data rather than compiled-in assumptions.
 */

import React from 'react';
import { FileCheck2, FileSpreadsheet, FolderLock, History, ListChecks, Scale } from 'lucide-react';
import { ComparisonList, CtaBand, FeatureCard, PageHeader, Section, SectionHeading, cardGrid } from './PublicUI.tsx';

const SUPPORTS = [
  'Organizing the information a reporting obligation refers to, so the work has a defined structure rather than a folder of documents.',
  'Maintaining evidence alongside the figure it supports, hashed and versioned, so a number can be defended when it is questioned.',
  'Tracking requirements as checklist items with a recorded satisfaction state and a reviewer note, so the state of the work is a fact.',
  'Managing findings and corrective actions as first-class records, so an open issue cannot be lost between reporting periods.',
  'Maintaining audit history: approvals, lock events, findings, comments, corrections, and evidence versions are retained with the audit.',
  'Preparing reports from stored records, so what is published can be traced back to the calculation that produced it.',
];

const DOES_NOT = [
  'CarbonFlow does not guarantee compliance, and it does not assert that any obligation has been met.',
  'CarbonFlow is not an assurance provider and issues no assurance, verification, or attestation conclusion.',
  'CarbonFlow is not a certifier and confers no certification of any product, organization, or claim.',
  'CarbonFlow is not a regulator, has no enforcement role, and holds no standing with any authority.',
  'CarbonFlow does not provide legal or regulatory advice, and does not evaluate the rules of any jurisdiction.',
  'CarbonFlow cannot tell you which framework applies to you. That determination belongs to the organization and to whoever it engages.',
];

const GLOBALITY_POINTS = [
  'No jurisdiction is hardcoded. The organization schema stores no default country, and the tenant states its own.',
  'No currency is hardcoded. Currency is tenant data, not an application constant.',
  'No timezone is assumed. Instants are stored and rendered in an explicit unambiguous form so that two reviewers in different places read the same moment.',
  'GWP sets and emission factors are versioned reference data with effective dating, not values compiled into the application.',
];

export const CompliancePage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="Compliance"
        title="Supporting carbon audit and compliance work"
        lede="CarbonFlow does not guarantee compliance. It is the system an organization uses to do the work that a reporting obligation implies: hold the information, keep the evidence, track what is outstanding, and retain the history afterwards."
      />
      <div className="mt-8 max-w-3xl rounded-xl border border-amber-200 bg-amber-50 p-5">
        <h2 className="text-sm font-semibold text-amber-800">The distinction that matters</h2>
        <p className="mt-2 text-sm leading-relaxed text-ink-700">
          A management platform can make an organization's records complete,
          consistent, and reviewable. It cannot decide whether a given reporting
          obligation has been discharged, because that determination depends on the
          applicable rules and on the facts. CarbonFlow supports the work and states
          plainly what it does not know.
        </p>
      </div>
    </Section>

    <Section tone="muted" labelledBy="supports-heading">
      <SectionHeading
        id="supports-heading"
        title="How CarbonFlow supports compliance work"
        description="Six concrete things the platform does, each of which is a real capability rather than an aspiration."
      />
      <div className="mt-10 grid gap-4 sm:gap-5 sm:grid-cols-2">
        {SUPPORTS.map((item) => (
          <div key={item} className="flex items-start gap-3 rounded-xl border border-line bg-surface p-5">
            <span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
            <p className="text-sm leading-relaxed text-ink-700">{item}</p>
          </div>
        ))}
      </div>
    </Section>

    <Section labelledBy="lifecycle-heading">
      <SectionHeading
        id="lifecycle-heading"
        title="The compliance-relevant machinery"
        description="The parts of CarbonFlow that a reviewer would actually rely on."
      />
      <div className={`mt-10 ${cardGrid}`}>
        <FeatureCard icon={ListChecks} title="Requirement state">
          Checklist items carry a satisfaction state and a reviewer note, so the
          state of each requirement is recorded rather than remembered.
        </FeatureCard>
        <FeatureCard icon={FileCheck2} title="Open items cannot be skipped">
          A correction request requires a logged finding, and an audit cannot
          reach audit-ready with unresolved high-severity findings. The process
          enforces the gate instead of trusting the reviewer to remember it.
        </FeatureCard>
        <FeatureCard icon={FolderLock} title="Evidence behind every figure">
          Source documents are hashed with SHA-256, versioned, and linked to the
          records they substantiate.
        </FeatureCard>
        <FeatureCard icon={History} title="Retained history">
          Approvals, lock events, findings, comments, and correction records persist
          with the audit, so the prior period is available for inspection.
        </FeatureCard>
        <FeatureCard icon={FileSpreadsheet} title="Reporting from records">
          Summaries, snapshots, and exports derive from stored records rather than
          from a separately maintained spreadsheet.
        </FeatureCard>
        <FeatureCard icon={Scale} title="Independent review inside the org">
          Reviewer and management roles are separated from the roles that prepare
          the data, so preparation and review are distinct activities.
        </FeatureCard>
      </div>
    </Section>

    <Section tone="muted" labelledBy="not-heading">
      <SectionHeading
        id="not-heading"
        title="What CarbonFlow does not do"
        description="These boundaries are the reason CarbonFlow can be used underneath an assurance engagement without conflicting with it."
      />
      <div className="mt-8 max-w-3xl">
        <ComparisonList items={DOES_NOT} />
      </div>
    </Section>

    <Section labelledBy="global-heading">
      <SectionHeading
        id="global-heading"
        title="Global by construction, not by claim"
        description="CarbonFlow does not target a single market. It also does not quietly assume one."
      />
      <div className="mt-10 grid gap-10 lg:grid-cols-2 lg:items-start">
        <div>
          <ComparisonList items={GLOBALITY_POINTS} />
        </div>
        <div className="rounded-xl border border-line bg-surface p-5">
          <h3 className="text-sm font-semibold text-ink-950">What this means in practice</h3>
          <p className="mt-2 text-sm leading-relaxed text-ink-500">
            Two organizations in different jurisdictions use the same platform with
            the same behaviour. Differences between them are expressed as tenant
            data — country, currency, consolidation approach, base year — and as
            reference data the tenant can reason about, rather than as branches
            compiled into the product. If you need a specific framework's
            requirement set modelled for you, that is configuration and process
            work on your side; CarbonFlow will not pretend to have done it.
          </p>
        </div>
      </div>
    </Section>

    <CtaBand
      title="Prepare the work, keep the history"
      body="Register your organization to open a workspace, or sign in if your organization has already been approved."
    />
  </>
);