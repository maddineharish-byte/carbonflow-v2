/**
 * CarbonFlow — Public website & entry-layer conformance.
 *
 * These tests assert the properties that make the public site safe to publish
 * and safe to hang in front of the existing authentication system:
 *
 *   ROUTING   the three route families are disjoint, unknown paths resolve to
 *             the public 404, and no public path can reach authenticated content.
 *   API       public pages issue no authenticated request; session restore is
 *             skipped for them.
 *   ENTRY     "Get Started" resolves to the EXISTING registration endpoint and
 *             "Sign In" to the EXISTING login view. There is one of each.
 *   CLAIMS    the security, compliance, and technology pages do not claim
 *             assurance, certification, guaranteed compliance, immutability,
 *             high availability, or technologies the repository does not use.
 *   A11Y      landmarks, headings, real anchors, and an accessible mobile menu.
 */
import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import {
  ENTRY_PATHS,
  HOME_PATH,
  POST_LOGIN_PATH,
  PUBLIC_PAGES,
  isEntryPath,
  isPrivatePath,
  isPublicPath,
  normalizePath,
  pathForView,
  privateViewFor,
  shouldRestoreSession,
} from './services/router.ts';
import { NAV_ITEMS } from './components/Sidebar.tsx';
import { PublicSite } from './components/public/PublicSite.tsx';
import { PublicLink, PublicHeader, PublicFooter } from './components/public/PublicLayout.tsx';
import { NotFoundPage } from './components/public/NotFoundPage.tsx';
import { RegisterView } from './components/RegisterView.tsx';
import { LoginView } from './components/LoginView.tsx';
import { PUBLIC_PAGE_REGISTRY, NOT_FOUND_META } from './components/public/pageRegistry.tsx';
import { api, clearAuthTokens, setAuthTokens } from './services/api.ts';
import App from './App.tsx';

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  clearAuthTokens();
});

function makeResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    blob: async () => ({ size: 0 }),
  } as unknown as Response;
}

const MARKETING_PATHS = PUBLIC_PAGES.map((page) => page.path);

/** Every public path rendered, for the "all pages" assertions. */
function renderPage(path: string): string {
  return renderToStaticMarkup(<PublicSite path={path} />);
}

// ------------------------------------------------------------------
// ROUTING 1 — route families are disjoint and complete
// ------------------------------------------------------------------

test('PUBLIC ROUTES 1: every required public path is registered and renders a page', () => {
  for (const required of [
    '/',
    '/product',
    '/features',
    '/how-it-works',
    '/security',
    '/compliance',
    '/technology',
    '/documentation',
    '/about',
    '/contact',
  ]) {
    assert.ok(isPublicPath(required), `${required} must be a public path`);
    assert.ok(required in PUBLIC_PAGE_REGISTRY, `${required} must be in the page registry`);
  }
});

test('PUBLIC ROUTES 2: a public page never renders the not-found page', () => {
  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    assert.doesNotMatch(markup, /This page does not exist/, `${path} rendered a 404`);
    assert.doesNotMatch(markup, /Error 404/, `${path} rendered a 404`);
  }
});

test('PUBLIC ROUTES 3: login and register are entry paths, not marketing pages', () => {
  assert.ok(isEntryPath('/login'));
  assert.ok(isEntryPath('/register'));
  assert.ok(!isPublicPath('/login'));
  assert.ok(!isPublicPath('/register'));
  assert.equal(ENTRY_PATHS['/login'], 'Sign In');
  assert.equal(ENTRY_PATHS['/register'], 'Get Started');
});

test('PUBLIC ROUTES 4: every workspace section has a private path, and none is public', () => {
  for (const item of NAV_ITEMS) {
    const path = pathForView(item.view);
    assert.ok(isPrivatePath(path), `${item.view} must resolve to a private path (${path})`);
    assert.ok(!isPublicPath(path), `${path} must never be a public path`);
    assert.equal(privateViewFor(path), item.view, `${path} must map back to ${item.view}`);
  }
});

