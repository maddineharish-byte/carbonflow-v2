/**
 * CarbonFlow — Public Features page.
 *
 * Every capability below is verified against the implementation before being
 * advertised. Where the platform records something partially — audit history is
 * held in specific governance tables rather than in one consolidated event
 * ledger — the page says exactly that rather than implying more.
 *
 * Section groups follow the approved workspace terminology in `Sidebar.tsx`
 * (asserted by `accessibility.test.tsx`, A11Y 27) so the public site and the
 * authenticated application use one vocabulary.
 */

import React from 'react';
import {
  Archive,
  BarChart3,
  Building2,
  Calculator,
  Database,
  FileCheck2,
  FileSpreadsheet,
  FolderLock,
  Layers,
  ListChecks,
  ShieldCheck,
  Target,
} from 'lucide-react';
import { cardSurface, CtaBand, FeatureCard, PageHeader, Section, SectionHeading, cardGrid } from './PublicUI.tsx';

const ORGANIZATION = [
  {
    title: 'Organization & boundary configuration',
    body: 'Maintain the organization profile and consolidation approach, and define the legal entities, departments, facilities, and boundary rules that activity data must resolve against.',
  },
  {
    title: 'Reporting periods',
    body: 'Create and manage named reporting periods with explicit start and end dates. Activity, calculations, emissions, and snapshots are all resolved against a period rather than a calendar assumption.',
  },
  {
    title: 'Multiple organizations per user',
    body: 'A user assigned to more than one organization holds a membership per organization, and can switch the active organization and role from within the workspace.',
  },
];

const DATA_AND_CALCULATION = [
  {
    title: 'Activity data collection',
    body: 'Log activity records against a facility, department, period, and scope, with unit handling and an explicit submission step before a record may feed a calculation.',
  },
  {
    title: 'Emission factors & GWP sets',
    body: 'Reference factor catalogues with versioned factor entries and global warming potential sets, resolved by the calculation engine as effective-dated versions.',
  },
  {
    title: 'Deterministic calculations',
    body: 'Run a single activity through the calculation engine, or batch-run a whole reporting period. Decimal arithmetic is exact and repeatable, and each run freezes its own snapshot.',
  },
  {
    title: 'Calculation snapshots',
    body: 'A calculation stores the factor version and GWP values it actually used, so a past result can be explained rather than merely re-run.',
  },
];

const EMISSIONS = [
  {
    title: 'Emission records & ledger',
    body: 'Calculated results are written as emission records with supersession handled by the database, giving a ledger rather than a single mutable total.',
  },
  {
    title: 'Scope 1',
    body: 'Direct emissions are captured as Scope 1 activity and calculated into their own record lines.',
  },
  {
    title: 'Scope 2 — location and market',
    body: 'Scope 2 is maintained as two distinct lines, location-based and market-based, and presented as a dual total rather than one silently aggregated figure.',
  },
  {
    title: 'Inventory snapshots',
    body: 'Generate a per-period snapshot of the emission inventory and lock it, fixing a reporting position that later periods are compared against.',
  },
];

const GOVERNANCE = [
  {
    title: 'Evidence vault',
    body: 'Upload supporting documents with size and MIME validation and a magic-byte check. Each file is hashed with SHA-256 over the stored bytes and kept in versions.',
  },
  {
    title: 'Evidence linking',
    body: 'Link evidence to the activity, calculation, or other record it supports, so a reviewer can move from a number to its source document.',
  },
  {
    title: 'Carbon audit workflow',
    body: 'A governed state machine moves an audit through submission, data collection, validation, review, approval, audit-ready, and lock, with each transition requiring a specific permission.',
  },
  {
    title: 'Verification checklists',
    body: 'Audit checklist items are verified individually, with the satisfaction state and reviewer notes recorded against the audit.',
  },
  {
    title: 'Review findings',
    body: 'Raise a finding against an audit, record its severity and detail, and resolve it explicitly. A correction request cannot be opened without a logged finding.',
  },
  {
    title: 'Corrective actions',
    body: 'Correction requests are tracked as their own records with a status, and an audit returns to data collection once corrections are requested.',
  },
];

