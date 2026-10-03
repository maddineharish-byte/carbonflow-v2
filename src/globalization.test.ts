/**
 * Phase 10.12 — Globalization & Internationalization conformance.
 *
 * The four locales below are REPRESENTATIVE TEST CONFIGURATIONS for the
 * presentation layer. They are not business rules, not supported markets, and
 * not a statement about where CarbonFlow may be deployed. Nothing in this file
 * may be allowed to become a country check: if a future change reads one of
 * these tags to make a *business* decision, that is the defect these tests are
 * meant to catch.
 *
 * What is asserted here is deliberately narrow:
 *
 *   1. NUMBERS  - grouping and decimal separators follow the reader's locale,
 *                 and the digit count / rounding is identical everywhere. No
 *                 configuration may change a quantity.
 *   2. INSTANTS - audit-bearing timestamps render identically under every
 *                 locale and in UTC with an explicit marker, so two auditors in
 *                 different countries read the same wall clock.
 *   3. DATES    - date-only values (reporting periods, activity bounds) are
 *                 passed through as ISO `YYYY-MM-DD` and never re-ordered.
 *   4. SOURCES   - no country, currency, timezone or locale is hardcoded
 *                 anywhere in the schema, the backend or the frontend.
 *
 * Calculation precision, GWP sets, emission factor values and unit conversions
 * are explicitly OUT of scope and are not touched or re-derived here.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

import {
  formatQuantity,
  formatInteger,
  formatPercent,
  formatAuditInstant,
  formatDateOnly,
  resolveLocale,
  setLocaleForTests,
} from './services/format.ts';

/**
 * Representative presentation configurations.
 *
 * Chosen to span the formatting axes that actually differ between real
 * deployments: `en-IN` and `en-US` share `.` as decimal separator but differ in
 * grouping (Indian lakh/crore grouping inserts an extra digit group), `de-DE`
 * inverts both separators and uses `,` as the decimal mark, and `ja-JP` uses
 * ASCII digits with `en-US` grouping so that a naive "non-Latin digits" fix
 * would not silently pass.
 */
const CONFIGURATIONS = [
  { country: 'India', locale: 'en-IN' },
  { country: 'United States', locale: 'en-US' },
  { country: 'Germany', locale: 'de-DE' },
  { country: 'Japan', locale: 'ja-JP' },
];

// ---------------------------------------------------------------------------
// 1. Numbers
// ---------------------------------------------------------------------------

test('NUMBERS: grouping and decimal separator follow the reader locale', () => {
  const observed = CONFIGURATIONS.map(({ country, locale }) => {
    setLocaleForTests(locale);
    return { country, rendered: formatQuantity(1234.5678, 4) };
  });
  setLocaleForTests(null);

  assert.deepEqual(
    observed.map((o) => o.rendered),
    ['1,234.5678', '1,234.5678', '1.234,5678', '1,234.5678'],
    'each configuration must render with its own separators',
  );

  // The separators genuinely differ, otherwise the test proves nothing.
  const distinct = new Set(observed.map((o) => o.rendered));
  assert.equal(distinct.size, 2, 'de-DE must differ from the ASCII-dot locales');

  // en-IN shares the dot decimal separator with en-US but groups by lakh, so a
  // large value must separate the two configurations that a 4-digit sample
  // cannot.
  setLocaleForTests('en-IN');
  const indian = formatInteger(12345678);
  setLocaleForTests('en-US');
  const american = formatInteger(12345678);
  setLocaleForTests(null);
  assert.equal(indian, '1,23,45,678');
  assert.equal(american, '12,345,678');
});

