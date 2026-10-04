/**
 * CarbonFlow — Public website layout shell.
 *
 * Owns the three landmarks every public page needs — a skip link, the site
 * header navigation, and the site footer — plus the `PublicLink` primitive that
 * performs client-side navigation while remaining a real anchor.
 *
 * Visual language: the CarbonFlow public design system — warm light surfaces,
 * deep carbon-green accents, near-black ink, Plus Jakarta Sans. Section rhythm
 * and panel treatment stay identical across every public page.
 */

import React, { useEffect, useId, useRef, useState } from 'react';
import { ChevronDown, Menu, X } from 'lucide-react';
import { navigate } from '../../services/router.ts';

/** Top-level public navigation, canonical order. */
const PRIMARY_LINKS = [
  { path: '/product', label: 'Product' },
  { path: '/features', label: 'Features' },
  { path: '/how-it-works', label: 'How It Works' },
  { path: '/security', label: 'Security' },
] as const;

const RESOURCES_LINKS = [
  { path: '/documentation', label: 'Documentation' },
  { path: '/technology', label: 'Technology' },
  { path: '/compliance', label: 'Compliance' },
] as const;

const ALL_PUBLIC_LINKS = [...PRIMARY_LINKS, ...RESOURCES_LINKS, { path: '/about', label: 'About' }, { path: '/contact', label: 'Contact' }] as const;

/** Shared shell classes so buttons never drift between pages. */
export const buttonBase =
  'inline-flex items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-semibold transition focus:outline-none active:scale-[0.98]';
export const primaryButton = `${buttonBase} bg-accent text-white hover:bg-accent-strong shadow-[0_1px_2px_rgba(20,26,22,0.12)]`;
export const secondaryButton =
  `${buttonBase} border border-line-strong bg-surface text-ink-900 hover:border-brand-400 hover:bg-brand-50/50 hover:text-brand-800`;

export interface PublicLinkProps {
  to: string;
  children: React.ReactNode;
  className?: string;
  /** Marks the link as the current page for assistive technology. */
  current?: boolean;
  onNavigate?: () => void;
  'aria-label'?: string;
}

/**
 * A real `<a href>` that navigates without a document load.
 *
 * Using an anchor rather than a `<button>` keeps middle-click, "open in new
 * tab", the browser status bar, and screen-reader link lists working, while the
 * click handler keeps the single-page transition.
 */
export const PublicLink: React.FC<PublicLinkProps> = ({
  to,
  children,
  className,
  current,
  onNavigate,
  ...rest
}) => (
  <a
    href={to}
    className={className}
    aria-current={current ? 'page' : undefined}
    onClick={(event) => {
      // Let the browser handle modified clicks (new tab/window) untouched.
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) {
        return;
      }
      event.preventDefault();
      navigate(to);
      onNavigate?.();
    }}
    {...rest}
  >
    {children}
  </a>
);

interface ResourcesMenuProps {
  currentPath: string;
}

/**
 * Resources disclosure in the desktop header.
 *
 * A real button with `aria-expanded`/`aria-controls`, operable entirely by
 * keyboard (click to toggle, Escape to close), hover also opens. The menu is
 * not modal: the page behind it stays reachable and scrollable.
 */
const ResourcesMenu: React.FC<ResourcesMenuProps> = ({ currentPath }) => {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  const hasActive = RESOURCES_LINKS.some((link) => link.path === currentPath);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    const onPointerDown = (event: PointerEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('pointerdown', onPointerDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('pointerdown', onPointerDown);
    };
  }, [open]);

  return (
    <div
      ref={containerRef}
      className="relative"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={(event) => {
        if (!containerRef.current?.contains(event.relatedTarget as Node | null)) {
          setOpen(false);
        }
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={open}
        aria-controls={menuId}
        aria-haspopup="menu"
        onClick={() => setOpen((value) => !value)}
        className={`inline-flex items-center gap-1 rounded-lg px-3 py-2 text-sm font-medium transition ${
          hasActive ? 'text-brand-700' : 'text-ink-700 hover:bg-sand-100 hover:text-ink-950'
        }`}
      >
        Resources
        <ChevronDown
          className={`h-3.5 w-3.5 transition-transform ${open ? 'rotate-180' : ''}`}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div
          id={menuId}
          role="menu"
          aria-label="Resources"
          className="absolute left-0 top-full z-50 mt-1 w-56 rounded-xl border border-line bg-surface p-1.5 shadow-[0_12px_40px_rgba(20,26,22,0.12)]"
        >
          {RESOURCES_LINKS.map((link) => (
            <PublicLink
              key={link.path}
              to={link.path}
              current={currentPath === link.path}
              onNavigate={() => setOpen(false)}
              className={`block rounded-lg px-3 py-2 text-sm transition ${
                currentPath === link.path
                  ? 'bg-brand-50 text-brand-800 font-semibold'
                  : 'text-ink-700 hover:bg-sand-100 hover:text-ink-950'
              }`}
            >
              {link.label}
            </PublicLink>
          ))}
        </div>
      )}
    </div>
  );
};

