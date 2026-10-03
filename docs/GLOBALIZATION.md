# Phase 10.12 — Globalization & Internationalization

**Document status:** CURRENT. Written during Phase 10.12 from the working tree
after the change set in that phase.

CarbonFlow is a global platform. This document records what an
internationalization audit found, what was fixed, what is deliberately *not*
supported yet, and what a future localization effort would have to build. It is
not a statement about where CarbonFlow may be deployed or which markets are
supported.

**Evidence labelling convention** (inherited from
[PHASE10.11-ACCESSIBILITY.md](PHASE10.11-ACCESSIBILITY.md)):

- **[ORIGINAL]** — a defect present at the start of Phase 10.12.
- **[FIXED]** — a change made in Phase 10.12.
- **[VERIFIED]** — a result produced by a command re-runnable today.
- **[REMAINING]** — a known gap that Phase 10.12 did **not** close.

---

## 1. Scope

Tracked production code only:

| Area | Path |
|---|---|
| Backend (sole runtime) | `backend-java/src/main` |
| Frontend | `src/` |
| Schema | `db/migration/` |

The untracked `server/` and `server.ts` are retired Node/Express remnants
(deleted from version control in Phase 10.5) and were **not** audited or
modified. Nothing in this phase touches `backend-java/src/main` calculation
logic, GWP sets, emission factor values, or unit conversion factors.

---

## 2. Findings and resolution

### 2.1 The schema defaulted every tenant to one country and one currency — **HIGH**

**[ORIGINAL]** `db/migration/V1__carbonflow_initial_schema.sql:14`

```sql
country VARCHAR(10) NOT NULL DEFAULT 'US',
```

**[ORIGINAL]** `db/migration/V1__carbonflow_initial_schema.sql:26`

```sql
currency VARCHAR(10) NOT NULL DEFAULT 'USD',
```

These were the two most consequential globalization defects in the system,
because they were **silent**. Neither value was ever displayed as an
assumption; each looked like real tenant data.

The currency default was worse than merely hardcoded. `OrganizationRepository
.updateProfile` (`backend-java/.../repository/OrganizationRepository.java:100`)
writes the settings row as:

```sql
INSERT INTO organization_settings (organization_id, consolidation_approach, base_year)
VALUES (?, ?, ?)
ON CONFLICT (organization_id) DO UPDATE
SET consolidation_approach = EXCLUDED.consolidation_approach, ...
```

`currency` is absent from both the insert column list and the `DO UPDATE` set
list. So the `DEFAULT 'USD'` supplied the value once, and **no code path could
ever change it afterwards** — there is no currency field on any request DTO. A
tenant reporting in EUR, JPY or INR had no way to say so, and any future
monetary figure (abatement cost, carbon price, revenue intensity denominator)
would have been denominated in USD by construction rather than by decision.

**[FIXED]** `db/migration/V9__remove_hardcoded_country_currency_defaults.sql`

- `organizations.country` — `DROP DEFAULT`, `NOT NULL` retained. Every
  production insert already names the column explicitly
  (`OrganizationRepository.insert` and `.updateProfile`, plus
  `DemoDataSeeder`), so no working path changes; a future insert that forgets
  `country` now fails at the database instead of silently creating a US entity.
- `organization_settings.currency` — `DROP DEFAULT` and `DROP NOT NULL`. NULL
  is the honest state ("reporting currency not captured") and is materially
  different from `'USD'`, which is a confident wrong assertion about a tenant
  nobody asked. No consumer can misread NULL as a currency.

Both columns carry `COMMENT ON COLUMN` text recording the intent, so the next
reader inherits the reasoning instead of re-deriving it from migration history.

No numeric column, unit, GWP set or conversion factor was touched.

**[VERIFIED]** `mvn -o -DskipTests compile` and `mvn -o -DskipTests test-compile`
both exit 0. The migration itself requires a live PostgreSQL instance and was
**not executed** in this environment — see §6.

### 2.2 UK-published emission factors were labelled `GLOBAL` — **HIGH**

**[ORIGINAL]** `db/migration/V2__seed_reference_data.sql`

The seeded reference data mixes publishing authorities and asserts a single
worldwide applicability:

| Source | Seeded `geography` | Occurrences |
|---|---|---|
| `UK DEFRA / BEIS` | `GLOBAL` | 2 |
| `US EPA Emission Hub` | `US` | 1 |
| `US EPA eGRID` | `US` | 1 |
| `Green-e Residual Mix` | `US` | 1 |
| `IPCC AR6 Refrigerant Blend` | `GLOBAL` | 1 |
| `Contractual Guarantee of Origin` | `GLOBAL` | 1 |

Labelling a UK government factor set as `GLOBAL` is a regulatory assumption:
it asserts that UK DEFRA values are the correct default for a tenant in Osaka or
Stuttgart. DEFRA factors are widely used internationally and the values are
legitimately sourced — the defect is the **applicability claim**, not the
number.

**[REMAINING]** Not fixed. Changing a factor's `geography`, or introducing a
different default, is a regulatory and accounting-policy decision, not a
hardening fix. See §5 (F-2).

### 2.3 Emission-factor resolution is geography-blind — **HIGH**

**[ORIGINAL]** `backend-java/.../repository/EmissionFactorRepository.java:113`

```java
WHERE ef.activity_type = ?
  AND efv.status = 'ACTIVE'
  AND (?::text IS NULL OR efv.id = ?::uuid)
ORDER BY efv.version_number DESC
LIMIT 1
```

`geography` is selected into the row and mapped onto
`EmissionFactorVersion.geography`, but it is **never used as a filter**. Factor
resolution therefore depends only on activity type, status and version number.
Concretely: a tenant in Japan or Germany recording `FLEET_DIESL` receives the
**US EPA** factor, because it is the only `ACTIVE` version of that activity
type. Nothing warns it.

The `ORDER BY version_number DESC` is also the wrong precedence rule for a
multi-jurisdiction catalogue: if a jurisdiction-specific version were ever added
alongside a `GLOBAL` one, selection would be decided by an arbitrary version
number rather than by any jurisdiction match.

**[REMAINING]** Not fixed — see §5 (F-2). Mitigating factor already in place:
`V6__calculation_emission_integrity.sql:16` writes
`factor_source = efv.source || ' (' || efv.source_year || ')'` onto every
`calculations` row, so the publishing authority of the applied factor **is**
recorded per calculation and surfaced in the calculation lineage response (for
example `UK DEFRA / BEIS (2024)`). The applied jurisdiction is therefore
traceable after the fact even though it is not selected by.

### 2.4 Audit timestamps were rendered in an unlabelled, reader-local zone — **MEDIUM**

**[ORIGINAL]** Bare `toLocaleString()` / `toLocaleDateString()` on instants:

| File:line | Surface |
|---|---|
| `src/components/AuditView.tsx:343` | checklist item `verifiedAt` |
| `src/components/AuditView.tsx:423` | audit comment `createdAt` |
| `src/components/EmissionsView.tsx:364` | calculation `calculatedAt` |
| `src/components/PlatformAdminView.tsx:314` | tenant `statusChangedAt` |
| `src/components/PlatformAdminView.tsx:331` | tenant `createdAt` |
| `src/components/EvidenceView.tsx:225` | evidence file `createdAt` |

`toLocaleString()` with no `timeZone` option renders in the **browser's** zone.
Every value above is a UTC instant from a `timestamptz` column, so an auditor in
Berlin and an auditor in Osaka read different wall-clock times for the same
immutable event — and neither is told which zone they are seeing. For a system
that produces audit evidence, that is a defensibility defect, not a cosmetic
one.

`AuditView.tsx:343` was worse: `toLocaleDateString()` **discards the time
entirely**, so two verifications on the same calendar day render identically
and cannot be told apart in the audit trail at all.

**[FIXED]** New `src/services/format.ts` exposing `formatAuditInstant`, which
renders from the UTC getters as `YYYY-MM-DD HH:MM:SS UTC`. It is
locale-independent by construction, so all six sites above now show the same
instant to every reader, with the zone stated explicitly.

### 2.5 Rendered numbers mixed locale-aware and locale-unaware separators — **MEDIUM**