test('NUMBERS: digit count and rounding are identical in every configuration', () => {
  for (const { country, locale } of CONFIGURATIONS) {
    setLocaleForTests(locale);
    const decimal = new Intl.NumberFormat(locale)
      .formatToParts(12345.6)
      .find((p) => p.type === 'decimal')?.value ?? '.';

    for (const digits of [0, 1, 2, 4]) {
      const rendered = formatQuantity(1234.56789, digits);
      // With zero fraction digits the output carries no decimal mark at all, so
      // the split is only meaningful for digits > 0.
      const parts = digits > 0 ? rendered.split(decimal) : [rendered];
      const integerPart = parts[0] ?? '';
      const fractionPart = parts[1] ?? '';

      // The integer part always carries the four significant digits of the
      // sample; the fraction carries exactly the requested number of digits,
      // zero-padded when needed.
      assert.equal(
        integerPart.replace(/\D/g, '').length,
        4,
        `${country}: ${rendered} must keep 4 integer digits`,
      );
      if (digits > 0) {
        assert.equal(
          fractionPart.length,
          digits,
          `${country}: ${rendered} must carry exactly ${digits} fraction digits`,
        );
        assert.match(fractionPart, /^\d+$/, `${country}: fraction must be digits only`);
      } else {
        assert.ok(
          !rendered.includes(decimal),
          `${country}: ${rendered} must carry no decimal mark at 0 digits`,
        );
      }
    }

    // Rounding must follow the same half-expand rule everywhere.
    assert.equal(formatQuantity(1234.56789, 2).split(decimal)[1], '57');
    assert.equal(formatQuantity(1234.56789, 0).split(decimal)[0].replace(/\D/g, ''), '1235');
  }
  setLocaleForTests(null);
});

test('NUMBERS: localization never changes the value, only its glyphs', () => {
  for (const { locale } of CONFIGURATIONS) {
    setLocaleForTests(locale);
    // Recover the separators from the same locale so the round trip strips the
    // right glyphs -- `,` groups and `.` decimals in en-US, the inverse in de-DE.
    const parts = new Intl.NumberFormat(locale).formatToParts(12345.6);
    const group = parts.find((p) => p.type === 'group')?.value ?? ',';
    const decimal = parts.find((p) => p.type === 'decimal')?.value ?? '.';

    for (const value of [0, 1, 999.5, 1234.5678, 1e9, -42.125]) {
      const rendered = formatQuantity(value, 4);
      const recovered = Number(
        rendered.split(group).join('').split(decimal).join('.'),
      );
      assert.ok(
        Number.isFinite(recovered),
        `${locale}: ${rendered} must be machine-parseable once separators are removed`,
      );
      assert.ok(
        Math.abs(recovered - value) < 1e-6,
        `${locale}: ${rendered} must still denote ${value}`,
      );
    }
  }
  setLocaleForTests(null);
});

test('NUMBERS: an absent measurement is visibly absent, not a locale zero', () => {
  for (const { locale } of CONFIGURATIONS) {
    setLocaleForTests(locale);
    for (const missing of [null, undefined, Number.NaN, Number.POSITIVE_INFINITY]) {
      assert.equal(formatQuantity(missing, 4), '—', `${locale}: must not fabricate 0`);
    }
  }
  setLocaleForTests(null);
});

test('NUMBERS: integer and percent helpers stay locale-consistent', () => {
  setLocaleForTests('de-DE');
  assert.equal(formatInteger(145000), '145.000');
  assert.equal(formatPercent(45.25, 1), '45,3%');
  setLocaleForTests('en-US');
  assert.equal(formatInteger(145000), '145,000');
  assert.equal(formatPercent(45.25, 1), '45.3%');
  setLocaleForTests(null);
});

// ---------------------------------------------------------------------------
// 2. Instants (UTC storage, unambiguous display)
// ---------------------------------------------------------------------------

test('INSTANTS: the same instant renders identically in every configuration', () => {
  // An instant that straddles a date boundary in some zones and not others:
  // 23:30Z is the next calendar day in IST (UTC+5:30) and the previous one in
  // the Americas. A locale-sensitive renderer would disagree here.
  const instant = '2024-03-31T23:30:00Z';

  const observed = CONFIGURATIONS.map(({ country, locale }) => {
    setLocaleForTests(locale);
    return { country, rendered: formatAuditInstant(instant) };
  });
  setLocaleForTests(null);

  for (const { country, rendered } of observed) {
    assert.equal(
      rendered,
      '2024-03-31 23:30:00 UTC',
      `${country} must see the same UTC instant, not its own wall clock`,
    );
  }
});

test('INSTANTS: the rendering is explicitly marked as UTC', () => {
  for (const { locale } of CONFIGURATIONS) {
    setLocaleForTests(locale);
    assert.match(formatAuditInstant('2024-06-01T12:00:00Z'), / UTC$/);
  }
  setLocaleForTests(null);
});