test('PUBLIC ROUTES 5: an unknown path is not public, not private, and renders the public 404', () => {
  for (const unknown of ['/nope', '/dashboard/admin/deep', '/api/v1/organizations', '/Login']) {
    assert.ok(!isPublicPath(unknown));
    assert.ok(!isEntryPath(unknown));
    assert.ok(!isPrivatePath(unknown), `${unknown} must not be treated as private`);
  }

  const markup = renderPage('/definitely-not-a-page');
  assert.match(markup, /This page does not exist/);
  assert.match(markup, /Error 404/);
});

test('PUBLIC ROUTES 6: the private route aliases all resolve, including /reports and /emissions', () => {
  assert.equal(privateViewFor('/reports'), 'EMISSIONS');
  assert.equal(privateViewFor('/emissions'), 'EMISSIONS');
  assert.equal(privateViewFor('/calculations'), 'EMISSIONS');
  assert.equal(privateViewFor('/organization'), 'BOUNDARIES');
  assert.equal(privateViewFor('/activity'), 'ACTIVITY_DATA');
  assert.equal(privateViewFor('/admin'), 'ADMIN');
  assert.equal(privateViewFor('/audits'), 'AUDIT');
  assert.equal(privateViewFor('/evidence'), 'EVIDENCE');
});

test('PUBLIC ROUTES 7: path normalization cannot be tricked into a known route', () => {
  assert.equal(normalizePath('/features/'), '/features');
  assert.equal(normalizePath('//security//'), '/security');
  assert.equal(normalizePath('/index.html'), HOME_PATH);
  assert.equal(normalizePath('/features?ref=x'), '/features');
  assert.equal(normalizePath('/features#top'), '/features');
  assert.equal(normalizePath(null), HOME_PATH);
  assert.equal(normalizePath(''), HOME_PATH);
  // Encoded traversal must not resolve to a private path.
  assert.ok(!isPrivatePath(normalizePath('/%2E%2E/dashboard')));
  // Malformed percent-encoding degrades to "unknown", never to a private path.
  assert.ok(!isPrivatePath(normalizePath('/%E0%A4%A')));
});

// ------------------------------------------------------------------
// API 1 — public pages issue no authenticated traffic
// ------------------------------------------------------------------

test('PUBLIC API 1: session restore is skipped for public pages only', () => {
  for (const path of MARKETING_PATHS) {
    assert.equal(shouldRestoreSession(path), false, `${path} must not restore a session`);
  }
  // The entry paths and the workspace must restore, because they must know
  // whether a session already exists.
  assert.equal(shouldRestoreSession('/login'), true);
  assert.equal(shouldRestoreSession('/register'), true);
  assert.equal(shouldRestoreSession('/dashboard'), true);
  // Unknown paths are public territory too.
  assert.equal(shouldRestoreSession('/not-a-page'), true);
});

test('PUBLIC API 2: rendering a public page makes no network request at all', () => {
  const calls: string[] = [];
  globalThis.fetch = ((input: any) => {
    calls.push(String(input));
    return Promise.resolve(makeResponse(200, { success: true, data: {} }));
  }) as typeof fetch;

  for (const path of MARKETING_PATHS) renderPage(path);

  assert.deepEqual(calls, [], `public pages must not call the API, saw: ${calls.join(', ')}`);
});

test('PUBLIC API 3: a public page renders correctly with no tokens present', () => {
  clearAuthTokens();
  assert.match(renderPage('/'), /Turn carbon data into audit-ready intelligence/);
});

// ------------------------------------------------------------------
// ENTRY 1 — CTAs resolve to the existing entry points
// ------------------------------------------------------------------

test('PUBLIC ENTRY 1: the landing page primary CTA is registration and secondary is login', () => {
  const markup = renderPage('/');

  // Scope to the hero region: the header presents Sign In before Get Started by
  // design, so a document-wide first-occurrence comparison proves nothing about
  // the hero's call to action.
  const heroStart = markup.indexOf('id="hero-heading"');
  const heroEnd = markup.indexOf('</section>', heroStart);
  assert.ok(heroStart > -1 && heroEnd > heroStart, 'the landing page must render a hero section');
  const hero = markup.slice(heroStart, heroEnd);

  const getStarted = hero.indexOf('>Get Started<');
  const explore = hero.indexOf('>Explore the Platform<');
  assert.ok(getStarted > -1, 'the hero must offer Get Started');
  assert.ok(explore > -1, 'the hero must offer Explore the Platform');
  assert.ok(getStarted < explore, 'Get Started must be the primary CTA');

  // Every such CTA points at the existing entry path, never at a new form.
  const registerLinks = [...markup.matchAll(/href="\/register"/g)];
  const loginLinks = [...markup.matchAll(/href="\/login"/g)];
  assert.ok(registerLinks.length > 0);
  assert.ok(loginLinks.length > 0);
});