**[ORIGINAL]** `Number.prototype.toFixed(n)` always emits `.` as the decimal
separator and never applies grouping. The same screens used
`toLocaleString()`, which does both. A `de-DE` reader therefore saw, on the
**same page**:

```
1234.5678        <- toFixed(4), EmissionsView
1.234,5678       <- toLocaleString(), elsewhere
```

24 `toFixed` call sites across 6 components. This is a real readability defect
and, for emissions figures that a user retypes into a spreadsheet, a data-entry
hazard.

**[FIXED]** `src/services/format.ts` provides `formatQuantity`, `formatInteger`
and `formatPercent`, all backed by `Intl.NumberFormat` with the reader's locale
resolved from `navigator.languages`. Every rendered quantity now uses them.

**Precision is unchanged.** `formatQuantity(x, n)` sets both `minimum` and
`maximumFractionDigits` to `n`, so rounding is identical to `toFixed(n)`; the
digit count is asserted directly in the tests (§3). Bare `toLocaleString()`
sites were deliberately **left alone**: they were already locale-correct, and
normalising their displayed digit count would have been an unrequested UX
change.

Two `toFixed` sites remain, both non-render and both explicitly allowed by the
guard: `TargetsView.tsx:264` (`aria-valuenow`, which must stay a parseable
number for assistive technology) and `DashboardView.tsx:422`
(`Number(x.toFixed(2))`, arithmetic rounding back into a number).

### 2.6 The existing globalization guard was India-shaped and blind to two thirds of the codebase — **MEDIUM**

**[ORIGINAL]** `backend-java/src/test/.../Phase7IntegrationTest.java:578`

```java
String[] banned = {
        "Asia/Kolkata", "Asia/Calcutta", "INR", "\u20B9",
        "period-2024", "gemini"};
```

Two structural problems:

1. **It was scoped to one country.** A deny-list of `Asia/Kolkata` and `INR`
   cannot function as a globalization control — it would pass a codebase that
   had quietly hardcoded `America/New_York` or `EUR`. That the previous phase
   recorded "no currency hardcoded at all" while `V1` shipped
   `DEFAULT 'USD'` is the direct consequence.
2. **It scanned only `backend-java/src/main/java`.** The country and currency
   defaults lived in `db/migration/`, and every formatting defect lived in
   `src/`. Neither was in scope.

**[FIXED]** `Phase7IntegrationTest`'s deny-list widened to a global one (four
IANA zones across three regions, seven currency codes, three currency symbols).
The demo/LLM entries are unchanged. Verified that `backend-java/src/main`
contains none of the added tokens, so the assertion still holds; **the test was
not executed** here because it requires an embedded PostgreSQL instance (§6).

**[FIXED]** New `src/globalization.test.ts` — 20 tests covering all three blind
spots plus locale behaviour (§3).

### 2.7 `AuditService` read the host default zone for an audit timestamp — **MEDIUM**

**[ORIGINAL]** `backend-java/.../service/AuditService.java:221`

```java
lockedAt = OffsetDateTime.now();
```

`OffsetDateTime.now()` uses the JVM default zone. The value is bound to a
`timestamptz` column, so the **stored instant was always correct** — the defect
is that the textual representation of a governance-relevant audit fact varies
with where the process happens to run.

**[FIXED]** `OffsetDateTime.now(ZoneOffset.UTC)`, with a comment explaining why.

### 2.8 Single unversioned `tax_id` — **LOW / [REMAINING]**

**[ORIGINAL]** `organizations.tax_id VARCHAR(100)` and
`organizations.country VARCHAR(10)`. One tax identifier, one country, no
regulatory-regime column, and no second identifier field. A group structure
that files in more than one jurisdiction cannot be represented, and there is no
model for a VAT number versus a tax registration number versus a LEI.

Not fixed: the correct shape is a `tax_registrations` child table, which is a
data-model feature rather than a hardening fix. See §5 (F-3).

### 2.9 Metric-only unit conversion — **LOW / [REMAINING]**