test('INSTANTS: an offset-bearing input is normalised to the same UTC instant', () => {
  const expected = '2024-03-31 23:30:00 UTC';
  const equivalents = [
    '2024-03-31T23:30:00Z',
    '2024-04-01T05:00:00+05:30', // India
    '2024-03-31T19:30:00-04:00', // United States (EDT)
    '2024-04-01T08:30:00+09:00', // Japan
    '2024-04-01T00:30:00+01:00', // Germany (CET)
    '2024-04-01T01:30:00+02:00', // Germany (CEST, DST already in effect)
  ];
  for (const { locale } of CONFIGURATIONS) {
    setLocaleForTests(locale);
    for (const iso of equivalents) {
      assert.equal(formatAuditInstant(iso), expected, `${locale}: ${iso}`);
    }
  }
  setLocaleForTests(null);
});

test('INSTANTS: an absent or unparseable timestamp degrades safely', () => {
  for (const { locale } of CONFIGURATIONS) {
    setLocaleForTests(locale);
    assert.equal(formatAuditInstant(null), '—');
    assert.equal(formatAuditInstant(undefined), '—');
    assert.equal(formatAuditInstant(''), '—');
    assert.equal(formatAuditInstant('not-a-date'), '—');
  }
  setLocaleForTests(null);
});

// ---------------------------------------------------------------------------
// 3. Date-only values
// ---------------------------------------------------------------------------

test('DATES: date-only values are never re-ordered by a locale', () => {
  // `2024-01-02` is 2 January in ISO order and would become 1 February under a
  // day-first rendering. Reporting period bounds must never be ambiguous.
  const rendered = CONFIGURATIONS.map(({ country, locale }) => {
    setLocaleForTests(locale);
    return { country, value: formatDateOnly('2024-01-02') };
  });
  setLocaleForTests(null);

  for (const { country, value } of rendered) {
    assert.equal(value, '2024-01-02', `${country} must see the same calendar date`);
  }
});

test('DATES: locale detection falls back rather than throwing', () => {
  setLocaleForTests(null);
  assert.ok(resolveLocale().length > 0);
  setLocaleForTests('de-DE');
  assert.equal(resolveLocale(), 'de-DE');
  setLocaleForTests(null);
});

// ---------------------------------------------------------------------------
// 4. Source-level guards
// ---------------------------------------------------------------------------

function walk(dir: string, extensions: string[]): string[] {
  const out: string[] = [];
  let entries: string[];
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const entry of entries) {
    if (entry === 'node_modules' || entry === '.git' || entry === 'dist' || entry === 'build') {
      continue;
    }
    const full = join(dir, entry);
    const stats = statSync(full);
    if (stats.isDirectory()) {
      out.push(...walk(full, extensions));
    } else if (extensions.some((ext) => entry.endsWith(ext))) {
      out.push(full);
    }
  }
  return out;
}

function readAll(dir: string, extensions: string[]): Array<{ file: string; text: string }> {
  return walk(dir, extensions).map((file) => ({
    file: file.replace(/\\/g, '/'),
    text: readFileSync(file, 'utf8'),
  }));
}

/**
 * Region-specific tokens that have no legitimate place in production source.
 *
 * The list is deliberately GLOBAL: every region named in the Phase 10.12 test
 * configurations appears, plus a few adjacent ones. An India-only deny-list
 * would pass a codebase that had quietly hardcoded, say, `America/New_York`.
 *
 * IANA zone identifiers and currency symbols are matched literally because they
 * are unambiguous. Bare ISO codes are matched on WORD boundaries: `AUD` is a
 * currency but `AUDIT`/`AUDITOR` are domain nouns, and `CAD` is a currency but
 * `CADENCE` is not a defect.
 */
const LITERAL_REGION_TOKENS = [
  // IANA time zones
  'Asia/Kolkata', 'Asia/Tokyo', 'Asia/Calcutta', 'Asia/Shanghai',
  'America/New_York', 'America/Chicago', 'America/Denver',
  'America/Los_Angeles', 'Europe/Berlin', 'Europe/London', 'Europe/Paris',
  // Currency symbols
  '₹', '€', '£', '¥', '₩',
];

/** Bare ISO 4217 / ISO 3166 codes, matched only as whole words. */
const ISO_CODE_PATTERN = /\b(INR|USD|EUR|JPY|GBP|CHF|AUD|CAD)\b/g;

/** Files excluded from the region scan: the guard must not flag its own list. */
const GUARD_SELF = 'src/globalization.test.ts';

