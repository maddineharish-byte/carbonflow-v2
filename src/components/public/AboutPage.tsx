/**
 * CarbonFlow — Public About page.
 *
 * Describes only what is verifiable from the product itself. There is no
 * founder story, no funding history, no customer list, no logo wall, no award,
 * and no traction figure here, because CarbonFlow does not have verifiable
 * claims of that kind and inventing them would be the single most damaging thing
 * this page could do.
 *
 * Everything asserted below is observable in the software.
 */

import React from 'react';
import { Building2, FileCode2, FolderLock, Gauge, Globe2, ShieldCheck, Target } from 'lucide-react';
import { ComparisonList, CtaBand, FeatureCard, PageHeader, Section, SectionHeading, cardGrid } from './PublicUI.tsx';

const PROBLEM_POINTS: string[] = [
  'Greenhouse gas information is spread across spreadsheets, inboxes, and shared drives, so it cannot be reviewed as one body of work.',
  'A reported figure and the document that supports it are stored apart, and the link between them is a person\'s memory.',
  'When a question arrives after the reporting period has closed, there is often no way to reconstruct who verified what, or against which version of the data.',
  'Audit preparation restarts each year instead of continuing from a structured position with open findings already tracked.',
  'Multiple spreadsheets in the same organization disagree, and reconciling them is itself a manual project.',
];

const PRINCIPLES: Array<{
  title: string;
  body: string;
  icon?: React.ComponentType<{ className?: string }>;
}> = [
  {
    title: 'Evidence before assertion',
    icon: FolderLock,
    body: 'A number should be accompanied by the document it came from. CarbonFlow treats the link between a figure and its source as part of the record, not as a convenience.',
  },
  {
    title: 'Gates instead of reminders',
    body: 'Preconditions are enforced by the process rather than left to memory: a correction cannot be requested without a finding, and an audit cannot reach audit-ready with unresolved high-severity findings.',
    icon: ShieldCheck,
  },
  {
    title: 'No fabricated numbers',
    icon: Gauge,
    body: 'An empty reporting period renders as empty. CarbonFlow does not generate demonstration emissions or placeholder statistics to make a workspace look populated.',
  },
  {
    title: 'Explain, not just reproduce',
    icon: FileCode2,
    body: 'A calculation keeps the factor version and GWP values it used, so a past result can be explained to a reviewer rather than merely recalculated.',
  },
  {
    title: 'Honest boundaries',
    body: 'CarbonFlow is not an assurance provider, a certifier, a regulator, or a legal adviser, and it does not guarantee compliance. Saying so plainly is more useful than implying capability it does not have.',
  },
  {
    title: 'No assumed jurisdiction',
    body: 'No country, currency, or timezone is hardcoded into the platform. Differences between organizations are tenant data, not compiled-in assumptions.',
    icon: Globe2,
  },
];

export const AboutPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="About"
        title="Organizational carbon management, treated as an operational problem"
        lede="CarbonFlow exists to make an organization's greenhouse gas information a managed, reviewable body of work — with its evidence attached, its review recorded, and its history retained — rather than a reporting exercise reconstructed from documents each year."
      />
    </Section>

    <Section tone="muted" labelledBy="problem-heading">
      <SectionHeading
        id="problem-heading"
        title="The problem being addressed"
        description="Carbon accounting is rarely an arithmetic problem. It is an information problem, and the arithmetic is the easy part."
      />
      <div className="mt-8 max-w-3xl">
        <ComparisonList items={PROBLEM_POINTS} />
      </div>
    </Section>

    <Section labelledBy="principles-heading">
      <SectionHeading
        id="principles-heading"
        title="What the platform is built on"
        description="Six commitments that are visible in how the software behaves, not in how it is described."
      />
      <div className={`mt-10 ${cardGrid}`}>
        {PRINCIPLES.map((principle) => (
          <FeatureCard
            key={principle.title}
            icon={principle.icon ?? Gauge}
            title={principle.title}
          >
            {principle.body}
          </FeatureCard>
        ))}
      </div>
    </Section>

    <Section tone="muted" labelledBy="scope-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-center">
        <div>
          <SectionHeading
            id="scope-heading"
            title="Scoped to the organization"
            description="CarbonFlow manages greenhouse gas information at the level of the reporting entity, because that is the level at which consolidation, boundaries, and external accountability are defined."
          />
          <div className="mt-6">
            <p className="text-sm leading-relaxed text-ink-500">
              It covers the path from activity data and its evidence through
              calculation, review, findings, corrective action, reporting, and the
              retained history of all of it — then carries that position into the
              next period and against reduction targets.
            </p>
          </div>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <FeatureCard icon={Building2} title="One entity at a time">
            Organizations, facilities, departments, and periods are modelled
            explicitly rather than assumed.
          </FeatureCard>
          <FeatureCard icon={Target} title="Measured against intent">
            Reduction targets and projects are measured against reported
            positions rather than tracked in isolation.
          </FeatureCard>
          <FeatureCard icon={Globe2} title="No assumed jurisdiction">
            Country, currency, and consolidation approach are tenant data. The
            same platform behaves identically for every organization.
          </FeatureCard>
          <FeatureCard icon={ShieldCheck} title="Governed before usable">
            A newly registered organization is reviewed and activated by a
            platform administrator before any account in it can sign in.
          </FeatureCard>
        </div>
      </div>
    </Section>

    <Section labelledBy="honesty-heading">
      <SectionHeading
        id="honesty-heading"
        title="What this page deliberately does not claim"
        description="CarbonFlow's About page contains no founders, investors, customers, partnerships, awards, certifications, or revenue figures, because none of those are verifiable from the product and an unverified claim would undermine every true claim beside it."
      />
      <div className="mt-8 max-w-3xl rounded-xl border border-line bg-surface p-5">
        <p className="text-sm leading-relaxed text-ink-500">
          What can be verified is the software itself: what it does, how it is
          built, and what it explicitly does not claim. Those are described across
          the product, features, security, compliance, and technology pages, and
          each statement on those pages was checked against the implementation
          before it was written.
        </p>
      </div>
    </Section>

    <CtaBand
      title="Build your position on evidence"
      body="Register your organization to open a workspace, or sign in if your organization has already been approved."
    />
  </>
);