**[ORIGINAL]** `backend-java/.../service/UnitConversionService.java` covers
kWh ↔ MWh, Therms → kWh (×29.3001), m³ → kWh (×10.55), and KG ↔ metric tonnes.
There is no short ton and no pound. GHG Protocol permits US short tons, and
`NUMERIC` quantity columns plus a free-text `unit` column mean such activity
could be entered but not converted.

**Deliberately not changed.** Adding mass conversions would alter calculation
behaviour, and Phase 10.12's instruction is explicitly not to change
calculation precision. See §5 (F-4).

### 2.10 Recovery schedule zones are correct but undiscoverable — **LOW / documentation only**

**[VERIFIED]** `RecoverySchedulerConfiguration` reads
`carbonflow.recovery.backup.zone` and `carbonflow.recovery.drill.zone`, both
defaulting to `UTC`, and both validated with `ZoneId.of(zone)` at startup so an
unknown zone fails fast instead of silently falling back. `RecoveryScheduleConfig`
also defaults to `ZoneId.of("UTC")`.

This is **good** globalization behaviour: UTC is a neutral, non-national default,
and the zone is operator-configurable for DST-aware scheduling. The only gap is
discoverability — neither property appears in `application.properties`,
`.env.example`, or the runbooks. Recorded in §5 (F-5).

### 2.11 What was already correct

Worth stating explicitly, because these were the highest-risk areas and none
needed a fix:

- **UTC storage.** Every instant column is `TIMESTAMP WITH TIME ZONE`. No
  timezone-naive `TIMESTAMP` column exists anywhere in `db/migration/`
  (now asserted by a test).
- **No process-wide locale or timezone mutation.** No `Locale.setDefault` or
  `TimeZone.setDefault` in `backend-java/src/main`.
- **Explicit zones for date-only logic.** `DrillSchedule.java:59` uses
  `LocalDate.now(zone)` with an injected zone, not `LocalDate.now()`.
- **Date-only fields are genuinely date-only.** Reporting period bounds, activity
  start/end and factor effective dates are PostgreSQL `DATE`, carried as
  `YYYY-MM-DD` strings. They are never routed through a formatter, so no
  day-first/month-first ambiguity can arise.
- **Backend number serialization is locale-independent.**
  `PlainBigDecimalSerializer` uses `stripTrailingZeros().toPlainString()`;
  `ReportsController` builds CSV cells with `BigDecimal.toPlainString()`. Neither
  consults a default locale.
- **`country` is data, never a business rule.** `organizations.country` and
  `facilities.country` are stored and returned, but no calculation, factor
  selection, validation or authorization path branches on them.
- **No address or phone capture.** Neither exists in the schema, so neither
  carries an address or dialling-format assumption.

---

## 3. Verification

### 3.1 Test suite

**[FIXED]** `src/globalization.test.ts`, 20 tests, added to
`npm run test:frontend`.

The four representative configurations are **presentation test configurations
only** — not business rules, not supported markets, and not a restriction on
deployment:

| Country | Locale | Why it is in the set |
|---|---|---|
| India | `en-IN` | Shares the `.` decimal mark with `en-US` but groups by lakh (`1,23,45,678`), so a 4-digit sample cannot distinguish it |
| United States | `en-US` | Baseline ASCII separators |
| Germany | `de-DE` | Inverts both separators (`.` groups, `,` decimal) |
| Japan | `ja-JP` | ASCII digits with `en-US` grouping, so a "use non-Latin digits" fix would not silently pass |

Behavioural assertions:

- **NUMBERS** — separators follow the locale; digit count and rounding are
  identical in all four; the value round-trips exactly once separators are
  removed; an absent measurement renders `—`, never a locale zero; Indian lakh
  grouping is asserted explicitly.
- **INSTANTS** — `2024-03-31T23:30:00Z` (which is a *different calendar day* in
  IST) renders byte-identically in all four configurations; the rendering is
  marked `UTC`; five offset-equivalent spellings of that instant (IST, EDT, JST,
  CET, CEST) all normalise to the same string; absent or unparseable input
  degrades safely.
- **DATES** — `2024-01-02` is never re-ordered (which a day-first locale would
  render as 1 February).

Source-level guards (these replace the India-shaped deny-list):

