/**
 * CarbonFlow — Public Technology page.
 *
 * Describes the architecture as it is in the repository. The "deliberately not
 * used" section exists because a technology page that only lists strengths is
 * marketing; the omissions are the more informative half and are stated
 * explicitly so a prospective integrator is not misled.
 *
 * Specifically NOT claimed anywhere on this page: Node/Express, JPA/Hibernate,
 * Docker, Kubernetes. The former was decommissioned, and the latter three are
 * not part of this repository.
 */

import React from 'react';
import { Box, Database, FileCode2, Server, ShieldCheck, Workflow } from 'lucide-react';
import { ComparisonList, CtaBand, PageHeader, Section, SectionHeading } from './PublicUI.tsx';

const LAYERS: Array<{
  icon: React.ComponentType<{ className?: string }>;
  layer: string;
  stack: string;
  body: string;
}> = [
  {
    icon: FileCode2,
    layer: 'Frontend',
    stack: 'React · TypeScript · Vite · Tailwind CSS',
    body: 'A single-page application built with React and TypeScript, bundled by Vite and styled with Tailwind CSS. The frontend performs presentation and formatting only — no business calculation runs in the browser.',
  },
  {
    icon: Server,
    layer: 'API',
    stack: 'Java 21 · Spring Boot 3 · REST / JSON',
    body: 'A stateless REST API over JSON. The frontend talks to it through a single API client, and every response uses one envelope shape so success and failure are handled the same way everywhere.',
  },
  {
    icon: ShieldCheck,
    layer: 'Security',
    stack: 'Spring Security · JWT · BCrypt',
    body: 'A stateless Spring Security filter chain. Access tokens are signed JWTs, refresh tokens are single-use and rotated, passwords are BCrypt hashed, and endpoints declare the permission they require.',
  },
  {
    icon: Database,
    layer: 'Persistence',
    stack: 'Spring JDBC · PostgreSQL',
    body: 'Persistence is plain JDBC through Spring JDBC — explicitly no ORM. SQL is written and reviewed directly, which is what makes tenant-scoped queries and the referential integrity constraints auditable.',
  },
  {
    icon: Workflow,
    layer: 'Schema',
    stack: 'Flyway · versioned migrations',
    body: 'The schema is created and evolved by versioned Flyway migrations applied automatically at startup. The migration files are the single source of truth and are exercised against a real PostgreSQL instance on every test run.',
  },
];

const DESIGN_DECISIONS: Array<{ title: string; body: string }> = [
  {
    title: 'No ORM, by decision',
    body: 'CarbonFlow uses JdbcTemplate rather than JPA/Hibernate. Queries that must be tenant-scoped are easier to verify by reading the SQL than by reasoning about generated statements, and the schema constraints do the integrity work the ORM would otherwise be asked to do.',
  },
  {
    title: 'The calculation engine is isolated and deterministic',
    body: 'Decimal arithmetic is exact and repeatable: the same inputs and the same effective factor version always produce the same result. Nothing in the engine reads the clock, a random source, or a mutable global.',
  },
  {
    title: 'The backend is the only source of truth',
    body: 'Reference data, permission decisions, lifecycle status, and every reported figure come from the API. The frontend renders what it is given, which is why an empty period renders as empty.',
  },
  {
    title: 'Static frontend, explicit API boundary',
    body: 'The frontend is built to static assets and reads its API origin from the build environment, defaulting to same-origin so it can sit behind a reverse proxy. No API origin is hardcoded.',
  },
  {
    title: 'Hermetic integration tests',
    body: 'Integration tests run against a real PostgreSQL server started from bundled binaries in a temporary directory. No container runtime is required, and the migration SQL is proven on every test run rather than assumed.',
  },
];

const NOT_USED: string[] = [
  'No Node.js or Express backend. The earlier Node gateway has been decommissioned; the Java service is the API.',
  'No JPA or Hibernate. Persistence is explicit SQL through Spring JDBC.',
  'No Docker and no Kubernetes. Neither container orchestration nor image-based deployment is part of this repository.',
  'No ORM-managed schema evolution. Schema changes go through reviewed, versioned Flyway migrations.',
  'No client-side business logic. The browser renders; the backend decides.',
];

export const TechnologyPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="Technology"
        title="What CarbonFlow is actually built from"
        lede="CarbonFlow is a React and TypeScript frontend over a Java 21 / Spring Boot REST API, with PostgreSQL as the system of record and Flyway owning the schema. There is no second backend and no second data store."
      />
    </Section>

    <Section tone="muted" labelledBy="layers-heading">
      <SectionHeading
        id="layers-heading"
        title="The stack, layer by layer"
        description="Each layer is a single responsibility, and the boundary between layers is where most of the platform's guarantees live."
      />
      <ul className="mt-10 space-y-4">
        {LAYERS.map((layer) => (
          <li key={layer.layer} className="rounded-xl border border-line bg-surface p-5 sm:p-6">
            <div className="flex items-start gap-4">
              <span
                aria-hidden="true"
                className="hidden h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-brand-700 sm:inline-flex"
              >
                <layer.icon className="h-5 w-5" />
              </span>
              <div className="min-w-0">
                <h3 className="text-sm font-bold uppercase tracking-wider text-ink-950">{layer.layer}</h3>
                <p className="mt-1 text-sm font-medium text-brand-700">{layer.stack}</p>
                <p className="mt-3 text-sm leading-relaxed text-ink-500">{layer.body}</p>
              </div>
            </div>
          </li>
        ))}
      </ul>
    </Section>

    <Section labelledBy="decisions-heading">
      <SectionHeading
        id="decisions-heading"
        title="Design decisions behind the stack"
        description="The technology choices are only interesting because of what they make possible."
      />
      <div className="mt-10 grid gap-4 sm:gap-5 sm:grid-cols-2">
        {DESIGN_DECISIONS.map((decision) => (
          <div key={decision.title} className="rounded-xl border border-line bg-surface p-5">
            <Box aria-hidden="true" className="h-4 w-4 text-brand-700" />
            <h3 className="mt-3 text-base font-semibold text-ink-950">{decision.title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-ink-500">{decision.body}</p>
          </div>
        ))}
      </div>
    </Section>

    <Section tone="muted" labelledBy="not-used-heading">
      <SectionHeading
        id="not-used-heading"
        title="Deliberately not used"
        description="Stated explicitly so that nobody has to infer the stack from silence."
      />
      <div className="mt-8 max-w-3xl">
        <ComparisonList items={NOT_USED} />
      </div>
    </Section>

    <CtaBand
      title="Try it against real data"
      body="Register your organization to open a workspace, or sign in if your organization has already been approved."
    />
  </>
);