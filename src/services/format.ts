/**
 * Locale-aware presentation primitives (Phase 10.12 — Globalization).
 *
 * CarbonFlow is a global platform, so exactly two things are allowed to depend
 * on the reader's locale, and nothing else is:
 *
 *   1. How a NUMBER is grouped and which character separates the decimal part.
 *   2. Nothing else. There is no locale for time here, on purpose (below).
 *
 * ---------------------------------------------------------------------------
 * Why numbers are localized but instants are not
 * ---------------------------------------------------------------------------
 * Numbers are presentation only. `1234.5678` tonnes and `1.234,5678` tonnes are
 * the same quantity; only the glyphs differ, and a reader is entitled to see
 * their own convention. The stored and transmitted value never changes.
 *
 * Instants are different in kind. Every timestamp CarbonFlow holds is an
 * immutable audit fact -- "this calculation was executed", "this audit item was
 * verified", "this account was suspended". Rendering such an instant in the
 * browser's local zone means:
 *
 *   - two auditors reading the same audit trail in Berlin and Osaka see
 *     different wall-clock times for the same event, and
 *   - neither of them is told which zone they are looking at.
 *
 * For an evidence-bearing carbon accounting system that is a defensibility
 * problem, not a cosmetic one: an auditor must be able to state unambiguously
 * when an event happened. So `formatAuditInstant` renders every instant in UTC
 * with an explicit offset marker. It is locale-independent by construction,
 * which also makes it assertable in tests under any locale.
 *
 * This does NOT claim a reporting period is a UTC calendar day. Date-only
 * columns (reporting period bounds, activity start/end, factor effective dates)
 * are stored as PostgreSQL `DATE`, carried as `YYYY-MM-DD` strings, and are
 * never routed through this module -- they are reporting facts with no time
 * component and no zone. See docs/GLOBALIZATION.md.
 */

/** Locale used when the runtime exposes no usable language preference. */
const FALLBACK_LOCALE = 'en-US';

/**
 * Overrides the detected locale. Exists so tests can pin a configuration
 * deterministically; production never calls this.
 */
let localeOverride: string | null = null;

/**
 * Resolves the reader's locale from the browser preferences.
 *
 * `navigator.languages` is the ordered preference list; `navigator.language` is
 * its first entry. Server-side rendering and non-browser test environments may
 * expose neither, hence the fallback.
 */
export function resolveLocale(): string {
  if (localeOverride) return localeOverride;
  const nav = (globalThis as { navigator?: Navigator }).navigator;
  const preferred = nav?.languages?.length ? nav.languages[0] : nav?.language;
  return preferred || FALLBACK_LOCALE;
}

/** Test hook. Pass `null` to return to browser detection. */
export function setLocaleForTests(locale: string | null): void {
  localeOverride = locale;
}

const formatterCache = new Map<string, Intl.NumberFormat>();

function formatter(locale: string, digits: number): Intl.NumberFormat {
  const key = `${locale}#${digits}`;
  let cached = formatterCache.get(key);
  if (!cached) {
    cached = new Intl.NumberFormat(locale, {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
      useGrouping: true,
    });
    formatterCache.set(key, cached);
  }
  return cached;
}

/**
 * Formats a quantity with a fixed number of fraction digits, using the
 * reader's grouping and decimal separators.
 *
 * Replaces `Number.prototype.toFixed`, which always emits `.` and never groups,
 * so a de-DE reader previously saw `1234.5678` and `1.234,5678` on the same
 * screen. Digit count is preserved exactly: `formatQuantity(x, 4)` and
 * `x.toFixed(4)` round identically, so no displayed precision changes here.
 *
 * @param value  the quantity; `null`/`undefined`/`NaN` yield an em dash so a
 *               missing measurement is visibly absent rather than shown as 0.
 */
export function formatQuantity(
  value: number | null | undefined,
  digits = 4,
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  return formatter(resolveLocale(), digits).format(value);
}

/** Groups an integer quantity (activity quantity, floor area, gas mass). */
export function formatInteger(value: number | null | undefined): string {
  return formatQuantity(value, 0);
}

/** Formats a percentage from a fraction or an already-scaled percentage. */
export function formatPercent(
  fraction: number | null | undefined,
  digits = 1,
): string {
  if (fraction === null || fraction === undefined || !Number.isFinite(fraction)) {
    return '—';
  }
  return `${formatter(resolveLocale(), digits).format(fraction)}%`;
}

/**
 * Renders an instant as an unambiguous UTC wall clock, e.g.
 * `2024-03-31 14:30:00 UTC`.
 *
 * Used for every audit-bearing timestamp. Deliberately built from the UTC
 * getters rather than `Intl.DateTimeFormat`, so the output cannot shift with
 * the reader's zone and can be asserted byte-for-byte in tests.
 *
 * @param iso an ISO-8601 instant as emitted by the API (`...Z` or with offset).
 * @returns the UTC rendering, or an em dash if the value is absent or invalid.
 */
export function formatAuditInstant(
  iso: string | null | undefined,
): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp =
    `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}` +
    ` ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`;
  return `${stamp} UTC`;
}

/**
 * Renders a date-only value (`YYYY-MM-DD`) unchanged.
 *
 * Date-only fields carry no time and no zone, so there is nothing to convert.
 * Re-rendering them through a locale would invent an ordering ambiguity (is
 * `2024-01-02` the 2nd of January or the 1st of February?) — the exact class of
 * defect Phase 10.12 exists to remove. ISO `YYYY-MM-DD` is unambiguous and
 * sorts correctly, and matches the API wire form.
 */
export function formatDateOnly(value: string | null | undefined): string {
  return value && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : (value ?? '—');
}