const ANALYSIS_AND_REPORTING = [
  {
    title: 'Executive dashboard',
    body: 'Period-over-period emissions, both Scope 2 perspectives, audit health, and the current audit cycle status.',
  },
  {
    title: 'Analytics & breakdowns',
    body: 'Period summaries and dimension breakdowns computed from stored records, plus trend insights derived from the same data.',
  },
  {
    title: 'Reporting & CSV export',
    body: 'Export the emission inventory ledger as CSV, filtered by period, facility, scope, and Scope 2 method.',
  },
  {
    title: 'Targets & reduction projects',
    body: 'Record carbon targets against a base year and track reduction projects, with progress computed from measured positions rather than restated by hand.',
  },
];

const GOVERNANCE_OF_ACCESS = [
  {
    title: 'Role-based access control',
    body: 'Nine roles mapped to a frozen permission matrix. Workspace sections are gated on the permission codes the authenticated session returns.',
  },
  {
    title: 'Platform administration',
    body: 'A separate platform role reviews each newly registered organization and records approval, rejection, or suspension before any user can sign in.',
  },
  {
    title: 'Recorded governance history',
    body: 'Review activity is persisted where it is auditable: checklist verification, audit approvals, lock events, findings, comments, corrections, and evidence versions. CarbonFlow does not present a single consolidated event ledger across every entity.',
  },
];

const OPERATIONS = [
  {
    title: 'Backup and recovery controls',
    body: 'Operational controls cover scheduled database and evidence-vault backups, backup encryption, retention, verification, health monitoring, and periodic recovery drills with recovery point and recovery time validation.',
  },
  {
    title: 'Automated test suite',
    body: 'A privileged in-platform test suite exercises the calculation and reporting paths and reports pass and fail counts, so the platform can be checked by the people who operate it.',
  },
];

interface CapabilityGroupProps {
  id: string;
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  description: string;
  items: Array<{ title: string; body: string }>;
}

const CapabilityGroup: React.FC<CapabilityGroupProps> = ({ id, icon: Icon, title, description, items }) => (
  <Section labelledBy={`${id}-heading`}>
    <div className="flex items-start gap-4">
      <span
        aria-hidden="true"
        className="mt-1 hidden h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-brand-700 sm:inline-flex"
      >
        <Icon className="h-5 w-5" />
      </span>
      <div className="max-w-3xl">
        <SectionHeading id={`${id}-heading`} title={title} description={description} />
      </div>
    </div>
    <div className={`mt-10 ${cardGrid}`}>
      {items.map((item) => (
        <div key={item.title} className={cardSurface}>
          <h3 className="text-base font-semibold text-ink-950">{item.title}</h3>
          <p className="mt-2 text-sm leading-relaxed text-ink-500">{item.body}</p>
        </div>
      ))}
    </div>
  </Section>
);