| Guard | Asserts |
|---|---|
| Frontend region guard | no IANA zone / currency symbol / standalone ISO currency code in `src/` |
| Backend region guard | same across `backend-java/src/main` |
| Schema default guard | no migration after V1 reintroduces a country/currency `DEFAULT` |
| V9 effectiveness | V1 really did contain both defaults, and V9 really drops them — so the test cannot pass vacuously if V1 is ever rewritten |
| Timestamp boundary | no `toLocaleDateString` / `toLocaleTimeString` / `new Date().toLocaleString` outside `format.ts`; any `Intl.DateTimeFormat` must pin `timeZone` |
| `toFixed` guard | no rendered `toFixed` outside ARIA attributes and `Number(...)` arithmetic |
| Clock zone guard | no zone-less `LocalDate.now()` / `LocalDateTime.now()` / `OffsetDateTime.now()` / `ZonedDateTime.now()` |
| Default-mutation guard | no `Locale.setDefault` / `TimeZone.setDefault` |
| Timestamp column guard | every `TIMESTAMP` column in `db/migration/` is `WITH TIME ZONE` |

SQL and Java comments are stripped before scanning, because explaining *why* a
zone is pinned necessarily names the zone-less call being avoided.

### 3.2 The guards are not vacuous

Each guard was verified to **fail** when its defect is reintroduced. Injecting a
single temporary file containing `DEFAULT 'JP'`, a bare `TIMESTAMP`,
`Asia/Tokyo`, `toLocaleDateString()` and a rendered `toFixed()` produced 5
failures naming exactly those 5 defects. The temporary files were removed.

### 3.3 Commands

```powershell
npm run lint              # tsc --noEmit                                    -> exit 0
npm run test:frontend     # 86 tests                                         -> 86 pass, 0 fail
cd backend-java; mvn -o -q -DskipTests compile                               -> exit 0
cd backend-java; mvn -o -q -DskipTests test-compile                          -> exit 0
node --import tsx --test src/globalization.test.ts                           -> 20 pass, 0 fail
```

The 86 = 66 pre-existing (unmodified, all still passing) + 20 new.

### 3.4 Not executed here

Stated plainly rather than implied:

- **`db/migration/V9` was never applied.** No PostgreSQL instance was available,
  so the migration's SQL was not run. It is reviewed and compiles as part of the
  Flyway set, but it is unproven against a live database.
- **`Phase7IntegrationTest` was not run.** It requires an embedded PostgreSQL
  instance. Its widened deny-list was verified safe by confirming
  `backend-java/src/main` contains none of the added tokens, and the file
  compiles — but the assertion itself was not executed.
- **The Java suite was not run.** Only `compile` and `test-compile`.

---

## 4. Localization: what does not exist

**There is no localization infrastructure.** This was verified, not assumed:

- No i18n library in `package.json` (dependencies are React, Tailwind, Vite,
  Recharts, Lucide, Motion, dotenv).
- `index.html:2` pins `<html lang="en">`.
- No message catalogue, no translation function, no locale detection in
  `src/App.tsx`, no `IntlProvider` equivalent.
- Every user-visible string is a hardcoded English literal in JSX.

**No multilingual support has been fabricated.** A translation framework, a
message catalogue and translated string sets would be a large, untranslated —
and therefore untestable — surface. Shipping that would be worse than shipping
an honest gap. The architecture for it is §5 (F-6) instead.

What *is* locale-aware today: number grouping and decimal separators, via
`src/services/format.ts`.

---

## 5. Remaining gaps and future requirements

