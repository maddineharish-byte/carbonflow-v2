/**
 * CarbonFlow — Public entry-layer router.
 *
 * CarbonFlow's authenticated application deliberately has no URL router: it
 * switches workspace sections by component state (`NavView`), and that behaviour
 * is left exactly as it is. What this module adds is the *public entry layer*
 * that the website needs, plus a thin URL surface for the authenticated
 * workspace so that a bookmarked private path cannot silently render public
 * content or vice versa.
 *
 * Three disjoint route families:
 *
 *   PUBLIC   /, /product, /features, /how-it-works, /security, /compliance,
 *            /technology, /documentation, /about, /contact
 *   ENTRY    /login, /register   (existing login + existing backend signup)
 *   PRIVATE  /dashboard, /organization, /activity, ... — every other known
 *            workspace path, resolved through the existing `NavView` state.
 *
 * Anything unrecognised is NOT-FOUND and never reaches the authenticated tree.
 *
 * Deliberately no router dependency: CarbonFlow ships React, TypeScript and
 * Vite, and adding a routing library would change the dependency surface of an
 * already-verified application for no benefit.
 */

import { useCallback, useEffect, useState } from 'react';
import type { NavView } from '../types.ts';

/** The two public paths that are entry points rather than marketing pages. */
export type EntryPath = '/login' | '/register';

/**
 * Canonical public marketing pages, in the order they appear in the header
 * navigation. `/` is the landing page and is listed explicitly so that the
 * information architecture has a single source of truth.
 */
export const PUBLIC_PAGES = [
  { path: '/', label: 'Home' },
  { path: '/product', label: 'Product' },
  { path: '/features', label: 'Features' },
  { path: '/how-it-works', label: 'How It Works' },
  { path: '/security', label: 'Security' },
  { path: '/compliance', label: 'Compliance' },
  { path: '/technology', label: 'Technology' },
  { path: '/documentation', label: 'Documentation' },
  { path: '/about', label: 'About' },
  { path: '/contact', label: 'Contact' },
] as const;

export type PublicPagePath = (typeof PUBLIC_PAGES)[number]['path'];

/** Entry-point paths, keyed by their role. */
export const ENTRY_PATHS: Record<EntryPath, string> = {
  '/login': 'Sign In',
  '/register': 'Get Started',
};

/**
 * Private workspace paths mapped onto the *existing* `NavView` values. These
 * are deep links into the authenticated application only; the sidebar still
 * drives the same state, so nothing about the workspace changes.
 */
const PRIVATE_PATH_TO_VIEW: Readonly<Record<string, NavView>> = {
  '/dashboard': 'DASHBOARD',
  '/organization': 'BOUNDARIES',
  '/boundaries': 'BOUNDARIES',
  '/facilities': 'BOUNDARIES',
  '/activity': 'ACTIVITY_DATA',
  '/activity-data': 'ACTIVITY_DATA',
  '/calculations': 'EMISSIONS',
  '/emissions': 'EMISSIONS',
  '/reports': 'EMISSIONS',
  '/ledger': 'EMISSIONS',
  '/factors': 'FACTORS',
  '/emission-factors': 'FACTORS',
  '/audits': 'AUDIT',
  '/audit': 'AUDIT',
  '/evidence': 'EVIDENCE',
  '/inventory': 'INVENTORY',
  '/analytics': 'ANALYTICS',
  '/targets': 'TARGETS',
  '/projects': 'TARGETS',
  '/admin': 'ADMIN',
  '/users': 'ADMIN',
  '/platform-admin': 'PLATFORM_ADMIN',
  '/test-suite': 'TEST_SUITE',
};

const PUBLIC_PATH_SET: ReadonlySet<string> = new Set(PUBLIC_PAGES.map((page) => page.path));
const ENTRY_PATH_SET: ReadonlySet<string> = new Set(Object.keys(ENTRY_PATHS));

/**
 * The canonical private path for each workspace view.
 *
 * Used when the user navigates from inside the workspace, so the address bar
 * keeps describing where they actually are. The inverse mapping above accepts
 * several aliases per view (`/reports` and `/calculations` both open the
 * calculations and ledger view) while this table names exactly one as
 * canonical, so the URL cannot oscillate.
 */
const VIEW_TO_PATH: Readonly<Record<NavView, string>> = {
  DASHBOARD: '/dashboard',
  BOUNDARIES: '/organization',
  ACTIVITY_DATA: '/activity',
  EMISSIONS: '/calculations',
  FACTORS: '/factors',
  AUDIT: '/audits',
  EVIDENCE: '/evidence',
  INVENTORY: '/inventory',
  ANALYTICS: '/analytics',
  TARGETS: '/targets',
  ADMIN: '/admin',
  PLATFORM_ADMIN: '/platform-admin',
  TEST_SUITE: '/test-suite',
};

