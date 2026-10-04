/**
 * CarbonFlow — Public landing page.
 *
 * Composition (top of page → bottom):
 *   hero (eyebrow / H1 / CTAs / platform preview)
 *   → trust strip
 *   → problem section (fragmented inputs → CarbonFlow)
 *   → interactive workflow
 *   → platform preview
 *   → feature groups
 *   → evidence section
 *   → Scope 2 explainer
 *   → governance pipeline
 *   → security (dark) banner
 *   → global section
 *   → final CTA
 *
 * Static content only: no API call, no session, no organization data.
 */

import React from 'react';
import {
  Boxes,
  Calculator,
  ChartLine,
  Database,
  FileCheck2,
  FileText,
  FolderLock,
  GitMerge,
  Globe2,
  History,
  LockKeyhole,
  ShieldCheck,
  Target,
  Users,
  Workflow,
} from 'lucide-react';
import { PublicLink, primaryButton, secondaryButton } from './PublicLayout.tsx';
import { Container, CtaBand, FeatureCard, Section, SectionHeading, cardSurface, cardGrid } from './PublicUI.tsx';
import { WorkflowExplorer } from './WorkflowExplorer.tsx';
import { ProductPreview, MiniTrendBars } from './ProductPreview.tsx';

const TRUST_ITEMS = [
  { icon: Database, label: 'Centralized carbon data' },
  { icon: FileText, label: 'Evidence-backed reporting' },
  { icon: FileCheck2, label: 'Audit-ready workflows' },
  { icon: ShieldCheck, label: 'Governed access' },
  { icon: Calculator, label: 'Traceable calculations' },
];

const WORKFLOW = [
  { label: 'Collect', detail: 'Activity records are captured against a facility, department, scope, and reporting period, then linked to the documents that support them — before any number may be calculated.' },
  { label: 'Validate', detail: 'Submissions are checked for unit consistency, boundary resolution, and evidence coverage, and moved through an explicit submission step rather than becoming calculable the moment they are typed.' },
  { label: 'Calculate', detail: 'A deterministic decimal engine resolves the effective emission factor version and GWP set, freezes the calculation snapshot, and writes the resulting emission records.' },
  { label: 'Verify / Review', detail: 'The audit state machine moves the reporting period through its governed stages, with verification checklists and recorded reviewer comments at each step.' },
  { label: 'Emissions', detail: 'Scope 1, Scope 2 location-based, and Scope 2 market-based results are kept as distinct lines, never silently aggregated into one figure.' },
  { label: 'Analyze', detail: 'Breakdowns by facility, category, scope, and period are computed from the stored records, with trend context across periods.' },
  { label: 'Report', detail: 'Reporting-period summaries, inventory snapshots, and CSV ledger exports are produced from the same underlying records that fed the calculation.' },
  { label: 'Reduce', detail: 'Carbon targets are set against a baseline, and the reduction projects intended to meet them are tracked in the same workspace.' },
  { label: 'Monitor', detail: 'Target progress is recomputed each period from measured positions, and the audit history carries forward to the next cycle.' },
];

const INPUT_LABELS = [
  'Spreadsheets',
  'Invoices',
  'Utility records',
  'Documents',
  'Emails',
  'Evidence files',
  'Manual calculations',
];

const OUTPUT_LABELS = [
  'Structured',
  'Traceable',
  'Auditable',
  'Centralized',
];

const FEATURE_GROUPS = [
  {
    heading: 'Carbon Accounting',
    items: ['Activity data collection', 'Deterministic calculations', 'Scope 1', 'Scope 2 dual reporting', 'Emission records & ledger'],
  },
  {
    heading: 'Audit & Evidence',
    items: ['Evidence vault with SHA-256 hashing', 'Governed audit workflow', 'Findings & review comments', 'Corrective actions', 'Retained audit history'],
  },
  {
    heading: 'Governance & Reporting',
    items: ['Compliance tracking as facts', 'Inventory snapshots & locks', 'Analytics & breakdowns', 'Targets & reduction projects', 'Organization & boundary management'],
  },
];

const GOVERNANCE_FLOW = [
  { label: 'Data', note: 'Submitted activity', tone: 'brand' },
  { label: 'Validation', note: 'Checklist recorded', tone: 'brand' },
  { label: 'Review', note: 'Findings logged', tone: 'amber' },
  { label: 'Approval', note: 'Permission-checked', tone: 'brand' },
  { label: 'Audit Ready', note: 'Unblocked open items', tone: 'brand' },
  { label: 'Locked', note: 'Fixed position', tone: 'brand' },
] as const;