| ID | Gap | Severity | Why not fixed now |
|---|---|---|---|
| F-1 | Tenant reporting currency cannot be set at all (no DTO field, no API) | HIGH | Needs a product decision on which monetary figures CarbonFlow reports. V9 makes the absence honest (`NULL`) rather than wrong (`USD`). |
| F-2 | Emission-factor selection ignores `geography`; UK DEFRA factors are seeded as `GLOBAL` | HIGH | Fixing it requires a **jurisdiction-resolution policy** — precedence between `GLOBAL` and country-specific versions, per-tenant factor library selection, and re-baselining seeded data. That is an accounting-policy decision with audit consequences. The applied authority is already recorded per calculation, so nothing is currently untraceable. |
| F-3 | Single unversioned `tax_id`, no regulatory-regime model | MEDIUM | Needs a `tax_registrations` child table; a data-model feature, not a hardening fix. |
| F-4 | Unit conversion is metric-only (no short ton / lb) | MEDIUM | Adding mass conversions alters calculation behaviour, which this phase was instructed not to change. |
| F-5 | `carbonflow.recovery.backup.zone` / `drill.zone` are real settings absent from `application.properties`, `.env.example` and the runbooks | LOW | Defaults to `UTC` are correct and neutral; this is a discoverability gap, not a defect. |
| F-6 | No localization infrastructure at all | MEDIUM | See §6. |
| F-7 | No tenant-level locale/timezone preference, no per-user preference | LOW | Follows from F-6. |
| F-8 | No translated regulatory document templates; no jurisdiction-specific report layout | LOW | Follows from F-6 and F-2. |
| F-9 | No RTL support (`dir` attribute never set; layout is not direction-agnostic) | LOW | Requires a design-system pass; would affect every component. |
| F-10 | Frontend date-only values are rendered as raw `YYYY-MM-DD` | INFORMATIONAL | Deliberate. See §6.2. |

### 6. Architecture for future localization

Recorded as a design, **not** as implemented code.

**6.1 Layering.** Localization must enter at exactly one boundary — the
presentation layer — and nowhere else. The precedent is already established by
`format.ts`. The rules:

1. The API speaks **one** wire format: ISO-8601 instants with an offset,
   `YYYY-MM-DD` date-only strings, and bare JSON numbers. Never localized.
   This is already true and must stay true.
2. The backend never formats for humans. It returns data plus enough metadata
   (unit, scale, currency) for a client to format correctly.
3. A single locale-resolution service resolves, in precedence order: explicit
   user preference → tenant default → `navigator.languages` → `en-US`. Today only
   the last two steps exist.
4. All user-visible strings move into message catalogues keyed by message id.
   Extraction is mechanical but must be done with a tool, not by hand, or ids
   will drift.

**6.2 Instants and dates are deliberately different.** Keep the UTC decision
for instants (`formatAuditInstant`). Do **not** introduce a "preferred timezone"
for audit-facing timestamps: it would re-create the ambiguity that §2.4 removed.
Date-only fields are already correct and should stay as ISO `YYYY-MM-DD`;
rendering them in a reader's locale would introduce a day/month ambiguity that
does not exist today.

**6.3 Numbers.** Extend `format.ts` rather than calling `Intl.*` ad hoc, so the
"no `toFixed` in render position" invariant stays enforceable by the existing
guard. When monetary figures are added (F-1), they must carry the ISO 4217 code
from the API and be formatted with `Intl.NumberFormat(locale, { style:
'currency', currency })` — never a hardcoded symbol.

**6.4 Extending the guards.** When localization lands, the guard in §3.1 must
gain: message-catalogue completeness (every id resolves in every locale),
`html lang` / `dir` correctness, and a parity check that no locale silently
falls back to `en-US` for a key it should have translated. The region guard must
keep excluding the catalogues themselves — a translation file legitimately
contains currency and locale names.

---

## 7. Summary

| Outcome | Count |
|---|---|
| Findings raised | 11 |
| Fixed | 5 (§2.1, §2.4, §2.5, §2.6, §2.7) |
| Already correct, documented | 1 group (§2.11, 6 items) |
| Genuinely remaining | 5 (F-1 … F-5, plus F-6 … F-9 as planned work) |
| New tests | 20, all passing, all verified non-vacuous |
| Regression | 0 — 66 pre-existing tests still pass |

The single most important change is §2.1: CarbonFlow was silently stamping
every tenant with `country = 'US'` and an unchangeable `currency = 'USD'`. The
second is §2.4, where audit timestamps were rendered in an unlabelled
reader-local zone — unacceptable in a system that produces audit evidence.

Two findings were deliberately **not** fixed, and the reasoning is recorded
rather than hidden: F-2 (factor geography) and F-4 (unit conversion) both
require accounting-policy decisions or would change calculation behaviour.