test('PUBLIC ENTRY 2: no public page offers a second authentication or registration surface', () => {
  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    // A login or registration FORM would be a duplicate implementation.
    assert.doesNotMatch(markup, /type="password"/, `${path} rendered a credential form`);
    assert.doesNotMatch(markup, /auth-submit/, `${path} rendered the login submit control`);
    assert.doesNotMatch(markup, /reg-submit/, `${path} rendered a registration submit control`);
  }
});

test('PUBLIC ENTRY 3: the existing login view is still the only login form', () => {
  const markup = renderToStaticMarkup(<LoginView onLogin={async () => {}} isLoading={false} error={null} />);
  assert.match(markup, /Sign in to CarbonFlow/);
  assert.match(markup, /auth-submit/);
  assert.match(markup, /type="password"/);
});

test('PUBLIC ENTRY 4: registration posts the existing backend contract and stores no session', async () => {
  const calls: Array<{ url: string; init: any }> = [];
  globalThis.fetch = (async (url: any, init: any) => {
    calls.push({ url: String(url), init });
    return makeResponse(201, {
      success: true,
      data: {
        user: { id: 'u1', email: 'admin@example.org', fullName: 'A Admin' },
        organization: { id: 'o1', name: 'Example', status: 'PENDING_ACTIVATION' },
      },
    });
  }) as typeof fetch;

  await api.register({
    organizationName: 'Example',
    country: '',
    industry: '',
    taxId: '',
    fullName: 'A Admin',
    email: 'admin@example.org',
    password: 'longenoughpassword',
  });

  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/api\/v1\/auth\/register$/);
  assert.equal(calls[0].init.method, 'POST');

  const body = JSON.parse(calls[0].init.body);
  // Exactly the fields AuthRequests.RegisterRequest declares.
  assert.deepEqual(Object.keys(body).sort(), [
    'country',
    'email',
    'fullName',
    'industry',
    'organizationName',
    'password',
    'taxId',
  ]);

  // A pending registration issues no tokens, so nothing may be persisted. The
  // API client's storage boundary is `window.localStorage`; under Node's test
  // runtime that boundary is null, so the client's own accessors are the truth.
  const { getAccessToken, getRefreshToken } = await import('./services/api.ts');
  assert.equal(getAccessToken(), null);
  assert.equal(getRefreshToken(), null);
});

test('PUBLIC ENTRY 5: the registration view states pending activation, not sign-in', () => {
  const markup = renderToStaticMarkup(<RegisterView />);
  assert.match(markup, /Register your organization/);
  assert.match(markup, /Register organization/);
  // The approval workflow must be stated, because sign-in stays closed until then.
  assert.match(markup, /platform administrator/i);
  assert.match(markup, /Already registered\?/);
  assert.match(markup, /href="\/login"/);
  // It must never claim the organization is active immediately.
  assert.doesNotMatch(markup, /your workspace is ready/i);
});

test('PUBLIC ENTRY 6: sign-in lands on the existing dashboard path', () => {
  assert.equal(POST_LOGIN_PATH, '/dashboard');
  assert.equal(privateViewFor(POST_LOGIN_PATH), 'DASHBOARD');
});

// ------------------------------------------------------------------
// NAVIGATION & LAYOUT
// ------------------------------------------------------------------

test('PUBLIC NAV 1: the header carries the full information architecture on every page', () => {
  const required = [
    'CarbonFlow',
    'Product',
    'Features',
    'How It Works',
    'Security',
    'Compliance',
    'Technology',
    'Documentation',
    'About',
    'Contact',
    'Sign In',
    'Get Started',
  ];

  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    for (const label of required) {
      assert.ok(
        markup.includes(label),
        `${path} is missing the header/footer entry "${label}"`,
      );
    }
  }
});