export const FeaturesPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="Features"
        title="What the CarbonFlow workspace actually does"
        lede="CarbonFlow is an operational system for greenhouse gas information, not a dashboard over someone else's data. The capabilities below are the ones implemented in the platform, described in the approved workspace terminology."
      />
    </Section>

    <CapabilityGroup
      id="organization"
      icon={Building2}
      title="Organization Management"
      description="The organizational frame every other record resolves against: who the reporting entity is, what it consolidates, and which places and periods it covers."
      items={ORGANIZATION}
    />

    <CapabilityGroup
      id="data"
      icon={Database}
      title="Carbon Data, Activity Data & Calculations"
      description="The measurement core: what was consumed or emitted, under which factor version, with the inputs frozen alongside the result."
      items={DATA_AND_CALCULATION}
    />

    <CapabilityGroup
      id="emissions"
      icon={Calculator}
      title="Emissions & Inventory"
      description="Results are kept as records with distinct scope lines rather than one recomputed total, and can be fixed into a locked per-period inventory position."
      items={EMISSIONS}
    />

    <CapabilityGroup
      id="governance"
      icon={FileCheck2}
      title="Evidence, Audits, Findings & Corrective Actions"
      description="The preparation layer. It is where an organization's own review happens, before any external party is engaged."
      items={GOVERNANCE}
    />

    <Section tone="muted" labelledBy="compliance-heading">
      <SectionHeading
        id="compliance-heading"
        title="Compliance tracking"
        description="CarbonFlow supports compliance work by holding the information a reporting obligation refers to in a structured, reviewable state. It is not a rules engine, and it does not decide whether an obligation has been met."
      />
      <div className="mt-10 grid gap-4 sm:gap-5 sm:grid-cols-2 lg:grid-cols-4">
        <FeatureCard icon={ListChecks} title="Requirements as checklist items">
          Audit checklist items carry a satisfaction state and a reviewer note, so
          the state of each requirement is a recorded fact.
        </FeatureCard>
        <FeatureCard icon={FileCheck2} title="Open items surfaced, not buried">
          A correction request cannot be opened until a finding is logged, and an
          audit cannot reach audit-ready with unresolved high-severity findings.
        </FeatureCard>
        <FeatureCard icon={Archive} title="Audit history retained">
          Approvals, lock events, findings, comments, and correction records are
          persisted and read back as part of the audit record.
        </FeatureCard>
        <FeatureCard icon={FolderLock} title="Evidence for every claim">
          A figure can be traced to a hashed, versioned source document within
          the same tenant.
        </FeatureCard>
      </div>
      <div className="mt-8 max-w-3xl rounded-xl border border-line bg-surface p-5">
        <h3 className="text-sm font-semibold text-ink-950">What this is not</h3>
        <p className="mt-2 text-sm leading-relaxed text-ink-500">
          CarbonFlow does not evaluate a jurisdiction's rules, does not map them
          to obligations, and does not assert that a reporting requirement has
          been satisfied. Those judgements stay with the organization and with
          whoever it engages to review the work. CarbonFlow's role is to keep the
          evidence, the state, and the history.
        </p>
      </div>
    </Section>

    <CapabilityGroup
      id="reporting"
      icon={BarChart3}
      title="Reporting & Analytics"
      description="Everything published is derived from the stored records, so a figure in a report can be traced back to the calculation that produced it."
      items={ANALYSIS_AND_REPORTING}
    />

    <CapabilityGroup
      id="access"
      icon={ShieldCheck}
      title="Audit history & access governance"
      description="Who may see and change what, and what review activity is retained afterwards."
      items={GOVERNANCE_OF_ACCESS}
    />

    <CapabilityGroup
      id="operations"
      icon={Layers}
      title="Recovery & operational assurance"
      description="Platform-side controls that protect the data an organization has invested in building. These operate at the deployment level rather than inside the workspace."
      items={OPERATIONS}
    />

    <Section labelledBy="factor-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-center">
        <div>
          <SectionHeading
            id="factor-heading"
            title="No invented figures, anywhere"
            description="CarbonFlow does not generate demonstration emissions, sample customers, or placeholder statistics. A workspace with no data shows an explicit empty state instead of a fabricated number."
          />
        </div>
        <div className={`${cardGrid} lg:grid-cols-2`}>
          <FeatureCard icon={Database} title="Real records only">
            The dashboard renders what the backend returns. An empty reporting
            period renders as empty, not as a default.
          </FeatureCard>
          <FeatureCard icon={FileSpreadsheet} title="Derived reporting">
            Summaries, trends, and exports are computed from stored records, not
            restated in the browser.
          </FeatureCard>
          <FeatureCard icon={Target} title="Progress from measurement">
            Target progress is computed from the recorded baseline and measured
            positions.
          </FeatureCard>
          <FeatureCard icon={ListChecks} title="Verifiable test suite">
            A privileged test suite runs the calculation and reporting paths on
            demand and reports real counts.
          </FeatureCard>
        </div>
      </div>
    </Section>

    <CtaBand
      title="See it against your own reporting process"
      body="Register your organization to open a workspace, or sign in if your organization has already been approved."
    />
  </>
);