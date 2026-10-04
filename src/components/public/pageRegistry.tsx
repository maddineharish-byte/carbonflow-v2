/**
 * CarbonFlow — Public page registry.
 *
 * One table binding each public path to its component and its metadata. Keeping
 * title and description beside the route (rather than inside each page) means
 * the information architecture, the navigation, and the SEO metadata can never
 * disagree: a page cannot exist in the navigation without also existing here.
 */

import type React from 'react';
import type { PublicPagePath } from '../../services/router.ts';
import { AboutPage } from './AboutPage.tsx';
import { CompliancePage } from './CompliancePage.tsx';
import { ContactPage } from './ContactPage.tsx';
import { DocumentationPage } from './DocumentationPage.tsx';
import { FeaturesPage } from './FeaturesPage.tsx';
import { HomePage } from './HomePage.tsx';
import { HowItWorksPage } from './HowItWorksPage.tsx';
import { ProductPage } from './ProductPage.tsx';
import { SecurityPage } from './SecurityPage.tsx';
import { TechnologyPage } from './TechnologyPage.tsx';

export interface PublicPageDefinition {
  title: string;
  description: string;
  component: React.ComponentType;
}

export const PUBLIC_PAGE_REGISTRY: Readonly<Record<PublicPagePath, PublicPageDefinition>> = {
  '/': {
    title: 'CarbonFlow — Enterprise Carbon Accounting & GHG Management',
    description:
      'CarbonFlow is an enterprise greenhouse gas management platform: carbon and activity data, evidence management, deterministic calculations, audit preparation, findings and corrective actions, compliance tracking, and reporting.',
    component: HomePage,
  },
  '/product': {
    title: 'Product — CarbonFlow',
    description:
      'How the CarbonFlow platform is organised: the collect, validate, calculate, review, emissions, analyze, report, reduce and monitor lifecycle, and how CarbonFlow differs from assurance providers, certifiers, regulators and legal advisers.',
    component: ProductPage,
  },
  '/features': {
    title: 'Features — CarbonFlow',
    description:
      'The CarbonFlow workspace capabilities: organization and boundary management, activity data, versioned emission factors, deterministic calculations, Scope 1 and dual-reported Scope 2, evidence vault, audits, findings, corrective actions, reporting, targets and recovery controls.',
    component: FeaturesPage,
  },
  '/how-it-works': {
    title: 'How It Works — CarbonFlow',
    description:
      'How information moves through CarbonFlow: organization, carbon data, evidence, carbon audit, validation, findings, corrective actions, compliance tracking, audit history, and reporting.',
    component: HowItWorksPage,
  },
  '/security': {
    title: 'Security — CarbonFlow',
    description:
      'The CarbonFlow security architecture: authentication, token rotation, role-based authorization, tenant isolation, SHA-256 evidence hashing, input validation, security headers, and backup and recovery controls — including what CarbonFlow does not claim.',
    component: SecurityPage,
  },
  '/compliance': {
    title: 'Compliance — CarbonFlow',
    description:
      'How CarbonFlow supports carbon audit and compliance workflows: organizing information, maintaining evidence, tracking requirements, managing findings and corrective actions, retaining audit history, and preparing reports. CarbonFlow does not guarantee compliance.',
    component: CompliancePage,
  },
  '/technology': {
    title: 'Technology — CarbonFlow',
    description:
      'The CarbonFlow architecture: React, TypeScript, Vite and Tailwind on the frontend; Java 21, Spring Boot, Spring JDBC, PostgreSQL and Flyway on the backend, over a stateless REST API.',
    component: TechnologyPage,
  },
  '/documentation': {
    title: 'Documentation — CarbonFlow',
    description:
      'Public documentation entry point for CarbonFlow: the reference model, the API and data model, the audit workflow, and the security model, with no operational or organization data.',
    component: DocumentationPage,
  },
  '/about': {
    title: 'About — CarbonFlow',
    description:
      'Why CarbonFlow exists: organizational carbon management treated as an operational problem, with evidence attached, review recorded, and history retained.',
    component: AboutPage,
  },
  '/contact': {
    title: 'Contact — CarbonFlow',
    description:
      'How to reach CarbonFlow. Registration and sign-in are handled by the platform itself; this page also states what a deployment operator must configure before a public enquiry channel exists.',
    component: ContactPage,
  },
};

/** Metadata for the not-found page. */
export const NOT_FOUND_META = {
  title: 'Page Not Found (404) — CarbonFlow',
  description: 'The requested address is not part of the CarbonFlow website.',
} as const;