/** The canonical private path for a workspace view. */
export function pathForView(view: NavView): string {
  return VIEW_TO_PATH[view];
}

/** The canonical landing path. */
export const HOME_PATH = '/';

/** Where a successful sign-in lands: the existing dashboard. */
export const POST_LOGIN_PATH = '/dashboard';

/**
 * Normalises a raw location path: drops any trailing slash, the `index.html`
 * suffix some static hosts serve, and collapses duplicate slashes. `null` and
 * unknown shapes degrade to the landing path rather than throwing, so a hostile
 * or malformed URL can never break rendering.
 */
export function normalizePath(raw: string | null | undefined): string {
  if (!raw) return HOME_PATH;
  let path = raw.split('?')[0].split('#')[0];
  try {
    // A path is compared as text only; decoding keeps encoded traversal
    // sequences (`%2F`, `..`) from resolving to a known private path.
    path = decodeURIComponent(path);
  } catch {
    // Malformed percent-encoding: keep the raw text, which then matches nothing.
  }
  path = path.replace(/\/{2,}/g, '/');
  if (path.endsWith('/index.html')) path = path.slice(0, -'index.html'.length);
  if (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1);
  if (!path.startsWith('/')) path = `/${path}`;
  return path === '' ? HOME_PATH : path;
}

/** True for the public marketing pages (no session, no API traffic). */
export function isPublicPath(path: string): boolean {
  return PUBLIC_PATH_SET.has(path);
}

/** True for `/login` and `/register`. */
export function isEntryPath(path: string): boolean {
  return ENTRY_PATH_SET.has(path);
}

/** The `NavView` a private path addresses, or `null` if it is not private. */
export function privateViewFor(path: string): NavView | null {
  return PRIVATE_PATH_TO_VIEW[path] ?? null;
}

/** True when the path addresses the authenticated workspace. */
export function isPrivatePath(path: string): boolean {
  return privateViewFor(path) !== null;
}

/**
 * True when the session restore effect must run.
 *
 * Public marketing pages never touch the authenticated API — that is the whole
 * point of the entry layer, and it is what keeps the landing page free of
 * private data and independent of any session. `/login`, `/register` and every
 * private path do restore, because they must know whether a session already
 * exists.
 */
export function shouldRestoreSession(path: string): boolean {
  return !isPublicPath(path);
}

/**
 * Replaces the browser path without a document load.
 *
 * `navigate` is intentionally the ONLY writer of `window.history` in the
 * application, so a public navigation can never be confused with a workspace
 * navigation.
 */
export function navigate(to: string, options: { replace?: boolean } = {}): void {
  if (typeof window === 'undefined') return;
  const next = normalizePath(to);
  const current = normalizePath(window.location.pathname);
  if (next === current) return;
  if (options.replace) {
    window.history.replaceState({}, '', next);
  } else {
    window.history.pushState({}, '', next);
  }
  window.dispatchEvent(new PopStateEvent('popstate'));
}

/**
 * Subscribes to path changes. Returns the current normalised path.
 *
 * `popstate` covers both the browser back/forward buttons and CarbonFlow's own
 * `navigate`, so there is exactly one subscription model.
 */
export function usePathname(): string {
  const [path, setPath] = useState(() =>
    typeof window === 'undefined' ? HOME_PATH : normalizePath(window.location.pathname),
  );

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const sync = () => setPath(normalizePath(window.location.pathname));
    window.addEventListener('popstate', sync);
    return () => window.removeEventListener('popstate', sync);
  }, []);

  return path;
}

/** Returns a stable `go` callback for `navigate`. */
export function useNavigate(): (to: string, options?: { replace?: boolean }) => void {
  return useCallback((to: string, options?: { replace?: boolean }) => navigate(to, options), []);
}

/**
 * Applies per-page document metadata.
 *
 * The public site is a single-document application, so the title and
 * description live in one place and are rewritten on navigation. `index.html`
 * ships the landing-page values, which keeps the very first paint (and any
 * non-JS crawler) correct before React mounts.
 */
export function usePageMeta(title: string, description: string): void {
  useEffect(() => {
    if (typeof document === 'undefined') return;

    document.title = title;

    const upsert = (selector: string, attr: 'name' | 'property', key: string, content: string) => {
      let element = document.head.querySelector<HTMLMetaElement>(selector);
      if (!element) {
        element = document.createElement('meta');
        element.setAttribute(attr, key);
        document.head.appendChild(element);
      }
      element.setAttribute('content', content);
    };

    upsert(`meta[name="description"]`, 'name', 'description', description);
    upsert(`meta[property="og:title"]`, 'property', 'og:title', title);
    upsert(`meta[property="og:description"]`, 'property', 'og:description', description);
  }, [title, description]);
}