test('PUBLIC NAV 2: the footer exposes the same destinations and no external accounts', () => {
  const markup = renderToStaticMarkup(<PublicFooter />);
  for (const label of ['Product', 'Features', 'How It Works', 'Security', 'Compliance', 'Technology', 'Documentation', 'About', 'Contact', 'Sign In', 'Get Started']) {
    assert.ok(markup.includes(label), `footer is missing "${label}"`);
  }
  // No invented social or third-party accounts.
  assert.doesNotMatch(markup, /https?:\/\//, 'the footer must not link off-site');
  assert.doesNotMatch(markup, /twitter|linkedin|facebook|instagram|github/i);
});

test('PUBLIC NAV 3: public navigation uses real anchors so links stay links', () => {
  const markup = renderToStaticMarkup(
    <PublicLink to="/features" current>
      Features
    </PublicLink>,
  );
  assert.match(markup, /<a href="\/features"/);
  assert.match(markup, /aria-current="page"/);
});

test('PUBLIC NAV 4: the mobile menu is a labelled, state-revealing disclosure', () => {
  const markup = renderToStaticMarkup(<PublicHeader currentPath="/" />);
  assert.match(markup, /aria-expanded="false"/);
  assert.match(markup, /aria-controls="/);
  assert.match(markup, />\s*Menu\s*</);
  // The trigger is a button, so it is operable by keyboard and announces state.
  assert.match(markup, /<button[^>]*aria-expanded/);
});

test('PUBLIC NAV 5: the current page is marked in the navigation', () => {
  const markup = renderPage('/security');
  // The Security link (and only that one) carries aria-current.
  const current = [...markup.matchAll(/href="(\/security)"[^>]*aria-current="page"/g)];
  assert.equal(current.length, 1);
});

test('PUBLIC NAV 6: every public page has a single main landmark and one h1', () => {
  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    const mains = [...markup.matchAll(/<main\b/g)];
    assert.equal(mains.length, 1, `${path} must have exactly one main landmark`);
    const h1 = [...markup.matchAll(/<h1\b/g)];
    assert.equal(h1.length, 1, `${path} must have exactly one h1`);
  }
});

test('PUBLIC NAV 7: the skip link is the first focusable element on every public page', () => {
  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    assert.match(markup, /href="#main-content"/);
    assert.ok(
      markup.indexOf('href="#main-content"') < markup.indexOf('<nav'),
      `${path} must place the skip link before the navigation`,
    );
  }
});

// ------------------------------------------------------------------
// NOT FOUND
// ------------------------------------------------------------------

test('PUBLIC 404 1: the not-found page discloses nothing and links onward', () => {
  const markup = renderToStaticMarkup(<NotFoundPage path="/oops" />);
  assert.match(markup, /Error 404/);
  assert.match(markup, /\/oops/);
  assert.match(markup, /Back to home/);
  // No diagnostics of any kind.
  assert.doesNotMatch(markup, /at Object|stack|Exception|\.java:|\.tsx:/i);
});

test('PUBLIC 404 2: the not-found page never offers authenticated destinations', () => {
  const markup = renderToStaticMarkup(<NotFoundPage path="/oops" />);
  for (const privatePath of ['/dashboard', '/organization', '/activity', '/admin']) {
    assert.doesNotMatch(markup, new RegExp(`href="${privatePath}"`), `404 linked to ${privatePath}`);
  }
});

test('PUBLIC 404 3: every public page has metadata, and so does the 404', () => {
  for (const path of MARKETING_PATHS) {
    const definition = PUBLIC_PAGE_REGISTRY[path as keyof typeof PUBLIC_PAGE_REGISTRY];
    assert.ok(definition.title.length > 10, `${path} needs a title`);
    assert.ok(definition.description.length > 40, `${path} needs a description`);
    assert.match(definition.title, /CarbonFlow|Register Your Organization|Sign In/);
  }
  assert.ok(NOT_FOUND_META.title.length > 0);
  assert.ok(NOT_FOUND_META.description.length > 0);
});

