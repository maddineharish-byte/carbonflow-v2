/**
 * CarbonFlow — Public Security page.
 *
 * Every control described here was read out of the implementation before it was
 * written down. Where CarbonFlow does NOT do something, the page says so rather
 * than staying silent, because an unqualified security page is worse than none:
 *
 *   - No certification, accreditation, or regulatory attestation is claimed.
 *   - No high-availability or multi-region topology is claimed; none is
 *     implemented in this repository.
 *   - Storage is not described as immutable or write-once. Evidence records are
 *     versioned and hash-verified, and inventory snapshots can be locked, but
 *     deletion endpoints exist for evidence by design.
 *   - HSTS is not claimed. `SecurityHeadersFilter` deliberately omits it because
 *     the application runs over HTTP in development; TLS termination and HSTS
 *     are deployment responsibilities.
 */

import React from 'react';
import {
  Database,
  FileKey2,
  Fingerprint,
  KeyRound,
  Layers,
  ScrollText,
  ShieldCheck,
  UserCheck,
} from 'lucide-react';
import { ComparisonList, CtaBand, FeatureCard, PageHeader, Section, SectionHeading, cardGrid } from './PublicUI.tsx';

const CONTROLS = [
  {
    icon: KeyRound,
    title: 'Authentication',
    body: 'Password authentication is served by the Spring Security filter chain. Passwords are stored as BCrypt hashes at cost 10 and are never returned by any endpoint. Failed sign-in attempts are throttled.',
  },
  {
    icon: FileKey2,
    title: 'Token handling',
    body: 'Access tokens are short-lived signed JWTs. Refresh tokens are single-use, cryptographically random, stored as keyed hashes, rotated on every use, and revoked as a family on logout. Tokens travel in the Authorization header only — never in a URL or query string.',
  },
  {
    icon: ScrollText,
    title: 'Input validation',
    body: 'Request bodies are validated by bean validation on the server. Errors are returned in a single response envelope with a stable error code and a message that does not disclose internals.',
  },
  {
    icon: ShieldCheck,
    title: 'Security headers',
    body: 'Every response carries X-Content-Type-Options: nosniff, X-Frame-Options: SAMEORIGIN, Referrer-Policy: strict-origin-when-cross-origin, and X-XSS-Protection. HSTS is intentionally not set by the application and belongs to TLS termination.',
  },
];

const NOT_CLAIMED = [
  'CarbonFlow is not certified, accredited, or audited against any security framework, and it makes no compliance or regulatory attestation about itself.',
  'CarbonFlow does not claim zero risk, unbroken security, or immunity to attack. It describes controls it implements, and the residual risk that remains with any software system.',
  'Storage is not immutable or write-once. Evidence is versioned and hash-verified and inventory snapshots can be locked, but evidence deletion is a supported operation and a database administrator can alter any row.',
  'No high-availability, multi-region, or zero-downtime topology is implemented in this repository. Availability depends on the deployment.',
  'Transport encryption for browser traffic is a deployment responsibility: the application sets no HSTS header and terminates TLS, if at all, in front of itself.',
];