function regionOffenders(text: string): string[] {
  const hits: string[] = [];
  for (const token of LITERAL_REGION_TOKENS) {
    if (text.includes(token)) hits.push(token);
  }
  // An ISO code is only a hit when it stands alone: not part of a longer word,
  // and not the prefix of a hyphen/underscore-separated identifier. That rules
  // out "AUDIT_READY" and test ids like "AUD-WFL-01" while still catching a
  // quoted SQL literal such as DEFAULT 'USD'.
  for (const match of text.matchAll(ISO_CODE_PATTERN)) {
    const index = match.index ?? 0;
    const before = text[index - 1] ?? '';
    const after = text[index + match[0].length] ?? '';
    if (/[A-Za-z0-9_-]/.test(before) || /[A-Za-z0-9_-]/.test(after)) continue;
    hits.push(match[0]);
  }
  return [...new Set(hits)];
}

test('GUARD: no region-specific token is hardcoded in the React frontend', () => {
  const offenders: string[] = [];
  for (const { file, text } of readAll('src', ['.ts', '.tsx'])) {
    // src/services/format.ts is the sanctioned localization boundary; it reads
    // the locale from the runtime and names no region.
    if (file.endsWith('src/services/format.ts')) continue;
    if (file.endsWith(GUARD_SELF)) continue;
    for (const token of regionOffenders(text)) offenders.push(`${file} -> ${token}`);
  }
  assert.deepEqual(offenders, [], `frontend hardcodes a region: ${offenders.join(', ')}`);
});

test('GUARD: no region-specific token is hardcoded in the Java backend', () => {
  const offenders: string[] = [];
  for (const { file, text } of readAll('backend-java/src/main', ['.java'])) {
    for (const token of regionOffenders(text)) offenders.push(`${file} -> ${token}`);
  }
  assert.deepEqual(offenders, [], `backend hardcodes a region: ${offenders.join(', ')}`);
});

/** Removes `--` line comments and `/* *\/` blocks so prose is never scanned. */
function stripSqlComments(sql: string): string {
  return sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*/g, ' ');
}

/** Removes Java line and block comments so prose is never scanned. */
function stripJavaComments(java: string): string {
  return java.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
}

test('GUARD: the schema defaults no country or currency for a tenant', () => {
  // The Phase 10.12 defect: `organizations.country ... DEFAULT 'US'` and
  // `organization_settings.currency ... DEFAULT 'USD'` silently invented a
  // jurisdiction for any tenant that did not state one.
  //
  // V1 is exempt: it is immutable, already-applied Flyway history, and V9
  // neutralises the defaults it created. Any migration added from here on is
  // scanned, so the defaults cannot be reintroduced. Comments are stripped
  // first, because V9 necessarily NAMES the defaults it removes.
  const pattern = /DEFAULT\s+'(?:US|USD|IN|INR|DE|EUR|JP|JPY|GB|GBP|CA|CAD|AU|AUD|CH|CHF)'/gi;
  const offenders: string[] = [];
  for (const { file, text } of readAll('db/migration', ['.sql'])) {
    if (file.includes('V1__carbonflow_initial_schema')) continue;
    pattern.lastIndex = 0;
    if (pattern.test(stripSqlComments(text))) offenders.push(file);
  }
  assert.deepEqual(
    offenders,
    [],
    'a migration still defaults a tenant to one country/currency: ' + offenders.join(', '),
  );
});

test('GUARD: V9 actually removed the V1 country and currency defaults', () => {
  const v1 = readFileSync('db/migration/V1__carbonflow_initial_schema.sql', 'utf8');
  const v9 = readFileSync(
    'db/migration/V9__remove_hardcoded_country_currency_defaults.sql',
    'utf8',
  );

  // V1 is where the defect was introduced; assert it is still recognisable so
  // this test cannot pass vacuously if V1 is ever rewritten.
  assert.match(v1, /country VARCHAR\(10\) NOT NULL DEFAULT 'US'/);
  assert.match(v1, /currency VARCHAR\(10\) NOT NULL DEFAULT 'USD'/);

  assert.match(v9, /ALTER TABLE organizations[\s\S]*?ALTER COLUMN country DROP DEFAULT/);
  assert.match(v9, /ALTER TABLE organization_settings[\s\S]*?ALTER COLUMN currency DROP DEFAULT/);
  assert.match(v9, /ALTER TABLE organization_settings[\s\S]*?ALTER COLUMN currency DROP NOT NULL/);
});