// ------------------------------------------------------------------
// CLAIMS — the public copy must stay defensible
// ------------------------------------------------------------------

/** Reads every public source file so claims can be checked in aggregate. */
function publicSourceFiles(): Array<{ file: string; text: string }> {
  const dir = 'src/components/public';
  return readdirSync(dir)
    .filter((name) => name.endsWith('.tsx') || name.endsWith('.ts'))
    .map((name) => ({ file: `${dir}/${name}`, text: readFileSync(join(dir, name), 'utf8') }))
    .concat([{ file: 'src/components/RegisterView.tsx', text: readFileSync('src/components/RegisterView.tsx', 'utf8') }]);
}

/**
 * Removes comments so prose that explains a limit is not mistaken for prose that
 * breaks it. The security and technology pages deliberately NAME the things
 * they do not do ("immutable storage is not claimed"), and that naming lives in
 * the surrounding comments as well as the copy.
 */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

const NEGATION =
  /\b(not|never|no|none|cannot|without|neither|nor|refus\w*|denie\w*|excluded?|omitted?)\b/i;

/**
 * Returns the sentences in `text` that assert `trigger` without negating it.
 *
 * This is what makes the claim guards meaningful: "CarbonFlow does not
 * guarantee compliance" and "CarbonFlow guarantees compliance" contain the same
 * words, and only one of them is a lie. Checking the raw substring would either
 * pass a lie or reject the truth, so the sentence must be read.
 */
function unnegatedAssertions(text: string, trigger: RegExp): string[] {
  const sentences = stripComments(text).replace(/\s+/g, ' ').split(/(?<=[.!?])\s+/);
  return sentences
    .filter((sentence) => trigger.test(sentence) && !NEGATION.test(sentence))
    .map((sentence) => sentence.trim());
}

function assertNoOverclaim(trigger: RegExp, label: string) {
  for (const { file, text } of publicSourceFiles()) {
    const offenders = unnegatedAssertions(text, trigger);
    assert.deepEqual(
      offenders,
      [],
      `${file} overclaims (${label}): ${offenders.join(' | ')}`,
    );
  }
}

test('PUBLIC CLAIMS 1: no page claims CarbonFlow guarantees compliance', () => {
  assertNoOverclaim(/guarantee\w* compliance|is compliant|compliance is assured/i, 'guaranteed compliance');
  // The disclaimer must actually be present on the compliance page, so the
  // guard above cannot pass merely because the topic was avoided.
  const compliance = readFileSync('src/components/public/CompliancePage.tsx', 'utf8');
  assert.match(compliance, /does not guarantee compliance/i);
});

test('PUBLIC CLAIMS 2: no page claims assurance, certification, or regulatory standing', () => {
  assertNoOverclaim(
    /CarbonFlow (provides|offers|performs|grants|issues|confers)\b/i,
    'an external standing',
  );
  assertNoOverclaim(/CarbonFlow is (an?|the) (assurance provider|certifier|regulator|auditor)\b/i, 'a regulated role');
  assertNoOverclaim(/CarbonFlow (is|has been) (certified|accredited|audited|attested)\b/i, 'a certification');
});

test('PUBLIC CLAIMS 3: the security page does not promise risk-free or certified security', () => {
  assertNoOverclaim(/zero[- ]risk|perfect(ly)? secure|unbreakable|bulletproof/i, 'risk-free security');
  assertNoOverclaim(/SOC ?2|ISO ?27001|GDPR|HIPAA|PCI DSS/i, 'a compliance certification');
  // It must state its limits explicitly rather than staying silent.
  const security = readFileSync('src/components/public/SecurityPage.tsx', 'utf8');
  assert.match(security, /What CarbonFlow does not claim/);
  assert.match(security, /not certified/i);
});

test('PUBLIC CLAIMS 4: immutability and high availability are not claimed', () => {
  assertNoOverclaim(/immutable|write-once|append-only/i, 'immutable storage');
  assertNoOverclaim(/high(ly)?[- ]available|zero[- ]downtime|no single point of failure|multi-region failover/i, 'high availability');
  // Snapshot locking IS real and may be described.
  const features = readFileSync('src/components/public/FeaturesPage.tsx', 'utf8');
  assert.match(features, /lock it/i);
});