export const HomePage: React.FC = () => (
  <>
    {/* Hero */}
    <section aria-labelledby="hero-heading" className="border-b border-line">
      <Container className="py-14 sm:py-20 lg:py-24">
        <div className="grid gap-12 lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)] lg:items-center">
          <div>
            <p className="inline-flex items-center gap-2 rounded-full border border-brand-200 bg-brand-50 px-3 py-1 text-[11px] font-bold uppercase tracking-[0.18em] text-brand-700">
              Carbon Accounting • Audit • Governance
            </p>
            <h1
              id="hero-heading"
              className="mt-6 text-4xl font-bold tracking-tight text-ink-950 sm:text-5xl"
            >
              Turn carbon data into audit-ready intelligence.
            </h1>
            <p className="mt-6 text-base leading-relaxed text-ink-700 sm:text-lg">
              CarbonFlow connects carbon data, evidence, calculations, audits, findings,
              corrective actions, and reporting in one governed workspace, so your
              numbers can be explained when it matters — not reconstructed at the last
              moment.
            </p>
            <p className="mt-4 text-sm leading-relaxed text-ink-500">
              CarbonFlow is a data and workflow platform. It does not provide assurance,
              certification, or regulatory advice.
            </p>

            <div className="mt-9 flex flex-col gap-3 sm:flex-row">
              <PublicLink to="/register" className={`${primaryButton} sm:px-6 sm:py-3`}>
                Get Started
              </PublicLink>
              <a href="#workflow" className={`${secondaryButton} sm:px-6 sm:py-3`}>
                Explore the Platform
              </a>
            </div>

            <p className="mt-6 text-xs leading-relaxed text-ink-400">
              New organizations submit a registration and are reviewed by a platform
              administrator before an account can sign in. Existing organizations sign
              in directly.
            </p>
          </div>

          <div>
            <ProductPreview />
          </div>
        </div>
      </Container>
    </section>

    {/* Trust strip */}
    <section aria-labelledby="trust-heading" className="border-b border-line bg-sand-100/60 py-8">
      <Container>
        <h2 id="trust-heading" className="sr-only">Platform assurances</h2>
        <ul className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {TRUST_ITEMS.map((item) => (
            <li key={item.label} className="flex items-center gap-3">
              <span
                aria-hidden="true"
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-brand-700"
              >
                <item.icon className="h-4 w-4" />
              </span>
              <span className="text-sm font-semibold text-ink-900">{item.label}</span>
            </li>
          ))}
        </ul>
      </Container>
    </section>

    {/* Problem */}
    <Section labelledBy="problem-heading" tone="muted">
      <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] lg:items-center">
        <div>
          <SectionHeading
            id="problem-heading"
            title="Carbon reporting fails on evidence, not on arithmetic"
            description="The calculation is rarely the hard part. What fails is the trail: a figure with no source document, a review that cannot be reconstructed, and a corrective action nobody can prove was closed."
          />
          <div className="mt-8">
            <PublicLink to="/how-it-works" className={secondaryButton}>
              See the full sequence
            </PublicLink>
          </div>
        </div>

        <div aria-label="Fragmented information today flows into one structured platform">
          <div className="rounded-2xl border border-line bg-surface p-6">
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-ink-400">Today</p>
            <ul className="mt-4 flex flex-wrap items-center gap-2">
              {INPUT_LABELS.map((item) => (
                <li
                  key={item}
                  className="rounded-lg border border-line bg-sand-50 px-3 py-1.5 text-xs font-semibold text-ink-700"
                >
                  {item}
                </li>
              ))}
            </ul>
          </div>

          <div aria-hidden="true" className="flex items-center justify-center py-4">
            <Workflow className="h-6 w-6 text-brand-500" />
          </div>

          <div className="rounded-2xl border border-brand-200 bg-brand-50/60 p-6">
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-brand-700">With CarbonFlow</p>
            <ul className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
              {OUTPUT_LABELS.map((item) => (
                <li
                  key={item}
                  className="flex items-center justify-center rounded-lg bg-white px-3 py-2 text-xs font-bold text-brand-800 shadow-[0_1px_2px_rgba(20,26,22,0.06)]"
                >
                  {item}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </Section>

    {/* Workflow */}
    <Section id="workflow" labelledBy="workflow-heading">
      <SectionHeading
        id="workflow-heading"
        title="One workflow, start to finish"
        description="CarbonFlow follows information through a fixed sequence every reporting period. Hover or focus a step to see what it contributes."
      />
      <div className="mt-10">
        <WorkflowExplorer steps={WORKFLOW} />
      </div>
    </Section>

    {/* Platform preview */}
    <Section tone="muted" labelledBy="preview-heading">
      <SectionHeading
        id="preview-heading"
        title="One workspace, one tenant, one perimeter"
        description="The authenticated application is organised as a single tenant-scoped workspace. The public site exists so visitors can see its shape before they ever sign in."
      />
      <div className="mt-10">
        <ProductPreview />
      </div>
      <div className="mt-8">
        <PublicLink to="/product" className={secondaryButton}>
          Explore CarbonFlow
        </PublicLink>
      </div>
    </Section>

    {/* Features grouped */}
    <Section labelledBy="features-heading">
      <SectionHeading
        id="features-heading"
        title="Three capability pillars"
        description="Grouped by the job they do — accounting, audit and evidence, and governance and reporting."
      />
      <div className={`mt-10 ${cardGrid}`}>
        {FEATURE_GROUPS.map((group) => (
          <div key={group.heading} className={cardSurface}>
            <h3 className="text-base font-semibold text-ink-950">{group.heading}</h3>
            <ul className="mt-4 space-y-2">
              {group.items.map((item) => (
                <li key={item} className="flex items-start gap-2 text-sm text-ink-700">
                  <span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </Section>

    {/* Evidence */}
    <Section tone="muted" labelledBy="evidence-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-center">
        <div>
          <SectionHeading
            id="evidence-heading"
            title="Evidence is why the number holds"
            description="Every document behind an activity record is uploaded to the evidence vault, validated, hashed, and versioned. A figure is only as good as the file that produced it."
          />
          <div className="mt-8">
            <PublicLink to="/security" className={secondaryButton}>
              How integrity is checked
            </PublicLink>
          </div>
        </div>
        <ol aria-label="Evidence flow" className="space-y-3">
          {[
            { label: 'Carbon Record', detail: 'Activity data submitted against a facility and period.' },
            { label: 'Evidence', detail: 'The source document uploaded and linked to the record it supports.' },
            { label: 'Hash / Integrity', detail: 'SHA-256 computed over the stored bytes, versioned with the record.' },
            { label: 'Audit Review', detail: 'The reviewer opens the linked version; the file matches the digest or it does not.' },
          ].map((step, index) => (
            <li
              key={step.label}
              className="flex items-start gap-4 rounded-xl border border-line bg-surface p-4"
            >
              <span
                aria-hidden="true"
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-[11px] font-bold text-brand-700"
              >
                {index + 1}
              </span>
              <div>
                <h3 className="text-sm font-semibold text-ink-950">{step.label}</h3>
                <p className="mt-1 text-sm leading-relaxed text-ink-500">{step.detail}</p>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </Section>

    {/* Scope 2 */}
    <Section labelledBy="scope2-heading">
      <SectionHeading
        id="scope2-heading"
        title="Scope 2: two methods, never one sum"
        description="CarbonFlow keeps location-based and market-based Scope 2 as distinct lines, presented side by side. Quietly adding them together would double-count the same energy."
      />
      <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div className={cardSurface}>
          <p className="text-xs font-bold uppercase tracking-[0.18em] text-brand-700">Location-Based</p>
          <p className="mt-3 text-sm leading-relaxed text-ink-700">
            Uses average grid emission factors for the place the energy was consumed. It
            answers: what did the grid deliver here?
          </p>
        </div>
        <div className={cardSurface}>
          <p className="text-xs font-bold uppercase tracking-[0.18em] text-brand-700">Market-Based</p>
          <p className="mt-3 text-sm leading-relaxed text-ink-700">
            Reflects contractual instruments — tariffs, certificates, supplier factors. It
            answers: what did the organization actually buy?
          </p>
        </div>
        <div className="rounded-xl border border-brand-200 bg-brand-50/60 p-5 sm:p-6">
          <div className="flex items-center gap-2">
            <GitMerge className="h-4 w-4 text-brand-700" aria-hidden="true" />
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-brand-800">Dual Reporting</p>
          </div>
          <p className="mt-3 text-sm leading-relaxed text-brand-900">
            Both lines are reported, never summed. A reader can see the same energy from
            both angles and reconcile between them.
          </p>
        </div>
      </div>
    </Section>

    {/* Governance */}
    <Section tone="muted" labelledBy="governance-heading">
      <SectionHeading
        id="governance-heading"
        title="Work moves through governed stages"
        description="The platform behaves like enterprise software: every transition requires a permission, and nothing skips a step."
      />
      <ol aria-labelledby="governance-heading" className="mt-10 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {GOVERNANCE_FLOW.map((step, index) => (
          <li
            key={step.label}
            className="rounded-xl border border-line bg-surface p-4"
          >
            <p className="flex items-center gap-2 text-sm font-bold text-ink-950">
              <span
                aria-hidden="true"
                className={`h-2 w-2 rounded-full ${
                  step.tone === 'amber' ? 'bg-amber-500' : 'bg-brand-500'
                }`}
              />
              {step.label}
            </p>
            <p className="mt-2 text-xs leading-relaxed text-ink-500">{step.note}</p>
            {index < GOVERNANCE_FLOW.length - 1 && (
              <p aria-hidden="true" className="mt-3 text-lg text-brand-300 lg:hidden">
                ↓
              </p>
            )}
          </li>
        ))}
      </ol>
    </Section>

    {/* Security banner */}
    <section aria-labelledby="security-home-heading" className="border-y border-line bg-brand-950 py-16 sm:py-20">
      <Container>
        <div className="grid gap-10 lg:grid-cols-2 lg:items-center">
          <div>
            <h2 id="security-home-heading" className="text-2xl font-bold tracking-tight text-white sm:text-3xl">
              Access is governed, tenants are isolated
            </h2>
            <p className="mt-4 text-sm leading-relaxed text-brand-100">
              Every request runs inside a tenant context resolved from the signed token and
              a verified membership. Role-based permissions decide which sections a user
              reaches; the backend enforces them, regardless of what the UI hides.
            </p>
            <div className="mt-8">
              <PublicLink
                to="/security"
                className="inline-flex items-center justify-center gap-2 rounded-lg border border-brand-700 bg-transparent px-4 py-2.5 text-sm font-semibold text-white transition hover:border-brand-400"
              >
                Read the security architecture
              </PublicLink>
            </div>
          </div>
          <ul className="grid grid-cols-2 gap-3">
            {[
              { icon: ShieldCheck, label: 'RBAC' },
              { icon: LockKeyhole, label: 'Tenant Isolation' },
              { icon: History, label: 'Audit History' },
              { icon: Target, label: 'Evidence Integrity' },
              { icon: Users, label: 'Secure Authentication' },
              { icon: Boxes, label: 'Recovery Controls' },
            ].map((item) => (
              <li
                key={item.label}
                className="flex items-center gap-3 rounded-xl border border-brand-900 bg-brand-900/60 p-4"
              >
                <item.icon className="h-4 w-4 text-brand-300" aria-hidden="true" />
                <span className="text-sm font-semibold text-white">{item.label}</span>
              </li>
            ))}
          </ul>
        </div>
      </Container>
    </section>

    {/* Global platform */}
    <Section labelledBy="global-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-center">
        <div>
          <SectionHeading
            id="global-heading"
            title="Built for any organization, anywhere"
            description="CarbonFlow does not assume a country, a currency, or a compliance framework. Differences between organizations are expressed as tenant data, not as branches in the product."
          />
        </div>
        <ol aria-label="Organization hierarchy" className="space-y-2">
          {[
            { level: 'Organizations', note: 'The reporting entity' },
            { level: 'Countries', note: 'Tenant-defined regions' },
            { level: 'Locations', note: 'Legal entities and facilities' },
            { level: 'Facilities', note: 'Resolved in the boundary model' },
            { level: 'Carbon Data', note: 'Scope, quantity, unit, evidence' },
          ].map((item, index) => (
            <li
              key={item.level}
              className="flex items-center gap-4 rounded-xl border border-line bg-surface p-4"
              style={{ marginLeft: `${index * 1.25}rem` }}
            >
              <Globe2 className="h-4 w-4 text-brand-600" aria-hidden="true" />
              <div>
                <h3 className="text-sm font-semibold text-ink-950">{item.level}</h3>
                <p className="text-xs text-ink-500">{item.note}</p>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </Section>

    {/* Style preview of trend bars for the platform section */}
    <Section tone="muted" labelledBy="trend-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-center">
        <div>
          <SectionHeading
            id="trend-heading"
            title="Trends are drawn from stored records"
            description="Period summaries and breakdowns are computed server-side from the same rows that produced the emission records. The chart below is a visual reference, not live data."
          />
        </div>
        <div className={cardSurface}>
          <p className="text-xs font-bold uppercase tracking-[0.18em] text-ink-400">Reference visual</p>
          <div className="mt-4">
            <MiniTrendBars />
          </div>
        </div>
      </div>
    </Section>

    <CtaBand
      title="Ready to bring your carbon data into one governed platform?"
      body="Register your organization to open a workspace, or sign in if your organization has already been approved."
    />
  </>
);