/**
 * Mobile navigation disclosure.
 *
 * A real button with `aria-expanded`/`aria-controls`, closed on Escape, closed
 * on navigation, and focus returned to the trigger on Escape. No focus trap,
 * because the menu is not modal.
 */
const MobileNav: React.FC<{ currentPath: string }> = ({ currentPath }) => {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open]);

  return (
    <div className="lg:hidden">
      <button
        ref={triggerRef}
        type="button"
        aria-expanded={open}
        aria-controls={menuId}
        onClick={() => setOpen((value) => !value)}
        className="inline-flex items-center gap-2 rounded-lg border border-line-strong bg-surface px-3 py-2 text-xs font-semibold text-ink-900 transition hover:border-brand-400"
      >
        {open ? <X className="h-4 w-4" aria-hidden="true" /> : <Menu className="h-4 w-4" aria-hidden="true" />}
        {open ? 'Close menu' : 'Menu'}
      </button>

      {open && (
        <nav
          id={menuId}
          aria-label="Public"
          className="absolute inset-x-0 top-full z-50 max-h-[calc(100vh-4rem)] overflow-y-auto border-b border-line bg-sand-50 px-4 pb-5 pt-3 shadow-xl"
        >
          <ul className="flex flex-col gap-1">
            {ALL_PUBLIC_LINKS.map((page) => (
              <li key={page.path}>
                <PublicLink
                  to={page.path}
                  current={currentPath === page.path}
                  onNavigate={() => setOpen(false)}
                  className={`block rounded-lg px-3 py-2.5 text-sm font-semibold transition ${
                    currentPath === page.path
                      ? 'bg-brand-50 text-brand-800'
                      : 'text-ink-700 hover:bg-sand-100 hover:text-ink-950'
                  }`}
                >
                  {page.label}
                </PublicLink>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex flex-col gap-2 border-t border-line pt-4">
            <PublicLink
              to="/login"
              onNavigate={() => setOpen(false)}
              className={`${secondaryButton} w-full`}
            >
              Sign In
            </PublicLink>
            <PublicLink
              to="/register"
              onNavigate={() => setOpen(false)}
              className={`${primaryButton} w-full`}
            >
              Get Started
            </PublicLink>
          </div>
        </nav>
      )}
    </div>
  );
};

export const PublicHeader: React.FC<{ currentPath: string }> = ({ currentPath }) => {
  const [elevated, setElevated] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const onScroll = () => setElevated(window.scrollY > 8);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  return (
    <header
      className={`sticky top-0 z-40 border-b bg-sand-50/85 backdrop-blur transition-shadow ${
        elevated ? 'border-line shadow-[0_6px_24px_rgba(20,26,22,0.06)]' : 'border-line'
      }`}
    >
      <div className="mx-auto flex w-full max-w-7xl items-center justify-between gap-4 px-4 py-2.5 sm:px-6 lg:px-8">
        <PublicLink
          to="/"
          className="flex shrink-0 items-center gap-2.5"
          aria-label="CarbonFlow home"
        >
          <span
            aria-hidden="true"
            className="flex h-9 w-9 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-xs font-bold tracking-wider text-brand-700"
          >
            CF
          </span>
          <span className="text-base font-bold tracking-tight text-ink-950">CarbonFlow</span>
        </PublicLink>

        <nav aria-label="Public" className="hidden lg:block">
          <ul className="flex flex-wrap items-center gap-1">
            {PRIMARY_LINKS.map((page) => (
              <li key={page.path}>
                <PublicLink
                  to={page.path}
                  current={currentPath === page.path}
                  className={`inline-block rounded-lg px-3 py-2 text-sm font-medium transition ${
                    currentPath === page.path
                      ? 'bg-brand-50 text-brand-800 font-semibold'
                      : 'text-ink-700 hover:bg-sand-100 hover:text-ink-950'
                  }`}
                >
                  {page.label}
                </PublicLink>
              </li>
            ))}
            <li>
              <ResourcesMenu currentPath={currentPath} />
            </li>
          </ul>
        </nav>

        <div className="hidden shrink-0 items-center gap-2 lg:flex">
          <PublicLink to="/login" className={secondaryButton}>
            Sign In
          </PublicLink>
          <PublicLink to="/register" className={primaryButton}>
            Get Started
          </PublicLink>
        </div>

        <MobileNav currentPath={currentPath} />
      </div>
    </header>
  );
};

const FOOTER_COLUMNS: Array<{ heading: string; links: Array<{ to: string; label: string }> }> = [
  {
    heading: 'Platform',
    links: [
      { to: '/product', label: 'Product' },
      { to: '/features', label: 'Features' },
      { to: '/how-it-works', label: 'How It Works' },
    ],
  },
  {
    heading: 'Governance',
    links: [
      { to: '/security', label: 'Security' },
      { to: '/compliance', label: 'Compliance' },
      { to: '/technology', label: 'Technology' },
    ],
  },
  {
    heading: 'Resources',
    links: [
      { to: '/documentation', label: 'Documentation' },
      { to: '/about', label: 'About' },
      { to: '/contact', label: 'Contact' },
    ],
  },
  {
    heading: 'Account',
    links: [
      { to: '/login', label: 'Sign In' },
      { to: '/register', label: 'Get Started' },
    ],
  },
];

export const PublicFooter: React.FC = () => (
  <footer className="border-t border-line bg-sand-100">
    <div className="mx-auto w-full max-w-7xl px-4 py-12 sm:px-6 lg:px-8">
      <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-5">
        <div className="lg:col-span-2">
          <div className="flex items-center gap-2.5">
            <span
              aria-hidden="true"
              className="flex h-9 w-9 items-center justify-center rounded-lg border border-brand-200 bg-brand-50 text-xs font-bold tracking-wider text-brand-700"
            >
              CF
            </span>
            <span className="text-base font-bold tracking-tight text-ink-950">CarbonFlow</span>
          </div>
          <p className="mt-4 max-w-sm text-sm leading-relaxed text-ink-700">
            Organizational greenhouse gas management: carbon data, evidence, audit
            preparation, compliance tracking, and reporting in one auditable
            workspace.
          </p>
          <p className="mt-4 text-xs leading-relaxed text-ink-500">
            CarbonFlow is a data and workflow management platform. It does not
            provide assurance, certification, or regulatory advice.
          </p>
        </div>

        {FOOTER_COLUMNS.map((column) => (
          <nav key={column.heading} aria-label={column.heading}>
            <h2 className="text-xs font-bold uppercase tracking-wider text-ink-500">
              {column.heading}
            </h2>
            <ul className="mt-4 space-y-2.5">
              {column.links.map((link) => (
                <li key={link.to}>
                  <PublicLink
                    to={link.to}
                    className="text-sm text-ink-700 transition hover:text-brand-700"
                  >
                    {link.label}
                  </PublicLink>
                </li>
              ))}
            </ul>
          </nav>
        ))}
      </div>

      <div className="mt-10 border-t border-line-strong pt-6">
        <p className="text-xs text-ink-500">
          CarbonFlow — enterprise carbon accounting and GHG management platform.
        </p>
      </div>
    </div>
  </footer>
);

export interface PublicLayoutProps {
  currentPath: string;
  children: React.ReactNode;
  /**
   * Set when a child supplies its own `<main>` landmark.
   *
   * The existing `LoginView` renders its own `<main>`, and two `main`
   * elements in one document is invalid HTML and leaves assistive technology
   * with an ambiguous landmark. When this is set the shell renders a plain
   * focusable wrapper instead of a second `main`, and the skip link targets
   * that wrapper.
   */
  childProvidesMain?: boolean;
}

/**
 * The public page shell.
 *
 * `children` is the page body. The shell supplies the document landmarks, the
 * skip link, and a single `main` region so no page has to repeat them.
 */
export const PublicLayout: React.FC<PublicLayoutProps> = ({
  currentPath,
  children,
  childProvidesMain = false,
}) => (
  <div className="flex min-h-screen flex-col bg-sand-50 text-ink-900">
    <a
      href="#main-content"
      className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-[60] focus:rounded-lg focus:bg-accent focus:px-3 focus:py-2 focus:text-xs focus:font-semibold focus:text-ink-950"
    >
      Skip to main content
    </a>
    <PublicHeader currentPath={currentPath} />
    {childProvidesMain ? (
      <div id="main-content" tabIndex={-1} className="flex flex-1 flex-col focus:outline-none">
        {children}
      </div>
    ) : (
      <main id="main-content" tabIndex={-1} className="flex-1 focus:outline-none">
        {children}
      </main>
    )}
    <PublicFooter />
  </div>
);