test('GUARD: timestamps are rendered through the sanctioned boundary only', () => {
  // `toLocaleDateString` silently drops the time component, which is
  // unacceptable for an audit fact; a bare `toLocaleString` on an instant
  // renders in the reader's zone with no offset shown. Both are banned outside
  // src/services/format.ts, which builds its UTC rendering explicitly.
  const offenders: string[] = [];
  for (const { file, text } of readAll('src', ['.ts', '.tsx'])) {
    if (file.endsWith('src/services/format.ts')) continue;
    if (/\.toLocaleDateString\s*\(/.test(text)) offenders.push(`${file} (toLocaleDateString)`);
    if (/\.toLocaleTimeString\s*\(/.test(text)) offenders.push(`${file} (toLocaleTimeString)`);
    if (/new Date\([^)]*\)\.toLocaleString\s*\(/.test(text)) {
      offenders.push(`${file} (new Date().toLocaleString)`);
    }
    // Any Intl date formatting must pin an explicit timeZone.
    for (const match of text.matchAll(/Intl\.DateTimeFormat\s*\(([^)]*)\)/g)) {
      if (!/timeZone/.test(match[1])) offenders.push(`${file} (Intl.DateTimeFormat w/o timeZone)`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `timestamps bypass the UTC boundary: ${offenders.join(', ')}`,
  );
});

test('GUARD: rendered quantities never use toFixed, which cannot localize', () => {
  // `toFixed` always emits `.` and never groups, so it puts `1234.5678` next to
  // `1.234,5678` on the same screen for a de-DE reader.
  //
  // Two contexts legitimately keep it, and both are non-render:
  //   * machine-readable ARIA attributes (aria-valuenow), which must stay
  //     parseable numbers rather than display strings, and
  //   * rounding a value back into a NUMBER (`Number(x.toFixed(2))`), which is
  //     arithmetic, not presentation.
  const offenders: string[] = [];
  for (const { file, text } of readAll('src/components', ['.tsx'])) {
    for (const [index, line] of text.split('\n').entries()) {
      if (!line.includes('.toFixed(')) continue;
      if (/aria-valuenow|aria-valuemax|aria-valuemin/.test(line)) continue;
      if (/Number\(.*\.toFixed\(/.test(line)) continue;
      offenders.push(`${file}:${index + 1}`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `rendered value uses toFixed instead of formatQuantity: ${offenders.join(', ')}`,
  );
});

test('GUARD: the backend never derives "now" from the system default zone', () => {
  // `LocalDateTime.now()` / `LocalDate.now()` read the JVM default zone, so the
  // same record would land on a different calendar day depending on where the
  // process runs. Instants use `Instant.now()`; date-only logic must pass an
  // explicit zone.
  const offenders: string[] = [];
  const zoneLess = /\b(LocalDateTime|LocalDate|ZonedDateTime|OffsetDateTime)\.now\s*\(\s*\)/g;
  for (const { file, text } of readAll('backend-java/src/main', ['.java'])) {
    // Comments are stripped: explaining WHY a zone is pinned necessarily names
    // the zone-less call that is being avoided.
    const code = stripJavaComments(text);
    for (const match of code.matchAll(zoneLess)) {
      offenders.push(`${file} -> ${match[1]}.now()`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `system-zone-dependent clock used: ${offenders.join(', ')}`,
  );
});

test('GUARD: the backend sets no JVM default locale or timezone', () => {
  // `Locale.setDefault` / `TimeZone.setDefault` would silently re-shape every
  // formatter and every scheduler in the process.
  const offenders: string[] = [];
  for (const { file, text } of readAll('backend-java/src/main', ['.java'])) {
    if (/Locale\.setDefault\s*\(/.test(text)) offenders.push(`${file} (Locale.setDefault)`);
    if (/TimeZone\.setDefault\s*\(/.test(text)) offenders.push(`${file} (TimeZone.setDefault)`);
  }
  assert.deepEqual(offenders, [], `process-wide locale/zone mutated: ${offenders.join(', ')}`);
});

test('GUARD: timestamp columns in the schema are timezone-aware', () => {
  // A bare `TIMESTAMP` column silently drops the offset and is read back in the
  // session zone. Every instant-bearing column must be `WITH TIME ZONE`.
  const offenders: string[] = [];
  for (const { file, text } of readAll('db/migration', ['.sql'])) {
    for (const [index, line] of text.split('\n').entries()) {
      if (!/\bTIMESTAMP\b/i.test(line)) continue;
      if (/WITH TIME ZONE/i.test(line)) continue;
      offenders.push(`${file}:${index + 1}`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `timezone-naive TIMESTAMP column: ${offenders.join(', ')}`,
  );
});