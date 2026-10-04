/**
 * CarbonFlow — Public Contact page.
 *
 * There is no public contact-submission endpoint in CarbonFlow's backend, and
 * none was invented for this page. A form that silently discards a message, or
 * an invented email address that reaches nobody, is worse than an honest page
 * that states what is configured and what is not.
 *
 * What this page therefore does:
 *   - routes the visitor to the two channels that definitely exist (registration
 *     and sign-in, handled entirely by the platform itself),
 *   - documents, as a visible configuration note, what a deployment operator
 *     must supply before a real enquiry channel can be offered.
 *
 * It exposes no address, phone number, or external account, because none is
 * configured in this repository.
 */

import React from 'react';
import { AlertCircle, Building2, FileQuestion, Mail, Wrench } from 'lucide-react';
import { PublicLink, primaryButton, secondaryButton } from './PublicLayout.tsx';
import { FeatureCard, PageHeader, Section, SectionHeading, cardGrid } from './PublicUI.tsx';

const EXISTING_CHANNELS = [
  {
    icon: Building2,
    title: 'Register your organization',
    body: 'Registration is the platform\'s own onboarding channel. It creates your organization in a pending state for review and needs no correspondence with us.',
    action: { label: 'Get Started', to: '/register' },
  },
  {
    icon: Mail,
    title: 'Sign in to an existing workspace',
    body: 'If your organization has already been approved, sign in directly. Access problems for an existing organization start with a platform administrator reviewing that organization.',
    action: { label: 'Sign In', to: '/login' },
  },
  {
    icon: FileQuestion,
    title: 'Find the answer first',
    body: 'Most questions are answered on the documentation, product, security, and compliance pages, which describe the platform as it is actually implemented.',
    action: { label: 'Documentation', to: '/documentation' },
  },
];

const REQUIRED_CONFIGURATION = [
  'A monitored enquiry address for the deployment, owned by whoever operates this CarbonFlow instance.',
  'A working submission mechanism, or an explicit decision to direct enquiries to the address above by other means.',
  'A named responder and a stated response expectation, so the published channel is one somebody is actually accountable for.',
];

export const ContactPage: React.FC = () => (
  <>
    <Section>
      <PageHeader
        eyebrow="Contact"
        title="Contact CarbonFlow"
        lede="This CarbonFlow deployment has no public enquiry address or enquiry form configured. Rather than publish a contact channel that reaches nobody, this page routes you to the channels that exist and states plainly what an operator must configure."
      />
    </Section>

    <Section tone="muted" labelledBy="channels-heading">
      <SectionHeading
        id="channels-heading"
        title="How to reach us today"
        description="Registration and sign-in are handled by the platform itself and need no correspondence at all."
      />
      <div className={`mt-10 ${cardGrid}`}>
        {EXISTING_CHANNELS.map((channel) => (
          <div key={channel.title} className="flex flex-col rounded-xl border border-line bg-surface p-5">
            <FeatureCard icon={channel.icon} title={channel.title}>
              {channel.body}
            </FeatureCard>
            <div className="mt-4">
              <PublicLink
                to={channel.action.to}
                className="inline-flex items-center text-sm font-semibold text-brand-700 transition hover:text-brand-800"
              >
                {channel.action.label}
                <span aria-hidden="true" className="ml-1.5">
                  &rarr;
                </span>
              </PublicLink>
            </div>
          </div>
        ))}
      </div>
    </Section>

    <Section labelledBy="config-heading">
      <div className="grid gap-10 lg:grid-cols-2 lg:items-start">
        <div>
          <SectionHeading
            id="config-heading"
            title="Configuration required before a public enquiry channel exists"
            description="These are deployment responsibilities. They are listed so that nobody mistakes an absent address for an oversight in the product."
          />
          <ol className="mt-8 space-y-3">
            {REQUIRED_CONFIGURATION.map((item, index) => (
              <li key={item} className="flex items-start gap-3 rounded-lg border border-line bg-surface px-4 py-3">
                <span
                  aria-hidden="true"
                  className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-brand-50 text-[11px] font-bold text-brand-700"
                >
                  {index + 1}
                </span>
                <span className="text-sm leading-relaxed text-ink-700">{item}</span>
              </li>
            ))}
          </ol>
        </div>

        <div className="rounded-xl border border-amber-200 bg-amber-50 p-5">
          <div className="flex items-center gap-2">
            <AlertCircle className="h-4 w-4 shrink-0 text-amber-300" aria-hidden="true" />
            <h2 className="text-sm font-semibold text-amber-800">Why there is no form here</h2>
          </div>
          <p className="mt-3 text-sm leading-relaxed text-ink-700">
            CarbonFlow's backend exposes no public contact or enquiry endpoint. A
            submission form was therefore not added, because a form with no
            server-side destination would collect a visitor's message and discard
            it — which is worse than having no form, because it appears to work.
          </p>
          <p className="mt-3 text-sm leading-relaxed text-ink-700">
            Until an operator configures a destination, use registration for
            onboarding and sign-in for access to an existing organization.
          </p>
        </div>
      </div>
    </Section>

    <Section tone="muted" labelledBy="questions-heading">
      <SectionHeading
        id="questions-heading"
        title="Common questions answered without a support ticket"
        description="These come up often enough to be worth stating on the page rather than answering repeatedly."
      />
      <div className="mt-10 grid gap-4 sm:gap-5 sm:grid-cols-2">
        <FeatureCard icon={Wrench} title="Why can I not sign in after registering?">
          Registration creates the organization in a pending state and issues no
          credentials. Sign-in stays closed until a platform administrator reviews
          and activates it.
        </FeatureCard>
        <FeatureCard icon={Building2} title="Who approves my organization?">
          Approval is a platform-level action, separate from company
          administration. A company administrator manages users inside their own
          organization and cannot approve that organization.
        </FeatureCard>
        <FeatureCard icon={FileQuestion} title="Does CarbonFlow certify or assure?">
          No. CarbonFlow is a data and workflow management platform. It does not
          provide assurance, certification, or regulatory advice, and it does not
          guarantee compliance.
        </FeatureCard>
        <FeatureCard icon={Mail} title="Can I reach support by email?">
          Not yet on this deployment. No monitored enquiry address is configured,
          and none is published here rather than risk directing you to an address
          nobody reads.
        </FeatureCard>
      </div>
    </Section>

    <section aria-labelledby="contact-cta-heading" className="border-t border-line bg-sand-100/60 py-16 sm:py-20">
      <div className="mx-auto flex w-full max-w-7xl flex-col gap-6 px-4 sm:px-6 lg:flex-row lg:items-center lg:justify-between lg:px-8">
        <h2 id="contact-cta-heading" className="max-w-2xl text-2xl font-bold tracking-tight text-ink-950 sm:text-3xl">
          The fastest way to get started does not involve contacting us.
        </h2>
        <div className="flex flex-col gap-3 sm:flex-row lg:shrink-0">
          <PublicLink to="/register" className={`${primaryButton} sm:px-6 sm:py-3`}>
            Get Started
          </PublicLink>
          <PublicLink to="/login" className={`${secondaryButton} sm:px-6 sm:py-3`}>
            Sign In
          </PublicLink>
        </div>
      </div>
    </section>
  </>
);