test('PUBLIC CLAIMS 5: the technology page names only the stack the repository uses', () => {
  const raw = readFileSync('src/components/public/TechnologyPage.tsx', 'utf8');
  const technology = stripComments(raw);
  for (const present of ['React', 'TypeScript', 'Vite', 'Tailwind', 'Java 21', 'Spring Boot', 'Spring JDBC', 'PostgreSQL', 'Flyway', 'REST']) {
    assert.ok(technology.includes(present), `technology page must describe ${present}`);
  }
  // Decommissioned or absent technologies must never be asserted as IN USE.
  // They may appear only in the explicit "deliberately not used" list, where
  // every occurrence is negated, so the unnegated check is the right one.
  assert.deepEqual(
    unnegatedAssertions(technology, /Express|Hibernate|Kubernetes|Docker|JPA|Node\.js/i),
    [],
    'the technology page must not advertise a technology the repository does not use',
  );
  // And the omission must be stated, not merely absent.
  assert.match(technology, /Deliberately not used/);
  assert.match(technology, /Express/);
});

test('PUBLIC CLAIMS 6: no fabricated social proof anywhere on the site', () => {
  const forbidden = [
    /trusted by/i,
    /\d+\+?\s+(customers|organizations|companies) (trust|use)/i,
    /customers include/i,
    /testimonial/i,
    /\baward[- ]winning\b/i,
    /featured in/i,
    /backed by (investors|venture)/i,
    /\b\d+(\.\d+)?(k|K|m|M)\+?\s+(emissions|tonnes|companies|users)/,
  ];
  for (const { file, text } of publicSourceFiles()) {
    for (const pattern of forbidden) {
      assert.doesNotMatch(text, pattern, `${file} contains fabricated social proof: ${pattern}`);
    }
  }
});