export const SecurityPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="Security"
        title="How CarbonFlow protects access and data"
        lede="CarbonFlow is a multi-tenant system holding commercially sensitive and often confidential information, so its security model is built around three boundaries: who may authenticate, what a signed-in user may do, and whose data any request may touch."
      />
    </Section>

    <Section tone="muted" labelledBy="controls-heading">
      <SectionHeading
        id="controls-heading"
        title="Access controls"
        description="Authentication establishes an identity. Authorization decides what that identity may do. Tenant context decides whose data the request may read. All three are enforced on the server."
      />
      <div className={`mt-10 ${cardGrid}`}>
        {CONTROLS.map((control) => (
          <FeatureCard key={control.title} icon={control.icon} title={control.title}>
            {control.body}
          </FeatureCard>
        ))}
      </div>
    </Section>

    <Section labelledBy="rbac-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-start">
        <div>
          <SectionHeading
            id="rbac-heading"
            title="Authorization is role-based and permission-checked"
            description="CarbonFlow maps nine roles onto a frozen matrix of granular permission codes. Individual endpoints declare the permission they require, so authorization is a property of the request rather than of the screen that issued it."
          />
          <div className="mt-6 max-w-2xl">
            <FeatureCard icon={UserCheck} title="The server is the boundary">
              Hiding a workspace section because the session lacks a permission is
              a usability measure only. A user who calls the endpoint directly
              without the permission is refused, and the refusal is reported in the
              standard error envelope.
            </FeatureCard>
          </div>
          <div className="mt-4 max-w-2xl">
            <FeatureCard icon={ScrollText} title="Separation of duties">
              Approving an organization is a platform-level action, distinct from
              company administration. A company administrator manages users inside
              their own organization and cannot approve that organization.
            </FeatureCard>
          </div>
        </div>

        <div>
          <SectionHeading
            id="tenant-heading"
            title="Tenant isolation"
            description="Every request runs inside a tenant context resolved from the signed token and a verified organization membership."
          />
          <div className="mt-8">
            <ComparisonList
              items={[
                'The active organization comes from the token, never from a request parameter or header the caller controls. There is no header to spoof.',
                'Membership is verified, so a token naming an organization the user does not belong to is rejected rather than honoured.',
                'Switching organization is a server-side operation restricted to memberships already assigned to the user.',
                'Persisted records carry the organization identifier, and database constraints enforce tenant-consistent references between activity, evidence, facilities, and periods.',
                'Cross-tenant reads are not a supported operation on any endpoint.',
              ]}
            />
          </div>
        </div>
      </div>
    </Section>

    <Section tone="muted" labelledBy="integrity-heading">
      <SectionHeading
        id="integrity-heading"
        title="Evidence integrity and data protection"
        description="The point of an evidence vault is that a number can be defended later. That depends on the file being verifiably the file that was supplied."
      />
      <div className={`mt-10 ${cardGrid}`}>
        <FeatureCard icon={Fingerprint} title="SHA-256 evidence hashing">
          Every uploaded document is hashed with SHA-256 over the exact stored
          bytes, and the digest is stored on the record and on each version, so a
          later reader can confirm the file has not changed underneath them.
        </FeatureCard>
        <FeatureCard icon={Layers} title="Versioned evidence">
          Evidence is retained in versions with links to the activity,
          calculation, or other record it substantiates, so a superseded document
          does not silently replace the one a figure depended on.
        </FeatureCard>
        <FeatureCard icon={Database} title="Upload validation">
          Evidence uploads are bounded in size, restricted to an allowed MIME
          set, and checked against their own magic bytes before storage, so the
          recorded type reflects the bytes rather than the filename.
        </FeatureCard>
        <FeatureCard icon={ScrollText} title="Recorded review activity">
          Checklist verification, audit approvals, lock events, findings,
          comments, corrections, and evidence versions are persisted with the
          identity that performed them.
        </FeatureCard>
        <FeatureCard icon={Database} title="Database-level integrity">
          Tenant-consistency constraints, foreign keys, and check constraints
          enforce referential integrity at the storage layer rather than relying on
          application code alone.
        </FeatureCard>
        <FeatureCard icon={ShieldCheck} title="Structured error responses">
          Validation and authorization failures return a stable code and message.
          Internal detail is not disclosed to the caller.
        </FeatureCard>
      </div>
    </Section>

    <Section labelledBy="deployment-heading">
      <SectionHeading
        id="deployment-heading"
        title="Transport and deployment"
        description="Some controls are deliberately outside the application, and it is more useful to say where the boundary sits than to imply the application covers it."
      />
      <div className={`mt-10 ${cardGrid}`}>
        <FeatureCard icon={Layers} title="Cross-origin policy is fail-closed">
          The browser origin allow-list is environment-owned and has no default.
          A deployment that never configures it trusts no browser origin at all,
          rather than trusting every origin. A wildcard is refused outright,
          because the API sends credentials.
        </FeatureCard>
        <FeatureCard icon={Layers} title="TLS">
          The application relies on a reverse proxy or load balancer for HTTPS.
          For the database connection it validates the configured TLS mode at
          startup and distinguishes modes that verify the server certificate from
          those that merely encrypt the transport.
        </FeatureCard>
        <FeatureCard icon={Database} title="Backup and recovery controls">
          Scheduled database and evidence-vault backups, backup encryption,
          retention, verification of the produced backup set, health monitoring,
          and scheduled recovery drills with recovery point and recovery time
          validation.
        </FeatureCard>
        <FeatureCard icon={ScrollText} title="Configuration is validated at startup">
          Security-relevant configuration is asserted during startup, so a
          deployment with an unsafe or malformed setting fails to start instead of
          running in a degraded posture.
        </FeatureCard>
      </div>
    </Section>

    <Section tone="muted" labelledBy="limits-heading">
      <SectionHeading
        id="limits-heading"
        title="What CarbonFlow does not claim"
        description="An accurate security page states its limits. The following are explicitly not true of this platform."
      />
      <div className="mt-8 max-w-3xl">
        <ComparisonList items={NOT_CLAIMED} />
      </div>
    </Section>

    <CtaBand
      title="Review the architecture yourself"
      body="The technology page describes what CarbonFlow is actually built from, and the documentation page points at the material behind these claims."
    />
  </>
);