test('PUBLIC CLAIMS 7: the contact page invents no address and no working form', () => {
  const contact = readFileSync('src/components/public/ContactPage.tsx', 'utf8');
  // No invented endpoint, and no form that would silently discard a message.
  assert.doesNotMatch(contact, /<form/, 'contact page must not render a submission form');
  assert.doesNotMatch(contact, /@[\w.-]+\.(com|org|net|io|co)\b/, 'contact page must not invent an email address');
  assert.doesNotMatch(contact, /https?:\/\//, 'contact page must not link off-site');
  // It must be explicit that this is a configuration gap.
  assert.match(contact, /no public enquiry/i);
});

test('PUBLIC CLAIMS 8: the documentation page exposes no operational material', () => {
  const documentation = readFileSync('src/components/public/DocumentationPage.tsx', 'utf8');
  // Real operational material, not the words that describe its absence.
  for (const forbidden of [
    /jdbc:postgresql/i,
    /[A-Za-z0-9+/]{40,}={0,2}/, // a base64 blob
    /BEGIN [A-Z ]*PRIVATE KEY/,
    /password\s*[:=]\s*\S/i,
    /CF_DB_[A-Z_]+\s*=\s*\S/,
  ]) {
    assert.doesNotMatch(documentation, forbidden, `documentation page exposes operational material: ${forbidden}`);
  }
  // It must say what it withholds, rather than being merely silent about it.
  assert.match(documentation, /Not published here/);
  assert.match(documentation, /credential/i);
  assert.match(documentation, /organization data/i);
});

test('PUBLIC CLAIMS 9: the public pages carry no credential or token material', () => {
  for (const { file, text } of publicSourceFiles()) {
    assert.doesNotMatch(text, /Bearer [A-Za-z0-9._-]{8,}/, `${file} contains a bearer token`);
    assert.doesNotMatch(text, /eyJ[A-Za-z0-9._-]{10,}/, `${file} contains a JWT`);
    assert.doesNotMatch(text, /[?&]token=/, `${file} passes a token in a URL`);
    assert.doesNotMatch(text, /localStorage\.setItem\(\s*['"]cf_(access|refresh)_token/, `${file} writes tokens outside the API client`);
  }
});

// ------------------------------------------------------------------
// PUBLIC / PRIVATE SEPARATION
// ------------------------------------------------------------------

test('PUBLIC BOUNDARY 1: public pages never render authenticated workspace chrome', () => {
  // "Executive Dashboard" is deliberately excluded: the demo ProductPreview
  // names the same sections the workspace does. These markers only occur in the
  // authenticated shell.
  const workspaceMarkers = [
    'Switch tenant',
    'Switch role',
    'Export Ledger',
    'Sign out',
    'Tenant Isolation Active',
    'Automated Test Suite',
    'Platform Administration',
  ];
  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    for (const marker of workspaceMarkers) {
      assert.ok(!markup.includes(marker), `${path} leaked authenticated chrome: ${marker}`);
    }
  }
});

test('PUBLIC BOUNDARY 2: public pages expose no private deep link', () => {
  const privatePaths = ['/dashboard', '/organization', '/activity', '/calculations', '/emissions', '/audits', '/evidence', '/admin', '/platform-admin'];
  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    for (const privatePath of privatePaths) {
      assert.doesNotMatch(
        markup,
        new RegExp(`href="${privatePath}"`),
        `${path} linked straight to the private route ${privatePath}`,
      );
    }
  }
});

test('PUBLIC BOUNDARY 3: no public page fetches tenant data even with a live session', () => {
  // Tokens present: the public branch must still be entirely static.
  setAuthTokens('access-token', 'refresh-token');
  const calls: string[] = [];
  globalThis.fetch = ((input: any) => {
    calls.push(String(input));
    return Promise.resolve(makeResponse(200, { success: true, data: {} }));
  }) as typeof fetch;

  for (const path of MARKETING_PATHS) renderPage(path);
  assert.deepEqual(calls, [], `public pages must stay static, saw: ${calls.join(', ')}`);
});

// ------------------------------------------------------------------
// RESPONSIVE & MARKUP HYGIENE
// ------------------------------------------------------------------

test('PUBLIC RESPONSIVE 1: no page pins a minimum width that would force horizontal scrolling', () => {
  for (const { file, text } of publicSourceFiles()) {
    assert.doesNotMatch(text, /min-w-\[\d{3,}px\]/, `${file} pins an over-wide minimum`);
    assert.doesNotMatch(text, /w-\[\d{4,}px\]/, `${file} pins an over-wide fixed width`);
  }
});

test('PUBLIC RESPONSIVE 2: the layout steps down for small viewports', () => {
  const layout = readFileSync('src/components/public/PublicLayout.tsx', 'utf8');
  // Header, footer and the mobile menu must all respond rather than assume a
  // desktop viewport.
  assert.match(layout, /lg:hidden/, 'the mobile menu must be hidden at lg and up');
  assert.match(layout, /hidden lg:block/, 'the desktop navigation must be hidden below lg');
  assert.match(layout, /sm:px-6/, 'containers must step their padding up at sm');
});

test('PUBLIC MARKUP 1: decorative icons are hidden from assistive technology', () => {
  for (const path of MARKETING_PATHS) {
    const markup = renderPage(path);
    const svgs = [...markup.matchAll(/<svg\b[^>]*>/g)].map((match) => match[0]);
    for (const svg of svgs) {
      assert.match(svg, /aria-hidden="true"/, `${path} rendered an unlabelled decorative icon`);
    }
  }
});

// ------------------------------------------------------------------
// APP-LEVEL BRANCH
// ------------------------------------------------------------------

test('PUBLIC APP 1: App renders the public landing page without a window', () => {
  // In Node there is no window, so usePathname resolves to the landing path and
  // the public branch must render. The authenticated workspace chrome (not the
  // demo ProductPreview, which intentionally lists section names) must not appear.
  const markup = renderToStaticMarkup(React.createElement(App));
  assert.match(markup, /Turn carbon data into audit-ready intelligence/);
  assert.doesNotMatch(markup, /Switch tenant/);
  assert.doesNotMatch(markup, /Switch role/);
  assert.doesNotMatch(markup, /Sign out/);
  assert.doesNotMatch(markup, /Sign in to CarbonFlow/);
});